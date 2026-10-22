package com.dav.isla

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * Base de cada mensaje.
 *
 * Además del texto visible, cada aviso guarda dos copias recuperables:
 *  - un archivo binario con los bytes UTF-8 del mensaje completo (.bin);
 *  - un archivo de texto con su código Base64 (.b64).
 *
 * La idea es que el Historial y la isla nunca dependan de que la vista haya podido cargar el texto:
 * si el texto normal viene vacío, cortado o corrupto, se reconstruye desde el binario o desde Base64.
 * Las imágenes y stickers se guardan como archivo binario; su Base64 se calcula solo cuando hace falta
 * para una vista o una exportación. Nada de esto se ofrece como botón: es recuperación interna.
 *
 * Los archivos viven en la misma carpeta privada que las imágenes del Historial, así que entran en la
 * copia de seguridad y se borran junto con la entrada cuando el historial rota.
 */
object MsgBase {
    /**
     * Los mensajes cortos llevan su Base64 también dentro del JSON del historial. Los largos lo dejan
     * solo en el archivo .b64 para no inflar SharedPreferences (que se lee entero en cada arranque).
     */
    private const val MAX_INLINE_B64 = 4_000

    /** Evita releer los mismos .bin/.b64 al dibujar el Historial y al copiar desde la isla. */
    private val fullCache = android.util.LruCache<String, String>(48)

    data class Stored(val b64: String, val bin: String, val b64f: String)

    private fun dir(c: Context): File = Prefs.histDir(c)

    /** Escribe las dos copias del texto y devuelve sus nombres. Nunca lanza: si falla, queda el texto normal. */
    fun write(c: Context, stamp: Long, text: String): Stored {
        if (text.isEmpty()) return Stored("", "", "")
        return try {
            val id = "${stamp}_${(Math.random() * 1_000_000).toInt()}"
            val bytes = text.toByteArray(Charsets.UTF_8)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val binName = "m_${id}.bin"
            val b64Name = "m_${id}.b64"
            var bin = ""
            var b64f = ""
            try {
                File(dir(c), binName).writeBytes(bytes)
                bin = binName
            } catch (t: Throwable) {}
            try {
                File(dir(c), b64Name).writeText(b64, Charsets.UTF_8)
                b64f = b64Name
            } catch (t: Throwable) {}
            Stored(if (b64.length <= MAX_INLINE_B64) b64 else "", bin, b64f)
        } catch (t: Throwable) {
            Stored("", "", "")
        }
    }

    /** Texto completo: se queda con la versión más larga de todas las copias disponibles. */
    fun fullText(c: Context, h: Prefs.HistItem): String {
        val key = "${h.ts}|${h.pkg}|${h.text.length}:${h.text.hashCode()}|" +
            "${h.b64.length}:${h.b64.hashCode()}|${h.bin}|${h.b64f}"
        fullCache.get(key)?.let { return it }
        val candidates = ArrayList<String>(4)
        if (h.text.isNotEmpty()) candidates.add(h.text)
        if (h.b64.isNotEmpty()) decode(h.b64)?.let { candidates.add(it) }
        if (h.b64f.isNotEmpty()) {
            readText(File(dir(c), h.b64f))?.let { raw -> decode(raw)?.let { candidates.add(it) } }
        }
        if (h.bin.isNotEmpty()) readText(File(dir(c), h.bin))?.let { candidates.add(it) }
        val out = candidates.maxByOrNull { it.length } ?: ""
        // Los «testamentos» gigantes no se retienen en memoria; se releen solo cuando hacen falta.
        if (out.length <= 32_000) fullCache.put(key, out)
        return out
    }

    /** Archivo de imagen o sticker de la entrada, o null si no existe. */
    fun mediaFile(c: Context, h: Prefs.HistItem): File? {
        if (h.img.isEmpty()) return null
        return try {
            val f = File(dir(c), h.img)
            if (f.isFile && f.length() > 0L) f else null
        } catch (t: Throwable) { null }
    }

    /** Base64 interno de la imagen o sticker (se calcula desde el archivo binario; no se muestra al usuario). */
    fun mediaBase64(c: Context, h: Prefs.HistItem): String? = try {
        mediaFile(c, h)?.readBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    } catch (t: Throwable) { null }

    /**
     * Busca la entrada del historial que corresponde a una tarjeta de la isla.
     * Primero por app/título/texto y, si el texto venía cortado, por la entrada más reciente de esa app.
     */
    fun findHist(c: Context, pkg: String, title: String, hint: String, userId: Int = -1): Prefs.HistItem? {
        val all = try { Prefs.histAll(c) } catch (t: Throwable) { emptyList() }
        if (all.isEmpty()) return null
        val clean = hint.trim()
        // La clave del historial puede ser «paquete@espacio» para un clon. Se busca primero esa clave exacta
        // y, si no aparece, se acepta cualquier espacio del mismo paquete.
        val want = if (userId >= 0) Clones.key(pkg, userId) else pkg
        var same = all.filter { it.pkg == want && (title.isEmpty() || it.title == title) }
        if (same.isEmpty()) same = all.filter {
            Clones.splitKey(it.pkg).first == pkg && (title.isEmpty() || it.title == title)
        }
        // Si el título cambió (un grupo, un nombre editado…), la app sigue siendo la misma: se usa su
        // entrada más reciente para no perder el texto completo.
        if (same.isEmpty()) same = all.filter { Clones.splitKey(it.pkg).first == pkg }
        if (clean.isNotEmpty()) {
            same.firstOrNull { h ->
                h.text == clean ||
                    (h.text.isNotEmpty() && (clean.startsWith(h.text.take(160)) || h.text.startsWith(clean.take(160))))
            }?.let { return it }
        }
        val now = System.currentTimeMillis()
        return same.firstOrNull { now - it.ts < 10 * 60_000L } ?: same.firstOrNull()
    }

    /** Texto completo de una tarjeta: historial/base primero y, si no hay nada, el texto que traía la isla. */
    fun fullTextFor(c: Context, pkg: String, title: String, hint: String, userId: Int = -1): String {
        val h = findHist(c, pkg, title, hint, userId)
        if (h != null) {
            val full = fullText(c, h)
            // Solo se cambia el texto visible si la copia guardada continúa el mensaje actual.
            // Así una tarjeta corta recién llegada no muestra por error un mensaje anterior de la misma app.
            if (full.isNotEmpty() && full.length >= hint.length && related(full, hint)) return full
        }
        return hint
    }

    /** ¿La copia guardada coincide o continúa el texto que mostró el aviso? */
    private fun related(full: String, hint: String): Boolean {
        if (hint.isBlank()) return true
        val clean = hint.trim().trimEnd('…', '.', ' ')
        if (clean.isEmpty()) return true
        val saved = full.trim()
        return saved == clean || saved.startsWith(clean.take(120)) || clean.startsWith(saved.take(120))
    }

    /** Borra los archivos de una entrada (imagen, binario y Base64). */
    fun deleteFiles(c: Context, img: String, bin: String, b64f: String) {
        for (name in arrayOf(img, bin, b64f)) {
            if (name.isEmpty()) continue
            try { File(dir(c), name).delete() } catch (t: Throwable) {}
        }
    }

    private fun readText(f: File): String? = try {
        if (f.isFile) String(f.readBytes(), Charsets.UTF_8) else null
    } catch (t: Throwable) { null }

    private fun decode(raw: String): String? = try {
        val clean = raw.trim()
        if (clean.isEmpty()) null
        else String(Base64.decode(clean, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (t: Throwable) { null }
}
