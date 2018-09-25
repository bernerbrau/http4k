package org.http4k.core

import arrow.core.Either
import arrow.effects.*
import arrow.typeclasses.MonadContinuation
import arrow.typeclasses.binding
import org.http4k.routing.RoutingHttpHandler

typealias PartialHttpHandler<ERR> = (Request) -> IO<Either<ERR,Response>>
typealias HttpHandler = (Request) -> IO<Response>

inline fun handleHttp(crossinline ioHandler: IOContext.((suspend MonadContinuation<ForIO, *>.(Request) -> Response) -> IO<Response>) -> IO<Response>): HttpHandler =
    { req ->
        ForIO extensions {
            ioHandler { handle ->
                binding {
                    handle(req)
                }.fix()
            }
        }
    }

interface PartialErrorHandler<ERR1, ERR2> : (PartialHttpHandler<ERR1>) -> PartialHttpHandler<ERR2> {
    companion object {
        operator fun <ERR1, ERR2> invoke(fn: (PartialHttpHandler<ERR1>) -> PartialHttpHandler<ERR2>): PartialErrorHandler<ERR1, ERR2> = object : PartialErrorHandler<ERR1, ERR2> {
            override operator fun invoke(next: PartialHttpHandler<ERR1>): PartialHttpHandler<ERR2> = fn(next)
        }
    }
}

interface ErrorHandler<ERR> : (PartialHttpHandler<ERR>) -> HttpHandler {
    companion object {
        operator fun <ERR> invoke(fn: (PartialHttpHandler<ERR>) -> HttpHandler): ErrorHandler<ERR> = object : ErrorHandler<ERR> {
            override operator fun invoke(next: PartialHttpHandler<ERR>): HttpHandler = fn(next)
        }
    }
}

interface Filter : (HttpHandler) -> HttpHandler {
    companion object {
        operator fun invoke(fn: (HttpHandler) -> HttpHandler): Filter = object : Filter {
            override operator fun invoke(next: HttpHandler): HttpHandler = fn(next)
        }
    }
}

val Filter.Companion.NoOp: Filter get() = Filter { next -> { next(it) } }

fun Filter.then(next: Filter): Filter = Filter { this(next(it)) }

fun Filter.then(next: HttpHandler): HttpHandler = { this(next)(it) }

fun Filter.then(routingHttpHandler: RoutingHttpHandler): RoutingHttpHandler = routingHttpHandler.withFilter(this)