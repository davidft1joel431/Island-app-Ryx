package com.dav.isla

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de ajustes + historial (con imágenes) en un .zip.
 * Se guarda en una carpeta que elige el usuario (por ejemplo, Google Drive) mediante el selector de Android,
 * así sobrevive a desinstalar la app. No hace falta ninguna clave ni cuenta dentro de Ryx.
 */
object Backup {
    private const val NAME = "ryx_respaldo.zip"
    private var last = 0L
    @Volatile private var running = false

    fun configured(c: Context) = Prefs.cloudUri(c).isNotEmpty()

    /** Carpeta PROPIA de la app (dentro de sus datos): aquí se guardan solas las copias, sin pedir nada. */
    fun dir(c: Context): File = File(c.filesDir, "copias").also { it.mkdirs() }

    /** Suma de comprobación sencilla de «lo que hay que guardar»: ajustes + historial + nº de imágenes. */
    fun stamp(c: Context): String {
        val h = Prefs.histRaw(c)
        val n = Prefs.histDir(c).listFiles()?.size ?: 0
        var x = 17L
        x = x * 31 + Prefs.exportAll(c).hashCode()
        x = x * 31 + h.hashCode()
        x = x * 31 + n
        return x.toString(16)
    }

    /**
     * Escribe una copia DENTRO de la app, rotando: conserva las [Prefs.backupKeep] más recientes.
     * Devuelve "" si salió bien o el motivo del fallo. Llamar fuera del hilo principal.
     */
    fun writeLocal(c: Context): String {
        return try {
            val d = dir(c)
            val f = File(d, "ryx_" + System.currentTimeMillis() + ".zip")
            f.outputStream().use { o -> write(c, o) }
            val all = d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
            val keep = Prefs.backupKeep(c).coerceIn(1, 50)
            all.drop(keep).forEach { it.delete() }
            Prefs.setBackupLast(c, System.currentTimeMillis())
            Prefs.setBackupStamp(c, stamp(c))
            ""
        } catch (e: Exception) {
            "No pude guardar la copia: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Copia automática en la carpeta propia: solo si algo cambió (ajustes/historial/imágenes) y como mucho
     * cada [Prefs.backupEveryMin] minutos. Es lo que evita tener que pulsar nada.
     */
    fun autoLocal(c: Context, force: Boolean = false) {
        if (!Prefs.backupAuto(c) || running) return
        val now = System.currentTimeMillis()
        when {
            force -> {}
            stamp(c) == Prefs.backupStamp(c) -> return                       // nada nuevo
            now - Prefs.backupLast(c) < Prefs.backupEveryMin(c) * 60_000L -> return
        }
        running = true
        val app = c.applicationContext
        Thread { try { writeLocal(app) } finally { running = false } }.start()
    }

    fun write(c: Context, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("ajustes.json")); z.write(Prefs.exportAll(c).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("historial.json")); z.write(Prefs.histRaw(c).toByteArray()); z.closeEntry()
            Prefs.histDir(c).listFiles()?.forEach { f ->
                if (f.isFile) { z.putNextEntry(ZipEntry("img/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
        }
    }

    /** Une el historial guardado con el de la copia (sin duplicados) y restaura ajustes e imágenes. */
    fun restore(c: Context, input: InputStream): String {
        var settings = false; var items = 0; var imgs = 0
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                when {
                    n == "ajustes.json" -> settings = Prefs.importAll(c, String(z.readBytes()))
                    n == "historial.json" -> items = mergeHistory(c, String(z.readBytes()))
                    n.startsWith("img/") -> {
                        File(Prefs.histDir(c), File(n).name).outputStream().use { o -> z.copyTo(o) }; imgs++
                    }
                }
                e = z.nextEntry
            }
        }
        return "Ajustes: ${if (settings) "restaurados" else "no encontrados"}\nNotificaciones en el historial: $items\nImágenes: $imgs"
    }

    private fun mergeHistory(c: Context, incoming: String): Int {
        val all = ArrayList<JSONObject>(); val seen = HashSet<String>()
        fun add(a: JSONArray) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (seen.add("${o.optString("p")}|${o.optLong("ts")}|${o.optString("t")}|${o.optString("x")}")) all.add(o)
            }
        }
        try { add(JSONArray(Prefs.histRaw(c))) } catch (e: Exception) {}
        try { add(JSONArray(incoming)) } catch (e: Exception) {}
        all.sortByDescending { it.optLong("ts") }
        val out = JSONArray()
        all.take(600).forEach { out.put(it) }
        Prefs.setHistRaw(c, out.toString())
        return out.length()
    }

    /** Escribe la copia en la carpeta elegida. Devuelve "" si salió bien, o el motivo del fallo. Llamar fuera del hilo principal. */
    fun upload(c: Context): String {
        val raw = Prefs.cloudUri(c)
        if (raw.isEmpty()) return "Todavía no elegiste una carpeta."
        return try {
            val tree = Uri.parse(raw)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            var found: Uri? = null
            val cur = c.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            if (cur != null) cur.use {
                while (it.moveToNext()) {
                    if (it.getString(1) == NAME) { found = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)); break }
                }
            }
            val target: Uri = found ?: DocumentsContract.createDocument(c.contentResolver, dir, "application/zip", NAME)
                ?: return "No se pudo crear el archivo en esa carpeta."
            val out = try { c.contentResolver.openOutputStream(target, "wt") } catch (e: Exception) { c.contentResolver.openOutputStream(target, "w") }
                ?: return "No se pudo escribir en esa carpeta."
            write(c, out)
            Prefs.setCloudLast(c, System.currentTimeMillis())
            ""
        } catch (e: SecurityException) {
            "Android quitó el permiso de la carpeta. Vuelve a elegirla."
        } catch (e: Exception) {
            "Falló la copia: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Copia automática: como mucho una cada 10 minutos, y solo si hay carpeta elegida. */
    fun maybeUpload(c: Context, minGapMs: Long = 10 * 60_000L) {
        if (!configured(c) || running) return
        val now = System.currentTimeMillis()
        if (now - last < minGapMs) return
        last = now; running = true
        val app = c.applicationContext
        Thread { try { upload(app) } finally { running = false } }.start()
    }
}
