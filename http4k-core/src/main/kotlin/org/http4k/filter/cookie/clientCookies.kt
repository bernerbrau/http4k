package org.http4k.filter.cookie

import arrow.core.constant
import arrow.data.k
import arrow.effects.IO
import arrow.effects.applicative
import arrow.effects.fix
import org.http4k.core.cookie.Cookie
import org.http4k.core.arrow.ignoreResult
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

data class LocalCookie(val cookie: Cookie, private val created: LocalDateTime) {
    fun isExpired(now: LocalDateTime) =
            cookie.maxAge?.let { maxAge ->
                Duration.between(created, now).seconds >= maxAge
            }
                    ?: cookie.expires?.let { expires -> Duration.between(created, now).seconds > Duration.between(created, expires).seconds } == true
}

interface CookieStorage {
    fun store(cookies: List<LocalCookie>): IO<Unit>
    fun remove(name: String): IO<Unit>
    fun retrieve(): IO<List<LocalCookie>>
}

class BasicCookieStorage : CookieStorage {
    private val storage = ConcurrentHashMap<String, LocalCookie>()

    override fun store(cookies: List<LocalCookie>) =
            cookies
                    .k()
                    .traverse(IO.applicative()) {
                        IO { storage[it.cookie.name] = it }
                    }
                    .fix()
                    .ignoreResult()


    override fun retrieve(): IO<List<LocalCookie>> = IO { storage.values.toList() }

    override fun remove(name: String): IO<Unit> = IO {
        storage.remove(name)
    }.ignoreResult()
}