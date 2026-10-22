package com.dav.isla

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
 *
 * Dos destinos, independientes:
 *  - Respaldo propio: carpeta VISIBLE «ryx respaldo» en el almacenamiento interno del teléfono
 *    (Download/ryx respaldo). Se guarda sola cada 30 s sin pulsar nada.
 *  - Copia en la nube: carpeta elegida en Google Drive u OneDrive (una sola vez, con el selector de Android).
 *
 * No hace falta ninguna clave ni cuenta dentro de Ryx.
 */
object Backup {
    /** Nombre del archivo de la copia en la nube / del respaldo propio. */
    const val NAME = "ryx_respaldo.zip"
    /** Nombre de la carpeta propia del respaldo. */
    const val FOLDER = "ryx respaldo"
    private var last = 0L
    // Varias llamadas pueden coincidir (onCreate, onResume, el temporizador…): compareAndSet evita que dos
    // copias se ejecuten a la vez y escriban el mismo archivo.
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastLock = Any()

    /** Paquetes conocidos de nube que Ryx puede detectar en el teléfono. */
    const val PKG_DRIVE = "com.google.android.apps.docs"
    const val PKG_ONEDRIVE = "com.microsoft.skydrive"

    fun configured(c: Context) = Prefs.cloudUri(c).isNotEmpty()

    /** ¿Está instalada la app de Google Drive? */
    fun hasDrive(c: Context): Boolean = installed(c, PKG_DRIVE)
    /** ¿Está instalada la app de OneDrive? */
    fun hasOneDrive(c: Context): Boolean = installed(c, PKG_ONEDRIVE)

    @Suppress("DEPRECATION")
    private fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- almacenamiento visible

    /**
     * Carpeta VISIBLE en el almacenamiento interno del teléfono: «Download/ryx respaldo».
     * En Android 10+ se escribe por MediaStore (sin permisos). Si ya existe una copia en la antigua
     * carpeta privada de la app («files/ryx respaldo»), se sigue leyendo para no perder nada.
     */
    fun publicDir(c: Context): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /** Carpeta privada antigua, solo como respaldo de compatibilidad. */
    fun privateDir(c: Context): File = File(c.filesDir, FOLDER).also { it.mkdirs() }

    /**
     * Guarda el .zip en la carpeta visible. Devuelve "" o el motivo del fallo.
     * Primero intenta el archivo directo (rápido, y funciona si el sistema lo permite); si falla,
     * lo publica por MediaStore, que crea la carpeta «Download/ryx respaldo» de forma visible.
     */
    private fun savePublic(c: Context, bytes: ByteArray): String {
        val dir = publicDir(c)
        if (dir.exists() || dir.mkdirs()) {
            val f = File(dir, "ryx_" + System.currentTimeMillis() + ".zip")
            try { f.outputStream().use { it.write(bytes) }; trimPublic(c); return "" } catch (e: Exception) {}
        }
        return try {
            val name = "ryx_" + System.currentTimeMillis() + ".zip"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = c.contentResolver.insert(col, values) ?: return "No se pudo crear la carpeta en el almacenamiento."
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return "No se pudo escribir el respaldo."
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                c.contentResolver.update(uri, values, null, null)
            }
            trimMediaStore(c)
            ""
        } catch (e: Exception) { "No pude guardar el respaldo: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun trimPublic(c: Context) {
        val dir = publicDir(c)
        val all = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.sortedByDescending { it.name } ?: return
        val keep = Prefs.backupKeep(c).coerceIn(1, 50)
        all.drop(keep).forEach { it.delete() }
    }

    /** Borra los respaldos más viejos creados por MediaStore, conservando [Prefs.backupKeep]. */
    private fun trimMediaStore(c: Context) {
        try {
            val keep = Prefs.backupKeep(c).coerceIn(1, 50)
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=?"
            c.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                sel, arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/", "application/zip"), null)?.use { cur ->
                val ids = ArrayList<Pair<String, Long>>()
                while (cur.moveToNext()) ids.add(cur.getString(0) to (cur.getString(1)?.removePrefix("ryx_")?.removeSuffix(".zip")?.toLongOrNull() ?: 0L))
                ids.sortedByDescending { it.second }.drop(keep).forEach {
                    c.contentResolver.delete(Uri.withAppendedPath(col, it.first), null, null)
                }
            }
        } catch (e: Exception) {}
    }

    /** Rutas donde puede haber respaldos: la privada (fiable) y la visible del almacenamiento. */
    private fun localDirs(c: Context): List<File> = listOf(privateDir(c), publicDir(c))

    /** ¿Existe ya algún respaldo guardado (visible o privado)? */
    fun hasLocal(c: Context): Boolean =
        localDirs(c).any { d -> d.listFiles()?.any { it.isFile && it.name.endsWith(".zip") } == true }

    /** El respaldo más reciente de cualquier carpeta, o null si no hay ninguno. */
    fun latestLocal(c: Context): File? =
        localDirs(c).flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: emptyList() }
            .maxByOrNull { nameStamp(it.name) }

    /** Marca de tiempo a partir del nombre del respaldo (ryx_123.zip / ryx_nube_123.zip). */
    private fun nameStamp(name: String): Long =
        name.removeSuffix(".zip").substringAfterLast('_').toLongOrNull() ?: 0L


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
     * Escribe un respaldo. Se guarda SIEMPRE una copia en la carpeta privada (que la app puede leer sin
     * problemas para restaurar) y ADEMÁS una copia VISIBLE en «Download/ryx respaldo» del almacenamiento
     * interno, para que el usuario la vea en cualquier app de Archivos. Rota conservando [Prefs.backupKeep].
     * Devuelve "" si salió bien o el motivo del fallo. Llamar fuera del hilo principal.
     */
    fun writeLocal(c: Context): String {
        return try {
            val bos = java.io.ByteArrayOutputStream()
            write(c, bos)
            val bytes = bos.toByteArray()
            // 1) Copia privada (fuente fiable para restaurar).
            val d = privateDir(c)
            val f = File(d, "ryx_" + System.currentTimeMillis() + ".zip")
            try { f.outputStream().use { it.write(bytes) } } catch (e: Exception) {}
            d.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.sortedByDescending { it.name }
                ?.drop(Prefs.backupKeep(c).coerceIn(1, 50))?.forEach { it.delete() }
            // 2) Copia VISIBLE en el almacenamiento del teléfono.
            val err = savePublic(c, bytes)
            Prefs.setBackupLast(c, System.currentTimeMillis())
            Prefs.setBackupStamp(c, stamp(c))
            if (err.isEmpty() || d.listFiles()?.isNotEmpty() == true) "" else err
        } catch (e: Exception) {
            "No pude guardar el respaldo: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Copia automática en la carpeta propia «ryx respaldo»: se revisa cada [Prefs.backupEverySec] segundos
     * (30 por defecto) y solo se guarda si algo cambió (ajustes / historial / imágenes). Así no hay que pulsar nada
     * y tampoco se llena la carpeta con copias idénticas.
     */
    fun autoLocal(c: Context, force: Boolean = false) {
        if (!Prefs.backupAuto(c)) return
        val now = System.currentTimeMillis()
        when {
            force -> {}
            stamp(c) == Prefs.backupStamp(c) -> return                                    // nada nuevo
            now - Prefs.backupLast(c) < Prefs.backupEverySec(c).coerceIn(5, 3600) * 1000L -> return
        }
        if (!running.compareAndSet(false, true)) return
        val app = c.applicationContext
        Thread { try { writeLocal(app) } finally { running.set(false) } }.start()
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
        // Una copia antigua puede no traer los códigos .bin/.b64: se generan al restaurarla.
        try { Prefs.migrateCodes(c, true) } catch (t: Throwable) {}
        return "Ajustes: ${if (settings) "restaurados" else "no encontrados"}\nNotificaciones en el historial: $items\nArchivos restaurados: $imgs"
    }

    private fun mergeHistory(c: Context, incoming: String): Int {
        var count = 0
        // Leer y escribir en una sola operación: si no, un aviso que llegue mientras se restaura se perdería.
        try {
            Prefs.updateHistRaw(c) { current ->
                val all = ArrayList<JSONObject>(); val seen = HashSet<String>()
                fun add(a: JSONArray) {
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        if (seen.add("${o.optString("p")}|${o.optLong("ts")}|${o.optString("t")}|${o.optString("x")}")) all.add(o)
                    }
                }
                try { add(JSONArray(current)) } catch (e: Exception) {}
                try { add(JSONArray(incoming)) } catch (e: Exception) {}
                all.sortByDescending { it.optLong("ts") }
                val out = JSONArray()
                all.take(600).forEach { out.put(it) }
                count = out.length()
                out.toString()
            }
        } catch (e: Exception) {}
        return count
    }

    /** Escribe la copia en la carpeta elegida. Devuelve "" si salió bien, o el motivo del fallo. Llamar fuera del hilo principal. */
    fun upload(c: Context): String {
        val raw = Prefs.cloudUri(c)
        if (raw.isEmpty()) return "Todavía no elegiste una carpeta."
        return try {
            val tree = Uri.parse(raw)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            // Se guarda dentro de «ryx respaldo» (se crea si no existe); si no se pudiera, en la carpeta elegida.
            val dir = ensureFolder(c) ?: DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(dir))
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
        if (!configured(c)) return
        val now = System.currentTimeMillis()
        synchronized(lastLock) {
            if (now - last < minGapMs) return
            if (!running.compareAndSet(false, true)) return
            last = now
        }
        val app = c.applicationContext
        Thread { try { upload(app) } finally { running.set(false) } }.start()
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
            // 1) Busca dentro de la subcarpeta «ryx respaldo».
            ensureFolder(c)?.let { folder ->
                findChild(c, tree, DocumentsContract.getDocumentId(folder), NAME)?.let { return it }
            }
            // 2) Si no, en la carpeta elegida directamente (versiones anteriores).
            findChild(c, tree, treeId, NAME)
        } catch (e: Exception) { null }
    }

    /** Busca un hijo con nombre [name] dentro del documento [parentId] del árbol [tree]. */
    private fun findChild(c: Context, tree: Uri, parentId: String, name: String): Uri? {
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            c.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
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
            val bos = java.io.ByteArrayOutputStream()
            c.contentResolver.openInputStream(uri)?.use { i -> bos.write(i.readBytes()) }
                ?: return "No se pudo leer la copia de la nube."
            val bytes = bos.toByteArray()
            // Copia privada (fiable para restaurar) + copia visible.
            val f = File(privateDir(c), "ryx_nube_" + System.currentTimeMillis() + ".zip")
            try { f.outputStream().use { it.write(bytes) } } catch (e: Exception) {}
            savePublic(c, bytes)
            Prefs.setCloudLast(c, System.currentTimeMillis())
            ""
        } catch (e: Exception) { "No se pudo traer la copia: ${e.message ?: e.javaClass.simpleName}" }
    }

    // ---------------------------------------------------------------- nube: Drive / OneDrive

    /**
     * Uri raíz del proveedor de Google Drive para abrir el selector DE DIRECTAMENTE en «Mi unidad».
     * Devuelve null si no está disponible (entonces el selector se abre normal).
     */
    fun driveRootUri(): Uri? = try {
        val authority = "com.google.android.apps.docs.storage"
        DocumentsContract.buildRootUri(authority, "root")
    } catch (e: Exception) { null }

    /**
     * Uri raíz del proveedor de OneDrive para abrir el selector DE DIRECTAMENTE en la unidad del usuario.
     * El id de raíz de OneDrive puede variar; si no está, el selector se abre normal.
     */
    fun oneDriveRootUri(): Uri? = try {
        DocumentsContract.buildRootUri("com.microsoft.skydrive", "od_root")
    } catch (e: Exception) { null }

    /**
     * Nombre comercial del destino elegido, para mostrarlo en la interfaz. Se deduce del authority del árbol.
     */
    fun providerLabel(c: Context): String {
        val raw = Prefs.cloudUri(c)
        if (raw.isEmpty()) return ""
        val a = try { Uri.parse(raw).authority ?: "" } catch (e: Exception) { "" }
        return when {
            a.contains("docs", true) -> "Google Drive"
            a.contains("skydrive", true) || a.contains("onedrive", true) -> "OneDrive"
            a.contains("externalstorage", true) || a.contains("downloads", true) -> "Almacenamiento del teléfono"
            else -> a
        }
    }

    /**
     * Crea (o reutiliza) la carpeta «ryx respaldo» DENTRO del árbol ya elegido y devuelve su Uri.
     * Con la carpeta creada, el archivo vive en «Mi unidad/ryx respaldo» o donde el usuario haya elegido.
     * Devuelve null si no se pudo. Llamar fuera del hilo principal.
     */
    fun ensureFolder(c: Context): Uri? {
        val raw = Prefs.cloudUri(c)
        if (raw.isEmpty()) return null
        return try {
            val tree = Uri.parse(raw)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            // ¿ya existe la subcarpeta «ryx respaldo»?
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            c.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == FOLDER &&
                        cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                    }
                }
            }
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            DocumentsContract.createDocument(c.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, FOLDER)
        } catch (e: Exception) { null }
    }
}
