package org.http4k.filter

import arrow.core.*
import arrow.effects.ForIO
import arrow.effects.IO
import arrow.effects.applicative
import arrow.effects.fix
import arrow.instances.traverse
import arrow.optics.*
import org.http4k.base64Decoded
import org.http4k.core.*
import org.http4k.core.Method.OPTIONS
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.UNAUTHORIZED
import org.http4k.core.Status.Companion.UNSUPPORTED_MEDIA_TYPE
import org.http4k.core.arrow.catch
import org.http4k.core.arrow.then
import org.http4k.lens.Failure
import org.http4k.lens.Header
import org.http4k.lens.LensFailure
import org.http4k.lens.RequestContextLens
import org.http4k.routing.ResourceLoader
import org.http4k.routing.ResourceLoader.Companion.Classpath
import java.io.PrintWriter
import java.io.StringWriter

data class CorsPolicy(val origins: List<String>,
                      val headers: List<String>,
                      val methods: List<Method>) {

    companion object {
        val UnsafeGlobalPermissive = CorsPolicy(listOf("*"), listOf("content-type"), Method.values().toList())
    }
}

object ServerFilters {

    /**
     * Add Cors headers to the Response, according to the passed CorsPolicy
     */
    object Cors {
        private fun List<String>.joined() = this.joinToString(", ")

        operator fun invoke(policy: CorsPolicy) = Filter { next ->
            { request ->
                val responseIO = if (request.method == OPTIONS) IO.just(Response(OK)) else next(request)
                responseIO.map { response ->
                    response.with(
                            Header.required("access-control-allow-origin") of policy.origins.joined(),
                            Header.required("access-control-allow-headers") of policy.headers.joined(),
                            Header.required("access-control-allow-methods") of policy.methods.map { it.name }.joined()
                    )
                }
            }
        }
    }

    /**
     * Adds Zipkin request tracing headers to the incoming request and outbound response. (traceid, spanid, parentspanid)
     */
    object RequestTracing {
        operator fun invoke(
                startReportFn: (Request, ZipkinTraces) -> Unit = { _, _ -> },
                endReportFn: (Request, Response, ZipkinTraces) -> Unit = { _, _, _ -> }): Filter = Filter { next ->
            {
                val fromRequest = ZipkinTraces(it)
                startReportFn(it, fromRequest)
                ZipkinTraces.THREAD_LOCAL.set(fromRequest)

                try {
                    val response = ZipkinTraces(fromRequest, next(ZipkinTraces(fromRequest, it)))
                    endReportFn(it, response, fromRequest)
                    response
                } finally {
                    ZipkinTraces.THREAD_LOCAL.remove()
                }
            }

        }
    }

    /**
     * Simple Basic Auth credential checking.
     */
    object BasicAuth {
        /**
         * Credentials validation function
         */
        operator fun invoke(realm: String, authorize: (Credentials) -> Boolean) = Filter { next ->
            {
                val credentials = it.basicAuthenticationCredentials()
                if (credentials == null || !authorize(credentials)) {
                    IO.just(Response(UNAUTHORIZED).header("WWW-Authenticate", "Basic Realm=\"$realm\""))
                } else next(it)
            }
        }

        /**
         * Static username/password validation
         */
        operator fun invoke(realm: String, user: String, password: String) = this(realm, Credentials(user, password))

        /**
         * Static credentials validation
         */
        operator fun invoke(realm: String, credentials: Credentials) = this(realm) { it == credentials }

        /**
         * Population of a RequestContext with custom principal object
         */
        operator fun <T> invoke(realm: String, key: RequestContextLens<T>, lookup: (Credentials) -> T?) = Filter { next ->
            {
                it.basicAuthenticationCredentials()
                        ?.let(lookup)
                        ?.let { found -> next(it.with(key of found)) }
                        ?: IO.just(Response(UNAUTHORIZED).header("WWW-Authenticate", "Basic Realm=\"$realm\""))
            }
        }

        private fun Request.basicAuthenticationCredentials(): Credentials? = header("Authorization")?.replace("Basic ", "")?.toCredentials()

        private fun String.toCredentials(): Credentials? = base64Decoded().split(":").let { Credentials(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }
    }

    /**
     * Bearer Auth token checking.
     */
    object BearerAuth {
        /**
         * Static token validation
         */
        operator fun invoke(token: String) = BearerAuth { it == token }

        /**
         * Static token validation function
         */
        operator fun invoke(checkToken: (String) -> Boolean) = Filter { next ->
            {
                if (it.bearerToken()?.let(checkToken) == true) next(it) else IO.just(Response(UNAUTHORIZED))
            }
        }

        /**
         * Population of a RequestContext with custom principal object
         */
        operator fun <T> invoke(key: RequestContextLens<T>, lookup: (String) -> T?) = Filter { next ->
            {
                it.bearerToken()
                        ?.let(lookup)
                        ?.let { found -> next(it.with(key of found)) }
                        ?: IO.just(Response(UNAUTHORIZED))
            }
        }

        private fun Request.bearerToken(): String? = header("Authorization")?.replace("Bearer ", "")
    }

    /**
     * Converts Lens extraction failures into correct HTTP responses (Bad Requests/UnsupportedMediaType).
     * This is required when using lenses to automatically unmarshal inbound requests.
     * Note that LensFailures from unmarshalling upstream Response objects are NOT caught to avoid incorrect server behaviour.
     */
    object CatchLensFailure : ErrorHandler<LensFailure> by CatchLensFailure()

    /**
     * Converts Lens extraction failures into correct HTTP responses (Bad Requests/UnsupportedMediaType).
     * This is required when using lenses to automatically unmarshal inbound requests.
     * Note that LensFailures from unmarshalling upstream Response objects are NOT caught to avoid incorrect server behaviour.
     *
     * Pass the failResponseFn param to provide a custom response for the LensFailure case
     */
    fun CatchLensFailure(failResponseFn: (LensFailure) -> IO<Response> = {
        IO.just(Response(BAD_REQUEST.description(it.failures.joinToString("; "))))
    }) = object : ErrorHandler<LensFailure> {
        override fun invoke(next: PartialHttpHandler<LensFailure>): HttpHandler = {
            next(it).then { result ->
                result.fold({ lensFailure: LensFailure ->
                    when {
                        lensFailure.target is Response -> lensFailure.raiseError()
                        lensFailure.target is RequestContext -> lensFailure.raiseError()
                        lensFailure.overall() == Failure.Type.Unsupported -> IO.just(Response(UNSUPPORTED_MEDIA_TYPE))
                        else -> failResponseFn(lensFailure)
                    }
                }, IO.Companion::just)
            }
        }
    }

    fun <ERR1, ERR2> CatchLensFailure(extract: (ERR1) -> Either<ERR2, LensFailure>, failResponseFn: (LensFailure) -> IO<Response> = {
        IO.just(Response(BAD_REQUEST.description(it.failures.joinToString("; "))))
    }) = object : PartialErrorHandler<ERR1, ERR2> {
        override fun invoke(next: PartialHttpHandler<ERR1>): PartialHttpHandler<ERR2> = { request ->
            next(request).then { result ->
                result.swap()
                        .traverse(Either.applicative(), extract).fix()
                        .traverse(IO.applicative()) {
                            it.traverse(IO.applicative()) { lensFailure: LensFailure ->
                                when {
                                    lensFailure.target is Response -> lensFailure.raiseError()
                                    lensFailure.target is RequestContext -> lensFailure.raiseError()
                                    lensFailure.overall() == Failure.Type.Unsupported -> IO.just(Response(UNSUPPORTED_MEDIA_TYPE))
                                    else -> failResponseFn(lensFailure)
                                }
                            }.fix()
                            .map(Lens.codiagonal<Response>()::get)
                        }.fix()
            }
        }
    }

    /**
     * Last gasp filter which catches all exceptions and returns a formatted Internal Server Error.
     */
    object CatchAll {
        operator fun invoke(errorStatus: Status = INTERNAL_SERVER_ERROR): Filter = Filter { next ->
            {
                next(it).catch { e ->
                    val body = StringWriter().also { sw ->
                        e.printStackTrace(PrintWriter(sw))
                    }.toString()

                    IO.just(Response(errorStatus).body(body))
                }
            }
        }
    }

    /**
     * Copy headers from the incoming request to the outbound response.
     */
    object CopyHeaders {
        operator fun invoke(vararg headers: String): Filter = Filter { next ->
            { request ->
                next(request).map { response ->
                    headers.fold(response) { memo, name ->
                        request.header(name)?.let { memo.header(name, it) } ?: memo
                    }
                }
            }
        }
    }

    /**
     * Basic GZip and Gunzip support of Request/Response. Does not currently support GZipping streams.
     * Only Gunzips requests which contain "transfer-encoding" header containing 'gzip'
     * Only Gzips responses when request contains "accept-encoding" header containing 'gzip'.
     */
    object GZip {
        operator fun invoke(): Filter = RequestFilters.GunZip().then(ResponseFilters.GZip())
    }

    /**
     * Initialise a RequestContext for each request which passes through the Filter stack,
     */
    object InitialiseRequestContext {
        operator fun invoke(contexts: Store<RequestContext>): Filter = Filter { next ->
            {
                val context = RequestContext()
                try {
                    next(contexts.inject(context, it))
                } finally {
                    contexts.remove(context)
                }
            }
        }
    }

    /**
     * Sets the Content Type response header on the Response.
     */
    object SetContentType {
        operator fun invoke(contentType: ContentType): Filter = Filter { next ->
            {
                next(it).map { response -> response.with(Header.Common.CONTENT_TYPE of contentType) }
            }
        }
    }

    /**
     * Intercepts responses and replaces the contents with contents of the statically loaded resource.
     * By default, this Filter replaces the contents of unsuccessful requests with the contents of a file named
     * after the status code.
     */
    object ReplaceResponseContentsWithStaticFile {
        operator fun invoke(loader: ResourceLoader = Classpath(),
                            toResourceName: (Response) -> String? = { if (it.status.successful) null else it.status.code.toString() }
        ): Filter = Filter { next ->
            handleHttp { handle ->
                handle { request ->
                    val response = next(request).bind()
                    toResourceName(response)
                            ?.let {
                                response.body(loader.load(it).bind()?.readText() ?: "")
                            } ?: response
                }
            }
        }
    }
}