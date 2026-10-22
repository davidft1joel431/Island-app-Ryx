package com.dav.isla

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Copia al portapapeles sin depender de AndroidX.
 *
 * El texto va como texto normal. Las imágenes/stickers se publican primero como una imagen normal en
 * Pictures/Ryx y se copia su URI: así se pueden pegar en WhatsApp, Telegram o cualquier app que acepte
 * imágenes, sin capturas de pantalla ni permisos especiales. Base64 y binario son internos y no se copian.
 */
object Clip {
    fun text(c: Context, label: String, value: String): Boolean = try {
        val cb = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, value))
        true
    } catch (t: Throwable) { false }

    fun image(c: Context, file: File, kind: String): Boolean {
        if (!file.isFile) return false
        // El tipo real manda: una captura puede ser PNG aunque el aviso diga «Imagen».
        val mime = when {
            isPng(file) -> "image/png"
            isGif(file) -> "image/gif"
            isWebp(file) -> "image/webp"
            isJpeg(file) -> "image/jpeg"
            kind.contains("Sticker", ignoreCase = true) -> "image/png"
            else -> "image/jpeg"
        }
        return publish(c, mime) { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun image(c: Context, bmp: Bitmap, kind: String): Boolean {
        val sticker = kind.contains("Sticker", ignoreCase = true)
        return publish(c, if (sticker) "image/png" else "image/jpeg") { out ->
            if (sticker) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
    }

    private fun head(f: File, n: Int = 12): ByteArray = try {
        f.inputStream().use { input ->
            val b = ByteArray(n)
            val r = input.read(b)
            if (r <= 0) ByteArray(0) else b.copyOf(r)
        }
    } catch (t: Throwable) { ByteArray(0) }

    private fun isPng(f: File): Boolean {
        val h = head(f, 8)
        return h.size >= 8 && (h[0].toInt() and 0xFF) == 0x89 && h[1].toInt() == 0x50 &&
            h[2].toInt() == 0x4E && h[3].toInt() == 0x47
    }

    private fun isJpeg(f: File): Boolean {
        val h = head(f, 3)
        return h.size >= 3 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xFF) == 0xD8 &&
            (h[2].toInt() and 0xFF) == 0xFF
    }

    private fun isGif(f: File): Boolean {
        val h = head(f, 4)
        return h.size >= 4 && h[0].toInt() == 0x47 && h[1].toInt() == 0x49 &&
            h[2].toInt() == 0x46 && h[3].toInt() == 0x38
    }

    private fun isWebp(f: File): Boolean {
        val h = head(f, 12)
        return h.size >= 12 && h[0].toInt() == 0x52 && h[1].toInt() == 0x49 &&
            h[2].toInt() == 0x46 && h[3].toInt() == 0x46 &&
            h[8].toInt() == 0x57 && h[9].toInt() == 0x45 && h[10].toInt() == 0x42 && h[11].toInt() == 0x50
    }

    /**
     * Publica la imagen en la galería y deja su URI en el portapapeles.
     * Se usa MediaStore para que otras apps puedan leerla sin compartir permisos privados.
     */
    private fun publish(c: Context, mime: String, write: (OutputStream) -> Unit): Boolean {
        var uri: android.net.Uri? = null
        return try {
            val ext = when (mime) {
                "image/png" -> ".png"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }
            val name = "ryx_" + System.currentTimeMillis() + ext
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Ryx")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            uri = target
            val out = c.contentResolver.openOutputStream(target)
            if (out == null) {
                // No dejar una imagen «pendiente» invisible en la galería si no se pudo escribir.
                c.contentResolver.delete(target, null, null)
                uri = null
                return false
            }
            out.use(write)
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                c.contentResolver.update(uri, values, null, null)
            }
            val cb = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newUri(c.contentResolver, "Ryx", uri))
            true
        } catch (t: Throwable) {
            try { if (uri != null) c.contentResolver.delete(uri, null, null) } catch (e: Throwable) {}
            false
        }
    }
}
