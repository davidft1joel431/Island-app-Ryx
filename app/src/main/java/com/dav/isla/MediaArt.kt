package com.dav.isla

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

/**
 * Carga imágenes conservando la animación.
 *
 * ImageDecoder devuelve un AnimatedImageDrawable para GIF/WebP animados y un bitmap para el resto.
 * BitmapFactory se usa solo como respaldo: antes se decodificaba siempre con él y por eso los stickers
 * animados y los GIF aparecían congelados en el primer fotograma.
 */
object MediaArt {
    fun fromFile(c: Context, file: File, maxPx: Int = 512): Drawable? {
        if (!file.isFile) return null
        return try {
            decode(ImageDecoder.createSource(file), maxPx)
        } catch (t: Throwable) {
            bitmapDrawable(c, file, maxPx)
        }
    }

    fun fromUri(c: Context, uri: String, maxPx: Int = 512): Drawable? {
        return try {
            decode(ImageDecoder.createSource(c.contentResolver, Uri.parse(uri)), maxPx)
        } catch (t: Throwable) {
            try {
                c.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    bitmapDrawable(c, input, maxPx)
                }
            } catch (t2: Throwable) { null }
        }
    }

    fun isAnimated(d: Drawable?): Boolean = d is AnimatedImageDrawable

    fun start(d: Drawable?) {
        if (d is AnimatedImageDrawable) {
            try {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (!d.isRunning) d.start()
            } catch (t: Throwable) {}
        }
    }

    fun stop(d: Drawable?) {
        if (d is AnimatedImageDrawable) {
            try { d.stop() } catch (t: Throwable) {}
        }
    }

    private fun decode(source: ImageDecoder.Source, maxPx: Int): Drawable? {
        val d = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val m = maxOf(w, h)
            if (m > maxPx && m > 0) {
                val f = maxPx.toFloat() / m
                decoder.setTargetSize(
                    (w * f).toInt().coerceAtLeast(1),
                    (h * f).toInt().coerceAtLeast(1)
                )
            }
            decoder.isMutableRequired = false
        }
        if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        // La animación se inicia en el hilo de UI, cuando el drawable ya tiene una vista que lo dibuja.
        return d
    }

    private fun bitmapDrawable(c: Context, file: File, maxPx: Int): Drawable? = try {
        val b = decodeSampledFile(file, maxPx)
        b?.let { BitmapDrawable(c.resources, it) }
    } catch (t: Throwable) { null }

    private fun bitmapDrawable(c: Context, input: java.io.InputStream, maxPx: Int): Drawable? = try {
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?.let { BitmapDrawable(c.resources, it) }
        }
    } catch (t: Throwable) { null }

    private fun decodeSampledFile(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
