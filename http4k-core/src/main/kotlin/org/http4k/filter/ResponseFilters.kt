package org.http4k.filter

import arrow.core.constant
import arrow.effects.IO
import org.http4k.core.Filter
import org.http4k.core.HttpTransaction
import org.http4k.core.Response
import org.http4k.core.arrow.then
import org.http4k.core.handleHttp
import java.time.Clock
import java.time.Duration
import java.time.Duration.between

object ResponseFilters {

    /**
     * Intercept the response after it is sent to the next service.
     */
    object Tap {
        operator fun invoke(fn: (Response) -> IO<Unit>) = Filter { next ->
            { request ->
                next(request).then {
                    fn(it).map(constant(it))
                }
            }
        }
    }

    /**
     * General reporting Filter for an ReportHttpTransaction. Pass an optional HttpTransactionLabeller to
     * create custom labels.
     * This is useful for logging metrics. Note that the passed function blocks the response from completing.
     */
    object ReportHttpTransaction {
        operator fun invoke(clock: IO<Clock> = IO { Clock.systemUTC() }, transactionLabeller: HttpTransactionLabeller = IO.Companion::just, recordFn: (HttpTransaction) -> IO<Unit>): Filter = Filter { next ->
            handleHttp { handle ->
                handle { request ->
                    val start = clock.flatMap { IO { it.instant() } }.bind()
                    val response = next(request).bind()
                    val end = clock.flatMap { IO { it.instant() } }.bind()
                    val transaction = transactionLabeller(HttpTransaction(request, response, between(start, end))).bind()
                    recordFn(transaction).bind()
                    response
                }
            }
        }
    }

    /**
     * Report the latency on a particular route to a callback function.
     * This is useful for logging metrics. Note that the passed function blocks the response from completing.
     */
    object ReportRouteLatency {
        operator fun invoke(clock: IO<Clock> = IO { Clock.systemUTC() }, recordFn: (String, Duration) -> IO<Unit>): Filter =
                ReportHttpTransaction(clock) { tx ->
                    recordFn("${tx.request.method}.${tx.routingGroup.replace('.', '_').replace(':', '.').replace('/', '_')}" +
                            ".${tx.response.status.code / 100}xx" +
                            ".${tx.response.status.code}", tx.duration)
                }
    }

    /**
     * Basic GZipping of Response. Does not currently support GZipping streams
     */
    object GZip {
        operator fun invoke() = Filter { next ->
            {
                val originalResponse = next(it)
                if ((it.header("accept-encoding") ?: "").contains("gzip", true)) {
                    originalResponse.flatMap { response ->
                        response.body.gzipped().map { gZipped ->
                            response.body(gZipped).replaceHeader("Content-Encoding", "gzip")
                        }
                    }
                } else originalResponse
            }
        }
    }

    /**
     * Basic UnGZipping of Response. Does not currently support UnGZipping streams
     */
    object GUnZip {
        operator fun invoke() = Filter { next ->
            {
                next(it).flatMap { response ->
                    response.header("content-encoding")
                            ?.takeIf { it.contains("gzip") }
                            ?.let { response.body.gunzipped().map { gUnzipped -> response.body(gUnzipped).removeHeader("content-encoding") } }
                            ?: IO.just(response)
                }
            }
        }
    }
}


typealias HttpTransactionLabeller = (HttpTransaction) -> IO<HttpTransaction>
