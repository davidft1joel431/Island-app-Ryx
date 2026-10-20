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
    /** Nombre del archivo de la copia en la nube / del respaldo propio. */
    private const val NAME = "ryx_respaldo.zip"
    /** Nombre de la carpeta propia del respaldo, dentro de los datos de la app. */
    const val FOLDER = "ryx respaldo"
    private var last = 0L
    @Volatile private var running = false

    fun configured(c: Context) = Prefs.cloudUri(c).isNotEmpty()

    /**
     * Carpeta PROPIA de la app donde se guardan solas las copias: «ryx respaldo».
     * Antes se llamaba «copias»; si existe la antigua, se conserva para no perder lo ya guardado.
     */
    fun dir(c: Context): File {
        val d = File(c.filesDir, FOLDER)
        if (!d.exists()) {
            d.mkdirs()
            // Migración suave: si venía de la carpeta «copias», se trae lo que hubiera.
            val old = File(c.filesDir, "copias")
            old.listFiles()?.forEach { f -> if (f.isFile) try { f.renameTo(File(d, f.name)) } catch (e: Exception) {} }
        }
        return d
    }

    /** ¿Existe ya algún respaldo guardado en la carpeta propia? */
    fun hasLocal(c: Context): Boolean = dir(c).listFiles()?.any { it.isFile && it.name.endsWith(".zip") } == true

    /** La copia propia más reciente, o null si no hay ninguna. */
    fun latestLocal(c: Context): File? =
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.maxByOrNull { it.name }

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
     * Copia automática en la carpeta propia «ryx respaldo»: se revisa cada [Prefs.backupEverySec] segundos
     * (30 por defecto) y solo se guarda si algo cambió (ajustes / historial / imágenes). Así no hay que pulsar nada
     * y tampoco se llena la carpeta con copias idénticas.
     */
    fun autoLocal(c: Context, force: Boolean = false) {
        if (!Prefs.backupAuto(c) || running) return
        val now = System.currentTimeMillis()
        when {
            force -> {}
            stamp(c) == Prefs.backupStamp(c) -> return                                    // nada nuevo
            now - Prefs.backupLast(c) < Prefs.backupEverySec(c).coerceIn(5, 3600) * 1000L -> return
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

    /**
     * Busca el archivo de copia en la carpeta de la nube elegida. Devuelve su Uri, o null si no hay carpeta,
     * no hay archivo o Android retiró el permiso. Llamar fuera del hilo principal.
     */
    fun cloudFileUri(c: Context): Uri? {
        val raw = Prefs.cloudUri(c)
        if (raw.isEmpty()) return null
        return try {
            val tree = Uri.parse(raw)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            c.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                }
            }
            null
        } catch (e: Exception) { null }
    }

    /** ¿Hay una copia en la nube disponible en la carpeta elegida? */
    fun hasCloud(c: Context): Boolean = cloudFileUri(c) != null

    /**
     * Trae la copia de la nube a la carpeta propia «ryx respaldo» (sin restaurarla todavía) para poder
     * restaurarla después igual que cualquier copia local. Devuelve "" si salió bien o el motivo del fallo.
     */
    fun pullCloud(c: Context): String {
        val uri = cloudFileUri(c) ?: return "No hay ninguna copia en la nube."
        return try {
            val d = dir(c); val f = File(d, "ryx_" + System.currentTimeMillis() + ".zip")
            c.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ?: return "No se pudo leer la copia de la nube."
            ""
        } catch (e: Exception) { "No se pudo traer la copia: ${e.message ?: e.javaClass.simpleName}" }
    }
}
