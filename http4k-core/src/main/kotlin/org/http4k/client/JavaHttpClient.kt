package org.http4k.client

import arrow.effects.IO
import org.http4k.core.*
import org.http4k.core.Status.Companion.CONNECTION_REFUSED
import org.http4k.core.Status.Companion.UNKNOWN_HOST
import org.http4k.core.arrow.catch
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer

class JavaHttpClient : HttpHandler {
    private val handle =
            handleHttp { handle ->
                handle { request ->
                    val connection = (URL(request.uri.toString()).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        requestMethod = request.method.name
                        doOutput = true
                        doInput = true
                        request.headers.forEach {
                            addRequestProperty(it.first, it.second)
                        }
                        request.body.apply {
                            if (this != Body.EMPTY) {
                                val content = if (stream.available() == 0) payload.array().inputStream() else stream
                                content.copyTo(outputStream)
                            }
                        }
                    }

                    val status = Status(connection.responseCode, connection.responseMessage.orEmpty())
                    val baseResponse = Response(status).body(connection.body(status))
                    connection.headerFields
                            .filterKeys { it != null } // because response status line comes as a header with null key (*facepalm*)
                            .map { header -> header.value.map { header.key to it } }
                            .flatten()
                            .fold(baseResponse) { acc, next -> acc.header(next.first, next.second) }
                }.catch { e ->
                    when(e) {
                        is UnknownHostException -> IO.just(Response(UNKNOWN_HOST.description("Client Error: caused by ${e.localizedMessage}")))
                        is ConnectException -> IO.just(Response(CONNECTION_REFUSED.description("Client Error: caused by ${e.localizedMessage}")))
                        else -> raiseError(e)
                    }
                }
            }

    override fun invoke(request: Request): IO<Response> = handle(request)

    // Because HttpURLConnection closes the stream if a new request is made, we are forced to consume it straight away
    private fun HttpURLConnection.body(status: Status) =
            resolveStream(status).readBytes().let { ByteBuffer.wrap(it) }.let { Body(it) }

    private fun HttpURLConnection.resolveStream(status: Status) =
            when {
                status.serverError || status.clientError -> errorStream
                else -> inputStream
            }
}