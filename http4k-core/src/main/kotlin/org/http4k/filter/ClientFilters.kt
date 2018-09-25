package org.http4k.filter

import arrow.core.constant
import arrow.data.k
import arrow.effects.*
import arrow.typeclasses.binding
import org.http4k.base64Encode
import org.http4k.core.Credentials
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Uri
import org.http4k.core.arrow.ignoreResult
import org.http4k.core.arrow.then
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.cookies
import org.http4k.core.then
import org.http4k.filter.ClientFilters.FollowRedirects.location
import org.http4k.filter.ZipkinTraces.Companion.THREAD_LOCAL
import org.http4k.filter.cookie.BasicCookieStorage
import org.http4k.filter.cookie.CookieStorage
import org.http4k.filter.cookie.LocalCookie
import java.time.Clock
import java.time.LocalDateTime

object ClientFilters {

    /**
     * Adds Zipkin request tracing headers to the outbound request. (traceid, spanid, parentspanid)
     */
    object RequestTracing {
        operator fun invoke(
                startReportFn: (Request, ZipkinTraces) -> IO<Unit> = { _, _ -> IO.unit },
                endReportFn: (Request, Response, ZipkinTraces) -> IO<Unit> = { _, _, _ -> IO.unit }): Filter = Filter { next ->
            { request ->
                IO { THREAD_LOCAL.get() }.flatMap {
                    ForIO extensions {
                        binding {
                            val updated = it.copy(parentSpanId = it.spanId, spanId = TraceId.new())
                            startReportFn(request, updated).bind()
                            val response = next(ZipkinTraces(updated, request)).bind()
                            endReportFn(request, response, updated).bind()
                            response
                        }.fix()
                    }
                }
            }
        }
    }

    /**
     * Sets the host on an outbound request. This is useful to separate configuration of remote endpoints
     * from the logic required to construct the rest of the request.
     */
    object SetHostFrom {
        operator fun invoke(uri: Uri): Filter = Filter { next ->
            {
                next(it.uri(it.uri.scheme(uri.scheme).host(uri.host).port(uri.port))
                        .replaceHeader("Host", "${uri.host}${uri.port?.let { port -> ":$port" } ?: ""}"))
            }
        }
    }

    object BasicAuth {
        operator fun invoke(provider: () -> Credentials): Filter = Filter { next ->
            { next(it.header("Authorization", "Basic ${provider().base64Encoded()}")) }
        }

        operator fun invoke(user: String, password: String): Filter = BasicAuth(Credentials(user, password))
        operator fun invoke(credentials: Credentials): Filter = BasicAuth { credentials }

        private fun Credentials.base64Encoded(): String = "$user:$password".base64Encode()
    }

    object BearerAuth {
        operator fun invoke(provider: () -> String): Filter = Filter { next ->
            { next(it.header("Authorization", "Bearer ${provider()}")) }
        }

        operator fun invoke(token: String): Filter = BearerAuth { token }
    }

    object FollowRedirects {
        operator fun invoke(): Filter = Filter { next -> { makeRequest(next, it) } }

        private fun makeRequest(next: HttpHandler, request: Request, attempt: Int = 1): IO<Response> =
            next(request).then { response ->
                if (response.isRedirection()) {
                    if (attempt == 10) throw IllegalStateException("Too many redirections")
                    response.assureBodyIsConsumed()
                            .then {
                                makeRequest(next, request.toNewLocation(response.location()), attempt + 1)
                            }
                } else IO.just(response)
            }

        private fun Request.toNewLocation(location: String) = ensureValidMethodForRedirect().uri(newLocation(location))

        private fun Response.location() = header("location")?.replace(";\\s*charset=.*$".toRegex(), "").orEmpty()

        private fun Response.assureBodyIsConsumed(): IO<Unit> = IO { body.close() }

        private fun Response.isRedirection(): Boolean = status.redirection && header("location")?.let(String::isNotBlank) == true

        private fun Request.ensureValidMethodForRedirect(): Request =
            if (method == Method.GET || method == Method.HEAD) this else method(Method.GET)

        private fun Request.newLocation(location: String): Uri =
            Uri.of(location).run {
                if (host.isBlank()) authority(uri.authority).scheme(uri.scheme) else this
            }
    }

    object Cookies {
        operator fun invoke(clock: IO<Clock> = IO { Clock.systemDefaultZone() },
                            storage: CookieStorage = BasicCookieStorage()): Filter = Filter { next ->
            { request ->
                ForIO extensions {
                    binding {
                        val now = clock.flatMap { it.now() }.bind()
                        removeExpired(now, storage).bind()
                        val response = request.withLocalCookies(storage).then(next).bind()
                        storage.store(response.cookies().map { LocalCookie(it, now) }).bind()
                        response
                    }.fix()
                }
            }
        }

        private fun Request.withLocalCookies(storage: CookieStorage): IO<Request> =
                storage.retrieve()
                    .map { cookies ->
                        cookies.map { it.cookie }
                            .fold(this) { r, cookie -> r.cookie(cookie.name, cookie.value) }
                    }


        private fun removeExpired(now: LocalDateTime, storage: CookieStorage): IO<Unit> =
                storage
                        .retrieve()
                        .then {
                            localCookies ->
                                localCookies
                                        .filter {
                                            it.isExpired(now)
                                        }
                                        .k().traverse(IO.applicative()) {
                                            storage.remove(it.cookie.name)
                                        }
                                        .fix()
                                        .ignoreResult()
                        }

        private fun Clock.now(): IO<LocalDateTime> = IO { LocalDateTime.ofInstant(instant(), zone) }
    }

    /**
     * Basic GZip and Gunzip support of Request/Response. Does not currently support GZipping streams.
     * Only Gunzip responses when the response contains "transfer-encoding" header containing 'gzip'
     */
    object GZip {
        operator fun invoke(): Filter = RequestFilters.GZip().then(ResponseFilters.GunZip())
    }

}
