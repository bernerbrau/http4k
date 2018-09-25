package org.http4k.filter

import arrow.core.constant
import arrow.effects.IO
import arrow.effects.IO.Companion.raiseError
import arrow.effects.handleErrorWith
import org.http4k.core.*
import org.http4k.core.arrow.catch
import org.http4k.core.arrow.then
import java.io.PrintStream

object DebuggingFilters {
    private const val defaultDebugStream = true

    /**
     * Print details of the request before it is sent to the next service.
     */
    object PrintRequest {
        operator fun invoke(out: PrintStream = System.out, debugStream: Boolean = defaultDebugStream): Filter = RequestFilters.Tap { req ->
            IO { out.println(listOf("***** REQUEST: ${req.method}: ${req.uri} *****", req.printable(debugStream)).joinToString("\n")) }
        }
    }

    /**
     * Print details of the response before it is returned.
     */
    object PrintResponse {
        operator fun invoke(out: PrintStream = System.out, debugStream: Boolean = defaultDebugStream): Filter = Filter { next ->
            {
                next(it).then { response ->
                            IO {
                                out.println(listOf("***** RESPONSE ${response.status.code} to ${it.method}: ${it.uri} *****", response.printable(debugStream)).joinToString("\n"))
                            }.map(constant(response))
                        }
                        .catch { e ->
                            IO {
                                out.println("***** RESPONSE FAILED to ${it.method}: ${it.uri}  *****")
                                e.printStackTrace(out)
                            }.then { raiseError<Response>(e) }
                        }
            }
        }
    }

    private fun HttpMessage.printable(debugStream: Boolean) =
            if (debugStream || body is MemoryBody) this else body("<<stream>>")

    /**
     * Print details of a request and it's response.
     */
    object PrintRequestAndResponse {
        operator fun invoke(out: PrintStream = System.out, debugStream: Boolean = defaultDebugStream) = PrintRequest(out, debugStream).then(PrintResponse(out, debugStream))
    }
}