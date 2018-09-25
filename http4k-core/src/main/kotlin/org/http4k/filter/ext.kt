package org.http4k.filter

import arrow.effects.IO
import org.http4k.core.Body
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


fun Body.gzipped(): IO<Body> = if (payload.array().isEmpty()) IO.just(Body.EMPTY)
else IO { payload.array() }.map {
    array ->
        ByteArrayOutputStream().run {
            GZIPOutputStream(this).use { it.write(array) }
            Body(ByteBuffer.wrap(toByteArray()))
        }
}


fun Body.gunzipped(): IO<Body> = if (payload.array().isEmpty()) IO.just(Body.EMPTY)
else IO { payload.array() }.map { array ->
    ByteArrayOutputStream().use {
        GZIPInputStream(ByteArrayInputStream(array)).copyTo(it)
        Body(ByteBuffer.wrap(it.toByteArray()))
    }
}