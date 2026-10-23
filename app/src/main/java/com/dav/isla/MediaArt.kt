package com.dav.isla

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * Carga imágenes conservando la animación.
 *
 * ImageDecoder devuelve un AnimatedImageDrawable para GIF/WebP animados y un bitmap para el resto.
 * BitmapFactory se usa solo como respaldo: antes se decodificaba siempre con él y por eso los stickers
 * animados y los GIF aparecían congelados en el primer fotograma.
 *
 * Las imágenes estáticas se guardan en una caché de memoria pequeña. Así, al subir y bajar por el
 * Historial o al volver a abrir una foto, no se decodifica otra vez el mismo archivo. Las animaciones
 * NO se comparten: cada vista recibe su propia copia para poder arrancarla y pararla sin afectar a otra.
 */
object MediaArt {
    /** 24 MB: suficiente para varias miniaturas sin retener fotos enormes durante toda la sesión. */
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    fun fromFile(c: Context, file: File, maxPx: Int = 512): Drawable? {
        if (!file.isFile) return null
        val key = cacheKey(file, maxPx)
        cache.get(key)?.let { return wrap(c, it) }
        return try {
            val d = decode(ImageDecoder.createSource(file), maxPx)
            if (d is BitmapDrawable) d.bitmap?.let { cache.put(key, it) }
            d
        } catch (t: Throwable) {
            bitmapDrawable(c, file, maxPx)?.also { d ->
                (d as? BitmapDrawable)?.bitmap?.let { cache.put(key, it) }
            }
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

    /**
     * Detecta por los bytes si un archivo es un GIF o un WebP animado. Se usa para poner la etiqueta
     * «GIF»/«ANIM» en el Historial incluso en entradas antiguas que solo decían «Imagen».
     */
    fun isAnimatedFile(file: File): Boolean = try {
        if (!file.isFile) false else {
            val h = ByteArray(32)
            val n = file.inputStream().use { it.read(h) }
            if (n >= 6 && h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == '8'.code.toByte()) true
            else if (n >= 21 && h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() &&
                h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() &&
                h[12] == 'V'.code.toByte() && h[13] == 'P'.code.toByte() &&
                h[14] == '8'.code.toByte() && h[15] == 'X'.code.toByte())
                (h[20].toInt() and 0x02) != 0
            else false
        }
    } catch (t: Throwable) { false }

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

    private fun cacheKey(file: File, maxPx: Int): String =
        "${file.absolutePath}|${file.lastModified()}|${file.length()}|$maxPx"

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
            // Software: los bitmaps con transparencia y los recortes redondeados se comportan igual
            // en todas las ROMs; la caché ya limita cuánta memoria se retiene.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        // La animación se inicia en el hilo de UI, cuando el drawable ya tiene una vista que lo dibuja.
        return d
    }

    /** Envuelve un bitmap sin que la densidad de la pantalla lo agrande: conserva sus píxeles reales. */
    private fun wrap(c: Context, b: Bitmap): BitmapDrawable =
        BitmapDrawable(c.resources, b).apply { setTargetDensity(b.density) }

    private fun bitmapDrawable(c: Context, file: File, maxPx: Int): Drawable? = try {
        val b = decodeSampledFile(file, maxPx)
        b?.let { wrap(c, it) }
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
                ?.let { wrap(c, it) }
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
