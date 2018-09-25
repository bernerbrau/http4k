package org.http4k.core.arrow

import arrow.effects.IO
import arrow.effects.IOOf
import arrow.effects.handleErrorWith

fun <A, B> IO<A>.then(f: (A) -> IOOf<B>): IO<B> = flatMap(f)

fun <A> IO<A>.ignoreResult(): IO<Unit> = then { IO.unit }

fun <A> IO<A>.catch(handle: (Throwable) -> IOOf<A>): IO<A> = handleErrorWith(handle)