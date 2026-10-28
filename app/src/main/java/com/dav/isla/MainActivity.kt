package com.dav.isla

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.BitmapFactory
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    /** Líneas visibles antes de pulsar «Leer más» en un mensaje largo del Historial. */
    private val HISTORY_PREVIEW_LINES = 7
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var activePill: TextView
    private var tab = 0
    private var subPage: String? = null
    private var lastKey: String? = null            // pantalla anterior: solo se anima al cambiar de pestaña
    private var tabBarKey: String? = null          // pestaña dibujada en la barra: solo se rehace si cambia
    private var landscapeView = false
    private val expandedGroups = HashSet<String>().apply { add("power") }
    private var previewView: IslandPreview? = null
    private var previewOpen = false
    private var appQuery = ""
    // Historial: búsqueda por texto y por app (se reconstruye solo la lista, sin perder el cursor del buscador)
    private var histQuery = ""
    private var histFilter = "__apps__"
    private var histBox: LinearLayout? = null
    /** Una sola lectura del historial por render: antes se volvía a parsear el JSON en cada consulta. */
    private var histCache: List<Prefs.HistItem>? = null
    /** Grupos del historial dibujados ahora mismo; «Ver todo» los abre sin rehacer la pantalla. */
    private val histGroupHandles = ArrayList<CollapsibleHandle>()
    /** Tarjetas de Diseño dibujadas ahora mismo; «Abrir todo» las despliega sin rehacer la pantalla. */
    private val designHandles = ArrayList<CollapsibleHandle>()
    /** Cancela aperturas diferidas anteriores al cambiar de sección o de búsqueda. */
    private var histExpandToken = 0
    /** Agrupa las pulsaciones del buscador para no reconstruir la lista en cada letra. */
    private var histSearchToken = 0
    private var pendingHtml = ""
    private var rootStatus = "Sin comprobar"
    /** Posición de scroll guardada por pestaña/subpágina para no llevar al usuario arriba al refrescar. */
    private val scrollPositions = HashMap<String, Int>()
    /** Hilos para descodificar miniaturas del historial fuera del hilo principal (2 bastan y no saturan). */
    private val imgWork = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var htmlBuilding = false
    // Gestos: el índice se guarda en los ajustes, así que la lista NO cambia de tamaño (así los gestos ya elegidos
    // siguen funcionando). «Burbuja» y los códigos Base64/binario se retiraron: sus huecos quedan como «Nada».
    // La base binaria/Base64 es interna; al usuario solo se le ofrece copiar el mensaje o la imagen/sticker.
    private val actionNames = listOf("Nada", "Abrir app", "Ventana en formato libre", "Descartar tarjeta",
        "Siguiente tarjeta", "Tarjeta anterior", "Contraer isla", "Ver detalle",
        "Nada (sin acción)", "Formato libre", "Copiar mensaje completo", "Silenciar esta app",
        "Nada (sin acción)", "Nada (sin acción)", "Copiar imagen/sticker", "Nada (sin acción)")
    // Solo se ofrecen acciones reales. Un gesto guardado en un hueco retirado se lee como «Nada».
    private val selectableActions = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14)
    private fun normalizedAction(a: Int): Int = if (a in selectableActions) a else 0
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { render() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { render() } }
    private var appsBox: LinearLayout? = null
    private val iconCache = HashMap<String, Drawable?>()
    private var appListCache: List<Pair<String, String>>? = null
    private val appList: List<Pair<String, String>> get() = appListCache ?: run {
        val pm = packageManager
        val base = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
        // Cada copia clonada (app dual / segundo espacio) es una entrada más, con su clave «paquete@espacio»
        val clones = Clones.cloneEntries(this).map { it.key to it.label }.filter { it.first != packageName }
        (base + clones).sortedBy { it.second.lowercase() }
    }.also { appListCache = it }
    private val appsPerm = "com.android.permission.GET_INSTALLED_APPS"
    /** Solo HyperOS/MIUI definen este permiso; en ROMs AOSP no existe y no se pide. */
    private fun hasAppsPerm() = try { packageManager.getPermissionInfo(appsPerm, 0); true } catch (e: Exception) { false }
    private fun appsPermOk() = !hasAppsPerm() || checkSelfPermission(appsPerm) == PackageManager.PERMISSION_GRANTED

    /** Permiso de imágenes: solo se pide para el último recurso de fotos de WhatsApp. */
    private fun imagePerm(): String =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES"
        else "android.permission.READ_EXTERNAL_STORAGE"
    private fun imagePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 34) arrayOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        else arrayOf(imagePerm())
    private fun imagePermOk(): Boolean = try {
        checkSelfPermission(imagePerm()) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)
    } catch (e: Throwable) { false }
    private fun askImagePermission() {
        if (imagePermOk()) return
        try { requestPermissions(imagePerms(), 11) }
        catch (e: Throwable) { toast("No se pudo pedir el permiso de fotos") }
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)
    private fun svc() = NotifListener.instance
    private fun applyAll() { svc()?.applySettings() }

    /** Refresco ligero de la isla (geometría) sin destruir la vista; útil mientras se mueve un deslizador. */
    private fun live() { svc()?.refreshLive(); previewView?.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.migrateGeometry(this)
        Prefs.clearLegacyBlur(this)
        Thread { try { Prefs.migrateCodes(this) } catch (t: Throwable) {} }.start()
        Glass.applyTheme(this)
        window.setBackgroundDrawable(Glass.page(this))
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootView.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        // Cabecera: logo, nombre y estado, compacta y limpia
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), px(14), px(20), px(8))
        }
        val logoBox = FrameLayout(this).apply {
            setPadding(px(2), px(2), px(2), px(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(px(1), Glass.accentA(0x66))
            }
        }
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        head.addView(logoBox, LinearLayout.LayoutParams(px(44), px(44)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(label("Ryx", 22f, R.color.gold, true).apply { letterSpacing = -0.02f })
        nameRow.addView(label(" Island", 22f, R.color.text, true).apply { letterSpacing = -0.02f })
        names.addView(nameRow)
        names.addView(label("Panel de control · por David Joel", 12f, R.color.muted))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(12) })
        activePill = label("", 12f, R.color.green, true).apply { setPadding(px(12), px(6), px(12), px(6)) }
        head.addView(activePill)
        rootView.addView(head)

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Barra de navegación flotante (propia, separada de los bordes)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.card(this@MainActivity, 30f, true)
            setPadding(px(7), px(7), px(7), px(7))
            elevation = px(10).toFloat()
        }
        rootView.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(px(16), px(6), px(16), px(10))
        })
        setContentView(rootView)
        render()
        if (!appsPermOk()) try { requestPermissions(arrayOf(appsPerm), 5) } catch (e: Throwable) {}
        askNotifPermission()
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListener(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
        } catch (e: Throwable) {}
        Backup.maybeUpload(this, 60 * 60_000L)
        Backup.autoLocal(this)
        CloudSync.syncIfNeeded(this)
        offerRestore()
        if (!RyxAccessibility.isEnabled(this) && !Prefs.a11yAsked(this)) {
            Prefs.setA11yAsked(this, true); askAccessibility()
        }
        if (System.currentTimeMillis() - Prefs.updLast(this) > Prefs.updEverySec(this) * 1000L) checkUpdates(false)
        handleExtras()
    }

    /** Revisa actualizaciones y refresca pantallas al volver a la app (sin molestar si ya se miró hace poco). */
    override fun onResume() {
        super.onResume()
        updatePill()
        if (tab == 3 || tab == 4 || subPage == "perms") render()
        if (System.currentTimeMillis() - Prefs.updLast(this) > Prefs.updEverySec(this) * 1000L) checkUpdates(false)
        Backup.autoLocal(this)
        CloudSync.syncIfNeeded(this)
    }

    /** Aperturas pedidas desde fuera: el aviso de «hay versión nueva» del panel del sistema. */
    private fun handleExtras() {
        val url = intent?.getStringExtra("ryx_open") ?: return
        intent?.removeExtra("ryx_open")
        val manual = intent?.getBooleanExtra("ryx_manual_upd", false) == true
        intent?.removeExtra("ryx_manual_upd")
        AppNotify.ensureChannel(this)
        if (url.isNotEmpty()) openUrl(url)
        if (manual) checkUpdates(true)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleExtras()
    }

    /** Pide el permiso de notificaciones (Android 13+). Lo usan los avisos de actualización del panel. */
    fun askNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (AppNotify.allowed(this)) return
        try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9) } catch (e: Throwable) {}
    }

    private val REQ_TREE = 71
    private val REQ_RESTORE = 72
    private val REQ_HTML = 73

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            REQ_TREE -> {
                try { contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
                Prefs.setCloudUri(this, uri.toString())
                toast("Carpeta elegida. Guardando copia…")
                Thread {
                    val r = Backup.upload(this)
                    runOnUiThread { toast(if (r.isEmpty()) "Copia guardada en la nube" else r); render() }
                }.start()
            }
            REQ_RESTORE -> Thread {
                val msg = try {
                    contentResolver.openInputStream(uri)?.use { Backup.restore(this, it) } ?: "No se pudo abrir el archivo."
                } catch (e: Exception) { "No se pudo restaurar: ${e.message ?: e.javaClass.simpleName}" }
                runOnUiThread { applyAll(); render(); dialog("Restaurar copia", msg) }
            }.start()
            REQ_HTML -> try {
                contentResolver.openOutputStream(uri)?.use { it.write(pendingHtml.toByteArray(Charsets.UTF_8)) }
                toast("Historial guardado como .html")
            } catch (e: Exception) { toast("No pude escribir el archivo: ${e.message ?: ""}") }
        }
    }

    private fun pickRestore() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE)

    /**
     * Abre el selector de carpetas. Si se pasa [initial] (raíz de Drive u OneDrive), el selector empieza
     * directamente ahí, para que el usuario cree/elija la carpeta «ryx respaldo» en la nube.
     */
    private fun pickCloudFolder(initial: Uri? = null) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (initial != null && android.os.Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        startActivityForResult(i, REQ_TREE)
    }

    /**
     * Al abrir la app con el historial vacío (instalación nueva o datos reiniciados), mira si hay un respaldo
     * disponible y PREGUNTA antes de restaurar. Fuentes, en orden:
     *  1) la carpeta propia «ryx respaldo»;
     *  2) la copia en la nube (Google Drive / OneDrive), si hay una carpeta elegida con archivo.
     * Nada se restaura sin que el usuario lo confirme.
     */
    private fun offerRestore() {
        if (Prefs.restoreAsked(this) || Prefs.histAll(this).isNotEmpty()) return
        Prefs.setRestoreAsked(this, true)
        Thread {
            val local = Backup.latestLocal(this)
            val cloud = if (Backup.configured(this)) Backup.cloudFileUri(this) else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    local != null -> glass(AlertDialog.Builder(this).setTitle("¿Restaurar tu respaldo?")
                        .setMessage("Encontré un respaldo en la carpeta «${Backup.FOLDER}». " +
                            "¿Quieres recuperar tus ajustes y el historial de notificaciones?")
                        .setPositiveButton("Restaurar") { _, _ -> restoreLocal(local) }
                        .setNegativeButton("Ahora no", null).show())
                    cloud != null -> glass(AlertDialog.Builder(this).setTitle("¿Restaurar desde la nube?")
                        .setMessage("Encontré una copia en la nube (ryx_respaldo.zip). " +
                            "¿Quieres traerla y recuperar tus ajustes y el historial de notificaciones?")
                        .setPositiveButton("Restaurar") { _, _ -> restoreFromCloud() }
                        .setNegativeButton("Ahora no", null).show())
                    else -> glass(AlertDialog.Builder(this).setTitle("¿Restaurar tu historial?")
                        .setMessage("Si ya tenías una copia de Ryx Island (por ejemplo en Google Drive), puedes traerla ahora: " +
                            "recupera tus ajustes y el historial de notificaciones.")
                        .setPositiveButton("Elegir copia") { _, _ -> pickRestore() }
                        .setNegativeButton("Ahora no", null).show())
                }
            }
        }.start()
    }

    /** Restaura una copia de la carpeta propia y avisa del resultado. */
    private fun restoreLocal(f: java.io.File) {
        toast("Restaurando…")
        Thread {
            val msg = try { f.inputStream().use { Backup.restore(this, it) } } catch (e: Exception) { "No pude restaurar: ${e.message}" }
            runOnUiThread { applyAll(); render(); dialog("Restaurar respaldo", msg) }
        }.start()
    }

    /** Trae la copia de la nube a la carpeta propia y la restaura (con confirmación previa). */
    private fun restoreFromCloud() {
        toast("Trayendo la copia…")
        Thread {
            val err = Backup.pullCloud(this)
            if (err.isNotEmpty()) { runOnUiThread { dialog("Restaurar desde la nube", err) }; return@Thread }
            val f = Backup.latestLocal(this)
            val msg = if (f == null) "No se pudo leer la copia." else try { f.inputStream().use { Backup.restore(this, it) } }
                catch (e: Exception) { "No pude restaurar: ${e.message}" }
            runOnUiThread { applyAll(); render(); dialog("Restaurar desde la nube", msg) }
        }.start()
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("No se pudo abrir el enlace") }
    }

    private fun checkUpdates(manual: Boolean) {
        if (manual) toast("Buscando actualizaciones…")
        Prefs.setUpdLast(this, System.currentTimeMillis())
        Thread {
            val r = Updater.check(this)
            // El aviso al panel se pone desde este mismo hilo de fondo: llega aunque la app no esté delante.
            r.info?.let { i ->
                if (i.newer) {
                    val key = if (i.version.isNotEmpty()) i.version else i.name
                    if (key != Prefs.updSeen(this)) {
                        AppNotify.update(this, i.version, i.name, i.apk, i.page, i.notes)
                        Prefs.setUpdSeen(this, key)
                    }
                } else if (Prefs.updSeen(this).isNotEmpty()) {
                    Prefs.setUpdSeen(this, ""); AppNotify.clearUpdate(this)   // ya está instalada: se quita el aviso
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val i = r.info
                if (i == null) {
                    if (manual) dialog("Actualizaciones", r.error)
                } else if (i.newer) {
                    val warn = if (i.channel.isEmpty()) "" else
                        "⚠ Esta versión es ${i.channel.uppercase()}. Puede tener errores o fallar; instalarla es bajo tu propio riesgo.\n\n"
                    glass(AlertDialog.Builder(this).setTitle("Hay una versión nueva: ${if (i.version.isNotEmpty()) "v" + i.version else i.name}")
                        .setMessage(warn + i.notes.trim().take(500).ifEmpty { "Hay una actualización disponible." } +
                            "\n\nPara actualizar, Ryx descarga el APK, guarda tu copia en la nube y desinstala esta versión; " +
                            "luego abres el archivo descargado para instalar la nueva.")
                        .setPositiveButton(if (i.channel.isEmpty()) "Actualizar" else "Entiendo el riesgo, actualizar") { _, _ -> startUpdate(i) }
                        .setNegativeButton("Luego", null).show())
                } else if (manual) {
                    dialog("Actualizaciones", "Ya tienes la última versión (v${BuildConfig.VERSION_NAME}).${if (i.version.isNotEmpty()) "\nÚltima publicada: v${i.version}" else ""}")
                }
            }
        }.start()
    }

    /** 1) copia en la nube  2) descarga del APK a Descargas  3) desinstala esta versión  4) el usuario instala el APK descargado. */
    private fun startUpdate(i: Updater.Info) {
        if (i.apk.isEmpty()) { openUrl(i.page); return }
        if (!Backup.configured(this)) {
            glass(AlertDialog.Builder(this).setTitle("Sin copia en la nube")
                .setMessage("Al desinstalar se borra el historial de notificaciones y tus ajustes. " +
                    "Configura la copia en la nube primero si quieres conservarlos.")
                .setPositiveButton("Configurar copia") { _, _ -> cloudDialog() }
                .setNeutralButton("Actualizar sin copia") { _, _ -> downloadAndUninstall(i) }
                .setNegativeButton("Cancelar", null).show())
        } else downloadAndUninstall(i)
    }

    private fun downloadAndUninstall(i: Updater.Info) {
        val fileName = "RyxIsland-${i.tag.ifEmpty { "nueva" }}.apk"
        val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
        val id = try {
            dm.enqueue(android.app.DownloadManager.Request(Uri.parse(i.apk))
                .setTitle("Ryx Island ${i.name}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName))
        } catch (e: Exception) { dialog("Actualizar", "No se pudo iniciar la descarga: ${e.message ?: e.javaClass.simpleName}"); return }
        toast("Descargando la actualización…")
        Thread {
            var status = 0
            for (n in 0 until 600) {                          // hasta ~5 minutos
                val cur = dm.query(android.app.DownloadManager.Query().setFilterById(id))
                if (cur != null) cur.use {
                    if (it.moveToFirst()) status = it.getInt(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                }
                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL || status == android.app.DownloadManager.STATUS_FAILED) break
                Thread.sleep(500)
            }
            if (status != android.app.DownloadManager.STATUS_SUCCESSFUL) {
                runOnUiThread { dialog("Actualizar", "No se pudo descargar el APK. No se desinstaló nada.") }
                return@Thread
            }
            val backup = if (Backup.configured(this)) Backup.upload(this) else "sin copia"
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val bMsg = when {
                    backup.isEmpty() -> "Copia en la nube guardada ✔"
                    backup == "sin copia" -> "Sin copia en la nube: el historial se perderá."
                    else -> "⚠ La copia en la nube falló: $backup"
                }
                glass(AlertDialog.Builder(this).setTitle("Listo para actualizar")
                    .setMessage("$bMsg\n\nAPK descargado en Descargas/$fileName.\n\n" +
                        "Ahora se desinstalará esta versión. Después, abre la notificación de descarga o el archivo en Descargas " +
                        "para instalar la nueva (Android puede pedirte permitir instalar desde esa app). " +
                        "Al abrir la nueva, usa «Restaurar» para recuperar tu historial.")
                    .setPositiveButton("Desinstalar ahora") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }
                        catch (e: Exception) { toast("No se pudo abrir el desinstalador") }
                    }.setNegativeButton("Cancelar", null).show())
            }
        }.start()
    }

    /** Abre Accesibilidad e intenta resaltar el servicio de Ryx. */
    private fun openAccessibility() {
        val cn = ComponentName(this, RyxAccessibility::class.java).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        i.putExtra(":settings:fragment_args_key", cn)
        i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", cn) })
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e2: Exception) { toast("No se pudo abrir Accesibilidad") }
        }
    }

    private fun openAppInfo() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { toast("No se pudo abrir") }
    }

    /** Pide el permiso de accesibilidad con las instrucciones exactas. */
    private fun askAccessibility(onSkip: (() -> Unit)? = null) {
        glass(AlertDialog.Builder(this).setTitle("Falta el permiso de accesibilidad")
            .setMessage("Con este servicio Ryx abre las ventanas flotantes desde el propio servicio, sin sacarte de tu app, y comprueba que la ventana quedó flotante. " +
                "No lee ni guarda lo que ves.\n\n" +
                "1. Toca «Abrir accesibilidad» y entra en «Apps instaladas» (o «Servicios descargados»).\n" +
                "2. Elige «Ryx Island · Formato libre» y actívalo.\n\n" +
                "Si no aparece o sale en gris: toca «Info de la app», abre ⋮ (arriba a la derecha) → «Permitir ajustes restringidos», " +
                "y vuelve a intentarlo. Si sigue sin aparecer, asegúrate de haber instalado la versión más reciente de Ryx.")
            .setPositiveButton("Abrir accesibilidad") { _, _ -> openAccessibility() }
            .setNeutralButton("Info de la app") { _, _ -> openAppInfo() }
            .setNegativeButton(if (onSkip == null) "Luego" else "Probar sin él") { _, _ -> onSkip?.invoke() }.show())
    }

    private fun askUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e2: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        appListCache = null; iconCache.clear(); render()
        // Tras conceder los permisos runtime, seguir con los accesos especiales del modo máximo.
        if (code == 12 && !isFinishing) window.decorView.postDelayed({ openNextMaxAccess() }, 350L)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(deadListener)
        } catch (e: Throwable) {}
        try { imgWork.shutdownNow() } catch (e: Throwable) {}
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (subPage != null) { subPage = null; render() } else super.onBackPressed()
    }
    private fun updatePill() {
        val on = Prefs.islandOn(this) && Settings.canDrawOverlays(this)
        activePill.text = if (on) "● Activa" else "● Inactiva"
        activePill.setTextColor(col(if (on) R.color.green else R.color.red))
        activePill.background = Glass.pill(this, if (on) 0x243DDC84 else 0x24FF6B6B,
            if (on) 0x663DDC84 else 0x66FF6B6B, 14f)
    }

    // ---------------- Navegación ----------------

    // Menú inferior: Isla · Demon · Diseño · Historial · Ajustes.
    // «Demon» e «Historial» suben aquí como pestañas propias (antes eran subpáginas de Ajustes).
    // «Puente» desaparece: su contenido se reparte entre Diseño (iconos, conectividad) y Ventana (sistema).
    private val tabs = listOf("bolt" to "Isla", "flash" to "Demon", "tune" to "Diseño", "history" to "Historial", "settings" to "Ajustes")

    private fun render() {
        // Al cambiar de pantalla, el contenido entra con un fundido corto; al refrescar la misma, no parpadea
        val key = subPage ?: "t$tab"
        val changed = key != lastKey
        val tabChanged = tabBarKey != key
        lastKey = key
        tabBarKey = key
        content.animate().cancel()
        if (changed) { content.alpha = 0f; content.animate().alpha(1f).setDuration(200).start() } else content.alpha = 1f
        updatePill()
        // La barra de abajo solo se reconstruye (iconos, pastillas y animación) cuando cambia de pestaña.
        // Antes se rehacía en cada refresco, y con ella cinco iconos: eso era lo que hacía lento el menú.
        if (tabChanged) renderTabBar()
        // Guardar posición de scroll antes de reconstruir, para no llevar al usuario arriba
        val currentScroll = when (val child = content.getChildAt(0)) {
            is PullRefreshScroll -> child.scrollY()
            is android.widget.ScrollView -> child.scrollY
            else -> 0
        }
        if (currentScroll > 0) scrollPositions[key] = currentScroll
        content.removeAllViews()
        previewView = null; appsBox = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(6), px(18), px(24))
        }
        val sp = subPage
        if (sp != null) {
            val heroTitle = when (sp) { "history" -> "Historial"; "demon" -> "Demon"; else -> "Permisos" }
            val heroSub = when (sp) {
                "history" -> "Todo lo que pasa por la isla, buscable y exportable"
                "demon" -> "Pruebas de la isla, aparte del resto de la app"
                else -> "Accesos que Ryx necesita para trabajar"
            }
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(2), px(8), px(2), px(8))
                background = Glass.ripple(this@MainActivity, Glass.pill(this@MainActivity, 0, 0, 20f), 20f)
                setOnClickListener { subPage = null; render() }
            }
            hero.addView(FrameLayout(this).apply {
                background = Glass.pill(this@MainActivity, Glass.SURFACE_HI, Glass.HAIR, 20f)
                addView(icon("back", 20, Glass.TEXT).apply { layoutParams = FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER) })
            }, LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(14) })
            val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ht.addView(label(heroTitle, 24f, R.color.text, true).apply { letterSpacing = -0.02f })
            ht.addView(label(heroSub, 12.5f, R.color.muted))
            hero.addView(ht, LinearLayout.LayoutParams(0, WRAP, 1f))
            page.addView(hero, lp(6))
            when (sp) {
                "history" -> buildHistory(page)
                "demon" -> buildDemon(page)
                else -> buildPerms(page)
            }
            if (sp == "history") content.addView(PullRefreshScroll(this).apply {
                refreshHint = "Buscando lo último…"
                addView(page)
                onRefresh = {
                    histCache = Prefs.reloadHist(this@MainActivity)
                    fillHist()
                    postDelayed({ done() }, 350)
                }
            })
            else content.addView(ScrollView(this).apply { addView(page); isVerticalScrollBarEnabled = false })
            return
        }
        when (tab) {
            0 -> buildHome(page)
            1 -> buildDemon(page)
            2 -> buildLayout(page)
            3 -> buildHistory(page)
            else -> buildSettings(page)
        }
        val scroll: View = if (tab == 3) {
            // Historial: arrastra hacia abajo para volver a cargar lo último del historial.
            PullRefreshScroll(this).apply {
                refreshHint = "Buscando lo último…"
                addView(page)
                onRefresh = {
                    histCache = Prefs.reloadHist(this@MainActivity)
                    fillHist()
                    postDelayed({ done() }, 350)
                }
            }
        } else ScrollView(this).apply { addView(page); isVerticalScrollBarEnabled = false }
        // Restaurar posición de scroll guardada
        val savedY = scrollPositions[key] ?: 0
        if (savedY > 0) {
            scroll.post {
                when (scroll) {
                    is PullRefreshScroll -> scroll.scrollTo(0, savedY)
                    is android.widget.ScrollView -> scroll.scrollTo(0, savedY)
                    else -> {}
                }
            }
        }
        val fixed: View? = when (tab) { 2 -> buildPreview(); else -> null }
        if (fixed != null) {
            val colm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            colm.addView(fixed, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = px(18); marginEnd = px(18); topMargin = px(4) })
            colm.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            content.addView(colm)
        } else content.addView(scroll)
    }

    /** Barra de navegación de abajo. Se reconstruye solo al cambiar de pestaña (ver [render]). */
    private fun renderTabBar() {
        tabBar.removeAllViews()
        // Índice de la pestaña que debe verse resaltada: si hay subpágina (Permisos), se marca Ajustes (índice 4).
        val activeTab = if (subPage == "perms") 4 else tab
        tabs.forEachIndexed { i, (ic, name) ->
            val sel = i == activeTab && (subPage == null || subPage == "perms" && i == 4)
            val colr = if (sel) Glass.ACCENT else Glass.MUTED
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, px(8), 0, px(7))
                background = Glass.ripple(this@MainActivity, Glass.pillCached(this@MainActivity, 0, 0, 24f), 24f)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (tab != i || subPage != null) { tab = i; subPage = null; render() }
                }
            }
            // Indicador tras el icono (como los menús modernos): solo el icono lleva la pastilla, la etiqueta queda debajo
            val ind = FrameLayout(this).apply {
                if (sel) background = Glass.pillCached(this@MainActivity, Glass.accentA(0x2E), Glass.accentA(0x4D), 16f)
            }
            ind.addView(icon(ic, 22, colr), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
            if (sel) { ind.scaleX = 0.88f; ind.alpha = 0.6f; ind.animate().scaleX(1f).alpha(1f).setDuration(220).start() }
            b.addView(ind, LinearLayout.LayoutParams(px(58), px(32)))
            b.addView(label(name, 10.5f, R.color.text, true).apply {
                setTextColor(if (sel) Glass.ACCENT else Glass.MUTED); gravity = Gravity.CENTER
            }, lp(3))
            tabBar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(2); marginEnd = px(2) })
        }
    }

    /** Editor visual fijo arriba: muestra la parte superior de ESTE teléfono con la isla en vivo. */
    private fun buildPreview(): View {
        val c = card().apply { setPadding(px(12), px(12), px(12), px(12)) }
        val m = resources.displayMetrics
        val wDp = (minOf(m.widthPixels, m.heightPixels) / m.density).toInt()
        val hDp = (maxOf(m.widthPixels, m.heightPixels) / m.density).toInt()
        c.addView(label("Vista previa · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", 15f, R.color.text, true))
        c.addView(label("Pantalla $wDp × $hDp dp · Android ${Build.VERSION.RELEASE}", 12f, R.color.muted))
        val pv = IslandPreview(this)
        pv.landscape = landscapeView
        val ins = window.decorView.rootWindowInsets
        if (Build.VERSION.SDK_INT >= 28) {
            ins?.displayCutout?.boundingRects?.firstOrNull { it.top <= 5 }?.let { pv.cutout = android.graphics.RectF(it) }
        }
        pv.statusBarPx = if (Build.VERSION.SDK_INT >= 30 && ins != null)
            ins.getInsets(WindowInsets.Type.statusBars()).top.toFloat() else 0f
        previewView = pv
        if (previewOpen) pv.setExpanded(true)
        c.addView(pv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (previewOpen) "Ver compacta" else "Ver expandida", false) {
            previewOpen = !previewOpen; pv.setExpanded(previewOpen); render()
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(6) })
        row.addView(button("▶ Animación", true) { previewOpen = false; pv.play() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(6) })
        c.addView(row, lp(8))
        return c
    }

    // ---------------- Tarjetas con titulo ----------------

    private fun titled(p: LinearLayout, title: String, sub: String = ""): LinearLayout {
        val c = card()
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(8)) }
        t.addView(label(title, 17.5f, R.color.text, true).apply { letterSpacing = -0.01f })
        if (sub.isNotEmpty()) t.addView(label(sub, 13f, R.color.muted).apply { setPadding(0, px(3), 0, 0) })
        c.addView(t)
        p.addView(c, lp(12))
        return c
    }

    /** Cabecera de pestaña: tarjeta con el tinte de la sección, icono elevado y título grande. */
    private fun pageHero(p: LinearLayout, iconName: String, color: Int, title: String, sub: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.hero(this@MainActivity, color, 26f)
            setPadding(px(16), px(18), px(16), px(18))
        }
        row.addView(tileIcon(iconName, Glass.withA(color, 0x3A), color),
            LinearLayout.LayoutParams(px(50), px(50)).apply { marginEnd = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 26f, R.color.text, true).apply { letterSpacing = -0.025f })
        t.addView(label(sub, 13f, R.color.muted).apply { setPadding(0, px(2), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(row, lp(6))
    }

    /** Texto de registro en un recuadro oscuro y seleccionable. Solo se usa dentro de las hojas de detalle. */
    private fun logBlock(text: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.pill(this@MainActivity, 0xFF0B0D11.toInt(), Glass.HAIR, 14f)
            setPadding(px(14), px(12), px(14), px(12))
        }
        box.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFD6E6DC.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(px(3).toFloat(), 1f)
        })
        return box
    }

    /**
     * Tarjeta de diagnóstico con aspecto profesional: NUNCA muestra el registro en crudo, para no ensuciar la pantalla.
     * Ofrece «Ver detalle» (hoja con el texto) y «Copiar».
     */
    private fun diagCard(p: LinearLayout, iconName: String, color: Int, title: String, summary: String, log: String) {
        if (log.isEmpty()) return
        val c = card()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon(iconName, (color and 0x00FFFFFF) or 0x33000000, color),
            LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 16.5f, R.color.text, true))
        t.addView(label(summary, 12.5f, R.color.muted))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(r)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Ver detalle", false) {
            sheet(title) { b ->
                b.addView(logBlock(log))
                b.addView(label("Cópialo si vas a reportar un fallo.", 12f, R.color.muted), lp(8))
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(5) })
        row.addView(button("Copiar", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", log))
            toast("Registro copiado")
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5) })
        c.addView(row, lp(12))
        p.addView(c, lp(10))
    }

    // ---------------- INICIO ----------------

    private fun buildHome(p: LinearLayout) {
        p.addView(heroCard(), lp(8))
        // Ficha rápida de estado: cuántos avisos hay activos y qué método de privilegios está en uso
        val activos = Events.GROUPS.sumOf { g -> g.events.count { Prefs.eventOnFor(this, it.id, landscapeView) } }
        val total = Events.GROUPS.sumOf { it.events.size }
        statGrid(p, listOf(
            Triple("bolt", "$activos", "Avisos activos de $total"),
            Triple("shield", avisosLabel(), "Vías de aviso en uso")))
        // (La tarjeta con interruptor «Ryx Island» se eliminó: la tarjeta de arriba ya enciende y apaga con su botón.)
        statusStrip(p)

        Events.GROUPS.forEach { g ->
            val on0 = g.events.count { Prefs.eventOnFor(this, it.id, landscapeView) }
            var subLabel: TextView? = null
            collapsibleCard(p, g.id, false, head = { top ->
                top.addView(tileIcon(g.symbol), LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(14) })
                val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                t.addView(label(g.label, 16.5f, R.color.text, true))
                val sl = label("$on0 de ${g.events.size} activos", 13f, R.color.muted)
                subLabel = sl
                t.addView(sl)
                top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                top.addView(icon("expand", 20, Glass.ACCENT).apply {
                    setPadding(px(6), px(6), px(6), px(6))
                    setOnClickListener {
                        val v = !g.events.all { Prefs.eventExpand(this@MainActivity, it.id) }
                        g.events.forEach { Prefs.setEventExpand(this@MainActivity, it.id, v) }
                        toast(if (v) "${g.label}: se abre expandida" else "${g.label}: solo aviso breve"); render()
                    }
                })
                top.addView(makeSwitch(on0 > 0) { v ->
                    g.events.forEach { Prefs.setEventOnFor(this, it.id, landscapeView, v) }
                    // Se actualiza el contador en el sitio, sin rehacer la pantalla.
                    subLabel?.text = "${if (v) g.events.size else 0} de ${g.events.size} activos"
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(6) })
            }, body = { b ->
                var row: LinearLayout? = null
                g.events.forEachIndexed { i, ev ->
                    if (i % 2 == 0) {
                        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        b.addView(row, lp(8))
                    }
                    val isOn = Prefs.eventOnFor(this, ev.id, landscapeView)
                    val ex = Prefs.eventExpand(this, ev.id)
                    val chip = FrameLayout(this).apply {
                        background = Glass.ripple(this@MainActivity, Glass.chipCached(this@MainActivity, isOn, 18f), 18f)
                        val col2 = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                            setPadding(px(6), px(18), px(6), px(16))
                            addView(icon(ev.symbol, 26, if (isOn) Glass.ACCENT else Glass.MUTED))
                            addView(label(ev.label, 14f, R.color.text, true).apply {
                                gravity = Gravity.CENTER; setTextColor(if (isOn) Glass.TEXT else Glass.MUTED)
                            }, lp(8))
                        }
                        addView(col2, FrameLayout.LayoutParams(MATCH, WRAP))
                        addView(icon("expand", 16, if (ex && isOn) Glass.ACCENT else 0x66B4B8A6).apply {
                            isClickable = true
                            setOnClickListener { Prefs.setEventExpand(this@MainActivity, ev.id, !ex); render() }
                        }, FrameLayout.LayoutParams(px(16), px(16), Gravity.TOP or Gravity.END).apply {
                            topMargin = px(10); marginEnd = px(10)
                        })
                        setOnClickListener { Prefs.setEventOnFor(this@MainActivity, ev.id, landscapeView, !isOn); render() }
                    }
                    row?.let { it.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5); marginEnd = px(5) }) }
                }
                if (g.events.size % 2 == 1) row?.let { it.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                b.addView(label("Toca para activar o desactivar · la flecha ⤢ elige si se abre expandida",
                    12f, R.color.muted), lp(10))
            })
        }
        // Comportamiento y visibilidad de la isla (vivían en Diseño; aquí es donde tienen sentido).
        heading(p, "COMPORTAMIENTO")
        val co = card()
        co.addView(rowWithSwitch("Isla siempre visible", "Se queda en reposo junto a la cámara", Prefs.permanent(this)) { v ->
            Prefs.setPermanent(this, v); applyAll() })
        co.addView(rowWithSwitch("Vibrar al mostrar", "Una vibración corta al aparecer la isla", Prefs.vibrate(this)) {
            Prefs.setVibrate(this, it)
        })
        p.addView(co, lp(8))

        heading(p, "VISIBILIDAD")
        val vi = card()
        vi.addView(rowWithSwitch("Mostrar en la pantalla de bloqueo", "La isla aparece también con el teléfono bloqueado",
            Prefs.lockScreen(this)) { Prefs.setLockScreen(this, it); applyAll() })
        vi.addView(rowWithSwitch("Ocultar en juegos",
            if (Foreground.hasAccess(this)) "Si detecta un juego abierto, la isla se esconde (las notificaciones siguen en el sistema)"
            else "Necesita «Acceso de uso» para saber qué app está al frente",
            Prefs.hideInGames(this)) { v ->
            Prefs.setHideInGames(this, v)
            if (v && !Foreground.hasAccess(this)) askUsageAccess()
        })
        val hid = Prefs.hideApps(this).size
        vi.addView(button(if (hid == 0) "Ocultar en apps concretas…" else "Ocultar en apps concretas ($hid)", false) {
            if (!Foreground.hasAccess(this)) { toast("Concede primero el acceso de uso"); askUsageAccess() }
            pickApps("Ocultar la isla en…", Prefs.hideApps(this)) { Prefs.setHideApps(this, it) }
        }, lp(10))
        vi.addView(label("Un juego se detecta si la app se declara como juego (la mayoría de los instalados desde Play Store). " +
            "Si alguno no se detecta, agrégalo en «Ocultar en apps concretas».", 12f, R.color.muted), lp(8))
        p.addView(vi, lp(8))

        // Accesos directos a las pestañas, igual que en la barra de abajo.
        heading(p, "ATAJOS")
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shortcuts.addView(button("Historial", false) { tab = 3; subPage = null; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(5) })
        shortcuts.addView(button("Diseño", false) { tab = 2; subPage = null; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5) })
        p.addView(shortcuts, lp(8))
    }

    /** Sección «Demon»: todas las pruebas de la isla, aparte del resto de la app. */
    private fun buildDemon(p: LinearLayout) {
        pageHero(p, "flash", 0xFFFF9F0A.toInt(), "Demon", "Pruebas de la isla, aparte del resto de la app")
        buildTest(p)
    }

    /** Tarjeta principal de la pestaña Isla: estado actual con el color del tema y acciones rápidas. */
    private fun heroCard(): View {
        val on = Prefs.islandOn(this) && Settings.canDrawOverlays(this)
        val st = Styles.byId(Prefs.style(this))
        val th = Glass.THEMES.firstOrNull { it.id == Prefs.appTheme(this) } ?: Glass.THEMES[0]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.hero(this@MainActivity, if (on) Glass.ACCENT else Glass.RED, 28f)
            setPadding(px(20), px(20), px(20), px(18))
            elevation = px(6).toFloat()
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tileIcon("bolt", Glass.accentA(0x36), Glass.ACCENT),
            LinearLayout.LayoutParams(px(54), px(54)).apply { marginEnd = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(if (on) "Isla activa" else "Isla inactiva", 22f, R.color.text, true).apply { letterSpacing = -0.02f })
        t.addView(label("Estilo ${st.label}  ·  Tema ${th.label}", 13f, R.color.muted).apply { setPadding(0, px(2), 0, 0) })
        top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(label(if (on) "● En línea" else "● Detenida", 11.5f, if (on) R.color.green else R.color.red, true).apply {
            setPadding(px(10), px(5), px(10), px(5))
            background = Glass.pill(this@MainActivity, if (on) 0x243DDC84 else 0x24FF6B6B,
                if (on) 0x663DDC84 else 0x66FF6B6B, 12f)
        })
        box.addView(top)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (on) "Apagar" else "Encender", !on) {
            Prefs.setIslandOn(this, !Prefs.islandOn(this)); applyAll(); render()
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(5) })
        row.addView(button("Permisos", false) { subPage = "perms"; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5) })
        box.addView(row, lp(18))
        return box
    }

    /** Tira de accesos rápidos: lleva a Permisos con un toque, mostrando en verde lo que ya está concedido. */
    private fun statusStrip(p: LinearLayout) {
        val c = card()
        c.addView(label("Accesos", 16f, R.color.text, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0) }
        val chips = listOf(
            "Avisos" to notifEnabled(),
            "Encima" to Settings.canDrawOverlays(this),
            "A11y" to RyxAccessibility.isEnabled(this),
            "Uso" to Foreground.hasAccess(this))
        chips.forEachIndexed { i, chip ->
            row.addView(TextView(this).apply {
                text = (if (chip.second) "● " else "○ ") + chip.first
                gravity = Gravity.CENTER; textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (chip.second) 0xFF3DDC84.toInt() else Glass.MUTED)
                setPadding(px(8), px(10), px(8), px(10))
                background = Glass.ripple(this@MainActivity, Glass.pillCached(this@MainActivity,
                    if (chip.second) 0x1F3DDC84 else Glass.SURFACE_HI, if (chip.second) 0x553DDC84 else Glass.HAIR, 14f), 14f)
                setOnClickListener { subPage = "perms"; render() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = px(6) })
        }
        c.addView(row)
        c.addView(label("Toca para abrir Permisos. En verde, lo que ya está concedido.", 12f, R.color.muted), lp(10))
        p.addView(c, lp(10))
    }

    /** Resumen corto de las vías de aviso activas, para la ficha de Inicio. */
    private fun avisosLabel(): String {
        val vias = ArrayList<String>()
        if (notifEnabled()) vias.add("Avisos")
        if (Settings.canDrawOverlays(this)) vias.add("Encima")
        if (RyxAccessibility.isEnabled(this)) vias.add("A11y")
        if (Foreground.hasAccess(this)) vias.add("Uso")
        if (Shell.privileged(this)) vias.add(Shell.label(this))
        return if (vias.isEmpty()) "Ninguna" else vias.joinToString(" · ")
    }

    // ---------------- Probar eventos (dentro de Isla) ----------------

    private fun buildTest(p: LinearLayout) {
        val c = titled(p, "Probar eventos", "Pone una tarjeta de ejemplo en la isla. Los eventos cortos se van solos.")
        c.addView(rowWithSwitch("Abrir la tarjeta expandida", "", Prefs.demoExpanded(this)) { Prefs.setDemoExpanded(this, it) })
        if (svc() == null) c.addView(label("⚠ Activa primero el acceso a notificaciones (Ajustes → Permisos).", 13f, R.color.red), lp(6))
        Events.GROUPS.forEach { g ->
            c.addView(label(g.label, 13f, R.color.muted, true), lp(12))
            g.events.chunked(2).forEach { pair ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { ev ->
                    r.addView(button("▶ ${ev.label}", false) {
                        val s = svc()
                        if (s == null) toast("Activa el acceso a notificaciones") else s.showEvent(ev.id, true)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
                }
                if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                c.addView(r, lp(2))
            }
        }
        c.addView(button("▶ Simular acumulación (4 notificaciones)", false) {
            val sv = svc(); if (sv == null) toast("Activa el acceso a notificaciones") else sv.demoStack()
        }, lp(12))
        c.addView(button("Quitar tarjetas", true) { svc()?.clearDemo() }, lp(8))
    }

    // ---------------- DISEÑO ----------------

    private fun buildLayout(p: LinearLayout) {
        designHandles.clear()
        pageHero(p, "palette", 0xFF7C89FF.toInt(), "Diseño", "Formas, colores, tamaños y movimiento; todo en tarjetas")
        heroStat(p, 0xFF7C89FF.toInt(), listOf(
            "Estilo" to Styles.byId(Prefs.style(this)).label,
            "Tema" to Glass.theme.label,
            "Medidas" to "${Prefs.idleWidthDp(this)}×${Prefs.heightDp(this)} · ${IslandGeometry.EXPANDED_WIDTH_DP}×${IslandGeometry.EXPANDED_HEIGHT_DP} dp"))

        // Menú de la pestaña, como en Historial: las secciones se abren y cierran sin rehacer la pantalla.
        val menu = card()
        menu.addView(label("Diseño en tarjetas", 16f, R.color.text, true))
        menu.addView(label("Cada bloque se abre por separado. «Abrir todo» los despliega de una vez y luego se convierte en «Contraer todo».", 12f, R.color.muted), lp(4))
        val menuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        menuRow.addView(label("Tu isla, por secciones", 12f, R.color.muted), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(8) })
        val toggle = TextView(this).apply {
            text = "Abrir todo"; textSize = 12.5f; gravity = Gravity.CENTER
            setTextColor(0xFF7C89FF.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(px(14), px(8), px(14), px(8))
            background = Glass.ripple(this@MainActivity,
                Glass.pill(this@MainActivity, Glass.withA(0xFF7C89FF.toInt(), 0x26), Glass.withA(0xFF7C89FF.toInt(), 0x55), 15f), 15f)
        }
        menuRow.addView(toggle)
        menu.addView(menuRow, lp(10))
        p.addView(menu, lp(8))

        // ─────────────────────────── ESTILOS ───────────────────────────
        designSection(p, "styles", "Estilos de isla", "${Styles.ALL.size} acabados: cristal, color, neón, claro y más", "palette", 0xFF7C89FF.toInt(), true) { b ->
            Styles.ALL.chunked(2).forEach { pair ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { sty ->
                    r.addView(styleCell(sty), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
                }
                if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                b.addView(r, lp(8))
            }
            b.addView(label("El estilo cambia colores, borde, icono y tipografía. El tamaño base no cambia: 160 × 32 dp cerrada y 350 × 74 dp abierta.", 12f, R.color.muted), lp(8))
        }

        // ─────────────────────────── ISLA CONTRAÍDA ───────────────────────────
        designSection(p, "collapsed", "Isla contraída", "160 × 32 dp por defecto, con esquinas de pastilla HyperOS", "window", 0xFF4DA3FF.toInt()) { b ->
            b.addView(label("Es la isla en reposo. Por defecto mide 160 × 32 dp y sus esquinas son una pastilla completa, como la HyperOS Island.", 12f, R.color.muted), lp(2))
            val mode = Prefs.idleMode(this)
            b.addView(segmented(listOf("Negro", "Cristal", "Color", "Acento"), mode, true) {
                Prefs.setIdleMode(this, it); applyAll(); svc()?.showTest()
            }, lp(10))
            if (mode != 0) {
                b.addView(label(if (mode == 1) "Tinte del cristal" else "Color", 13f, R.color.muted, true), lp(12))
                colorRow(b, Prefs.idleColor(this)) { Prefs.setIdleColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
                hexRow(b) { Prefs.setIdleColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
            }
            shapeRow(b, Prefs.idleCornerDp(this)) { Prefs.setIdleCornerDp(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
            seekRow(b, "Radio de esquina fino", "dp", 0, 50, Prefs.idleCornerDp(this).coerceIn(0, 50)) { Prefs.setIdleCornerDp(this, it); applyAll(); svc()?.showTest() }
            seekRow(b, if (mode == 1) "Transparencia del cristal" else "Opacidad", "%", 15, 100, Prefs.idleOpacity(this)) { Prefs.setIdleOpacity(this, it) }
            seekRow(b, "Borde", "dp", 0, 4, Prefs.idleBorder(this).let { if (it >= 0) it else if (mode == 1) 1 else 0 }) { Prefs.setIdleBorder(this, it) }
            b.addView(infoNote("shield", 0xFF3DDC84.toInt(),
                "Cristal seguro: el brillo y la transparencia se dibujan dentro de la isla. El desenfoque del sistema se retiró porque en Android 12+ y HyperOS podía bloquear la pantalla."), lp(12))
            seekRow(b, "Ancho en reposo", "dp", 60, 220, Prefs.idleWidthDp(this)) { Prefs.setIdleWidthDp(this, it); live() }
            seekRow(b, "Alto en reposo", "dp", 20, 60, Prefs.heightDp(this)) { Prefs.setHeightDp(this, it); live() }
            seekRow(b, "Posición vertical (margen superior)", "dp", 0, 80, Prefs.yOffsetDp(this)) { Prefs.setYOffsetDp(this, it); live() }
            seekRow(b, "Posición horizontal", "dp", -100, 100, Prefs.xOffsetDp(this)) { Prefs.setXOffsetDp(this, it); live() }
            b.addView(label("El ancho y el alto se aplican al instante. Si eliges una esquina manual, se respeta aunque no sea una pastilla.", 12f, R.color.muted), lp(8))
        }

        // ─────────────────────────── ISLA EXPANDIDA ───────────────────────────
        designSection(p, "expanded", "Isla expandida", "350 × 74 dp fijos, también con texto largo o imagen", "expand", 0xFF6A62E8.toInt()) { b ->
            b.addView(label("Tamaño fijo: 350 × 74 dp. No crece con el texto ni con la imagen: el contenido se ajusta y se desplaza dentro de esa caja. En pantallas muy estrechas se centra con el margen que quede.", 12f, R.color.muted), lp(2))
            b.addView(rowWithSwitch("Efecto cristal (Liquid Glass)", "Brillo y borde de luz dentro de la isla. No desenfoca el resto de la pantalla.", Prefs.cardGlass(this)) { v ->
                Prefs.setCardGlass(this, v); applyAll(); svc()?.showTest(); previewView?.refresh() })
            b.addView(label("Color de la tarjeta", 13f, R.color.muted, true), lp(12))
            colorRow(b, Prefs.cardColor(this)) { Prefs.setCardColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
            hexRow(b) { Prefs.setCardColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
            shapeRow(b, Prefs.cornerDp(this)) { Prefs.setCornerDp(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
            seekRow(b, "Radio de esquina fino", "dp", 0, 50, Prefs.cornerDp(this).coerceIn(0, 50)) { Prefs.setCornerDp(this, it); applyAll(); svc()?.showTest() }
            seekRow(b, "Transparencia", "%", 15, 100, Prefs.opacity(this)) { Prefs.setOpacity(this, it); live() }
            b.addView(rowWithSwitch("Sombra bajo la isla", "Da profundidad a la tarjeta", Prefs.shadow(this)) { v ->
                Prefs.setShadow(this, v); applyAll(); svc()?.showTest(); previewView?.refresh() })
            b.addView(button("Restablecer tamaño y posición", false) {
                Prefs.setXOffsetDp(this, 0); Prefs.setYOffsetDp(this, 8)
                Prefs.setCornerDp(this, -1)
                Prefs.setIdleWidthDp(this, IslandGeometry.COLLAPSED_WIDTH_DP); Prefs.setHeightDp(this, IslandGeometry.COLLAPSED_HEIGHT_DP)
                applyAll(); svc()?.showTest(); render()
            }, lp(12))
        }

        // ─────────────────────────── ACENTO DE LA APP ───────────────────────────
        designSection(p, "accent", "Acento de la app", "Botones, interruptores y detalles. Por defecto: amarillo", "palette", 0xFFFFC857.toInt()) { b ->
            Glass.THEMES.chunked(3).forEach { rowThemes ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowThemes.forEach { t ->
                    val on = Glass.theme.id == t.id
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        setPadding(px(4), px(10), px(4), px(8))
                        background = Glass.ripple(this@MainActivity, Glass.pillCached(this@MainActivity,
                            if (on) Glass.withA(t.accent, 0x2A) else Glass.SURFACE_HI, if (on) t.accent else Glass.HAIR, 16f), 16f)
                        setOnClickListener { Prefs.setAppTheme(this@MainActivity, t.id); Glass.applyTheme(this@MainActivity); recreate() }
                    }
                    cell.addView(View(this).apply { background = Glass.pill(this@MainActivity, t.accent, 0, 20f) },
                        LinearLayout.LayoutParams(px(34), px(34)))
                    cell.addView(label(t.label, 11f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(6))
                    r.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
                }
                var n = rowThemes.size
                while (n < 3) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); n++ }
                b.addView(r, lp(6))
            }
        }

        // ─────────────────────────── NOTIFICACIONES ───────────────────────────
        designSection(p, "notif", "Notificaciones", "Iconos, tipografía y color inteligente", "bell", 0xFFFF9F0A.toInt()) { b ->
            b.addView(label("De dónde sale el icono y cómo se recorta", 13f, R.color.muted, true), lp(2))
            b.addView(segmented(listOf("Sistema", "Notificación", "Monocromo"), Prefs.iconMode(this), true) {
                Prefs.setIconMode(this, it); applyAll(); svc()?.showTest()
            }, lp(10))
            b.addView(label("Forma del icono", 13f, R.color.muted, true), lp(12))
            val shapes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val shapeCells = ArrayList<LinearLayout>()
            fun paintShapes(sel: Int) {
                shapeCells.forEachIndexed { i, cell ->
                    cell.background = Glass.ripple(this@MainActivity, Glass.chipCached(this@MainActivity, i == sel, 16f), 16f)
                }
            }
            listOf("Círculo", "Squircle", "Flor", "Hexágono").forEachIndexed { i, n ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(px(4), px(10), px(4), px(8))
                    background = Glass.ripple(this@MainActivity, Glass.chipCached(this@MainActivity, Prefs.iconShape(this@MainActivity) == i, 16f), 16f)
                    setOnClickListener { paintShapes(i); Prefs.setIconShape(this@MainActivity, i); applyAll(); svc()?.showTest() }
                }
                cell.addView(ShapedImageView(this).apply {
                    shape = i; setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(px(40), px(40)))
                cell.addView(label(n, 11f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(6))
                shapeCells.add(cell)
                shapes.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
            }
            b.addView(shapes, lp(6))
            b.addView(rowWithSwitch("Color inteligente", "El borde toma el color del icono de la app", Prefs.smartColor(this)) {
                Prefs.setSmartColor(this, it); applyAll(); svc()?.showTest()
            })
            b.addView(rowWithSwitch("Título en negrita", "El título se resalta; el cuerpo se lee en letra normal",
                Prefs.fontBold(this)) { v -> Prefs.setFontBold(this, v); applyAll(); svc()?.showTest() })
        }

        // ─────────────────────────── FOTOS Y MEDIA ───────────────────────────
        designSection(p, "media", "Fotos y media", "Primero se leen del aviso; si no vienen, se busca la última foto de la app", "camera", 0xFF40C8E0.toInt()) { b ->
            b.addView(rowWithSwitch("Completar fotos de WhatsApp", "Si el aviso dice «Foto» pero no trae la imagen, usa la última foto reciente de la carpeta de WhatsApp en la galería.",
                Prefs.mediaFolder(this)) { v ->
                Prefs.setMediaFolder(this, v)
                if (v) askImagePermission()
                render()
            })
            b.addView(label(if (imagePermOk()) "Permiso de fotos: concedido ✔" else "Permiso de fotos: falta. Sin él, Ryx solo puede usar las imágenes que vengan dentro del aviso.",
                12f, if (imagePermOk()) R.color.green else R.color.gold), lp(8))
            if (!imagePermOk()) b.addView(button("Dar permiso de fotos", true) { askImagePermission() }, lp(10))
            b.addView(label("No se hace ninguna captura de pantalla. Primero se buscan Bitmaps, Iconos, URIs y datos de MessagingStyle dentro de la notificación; la carpeta es el último recurso y solo para avisos de foto/sticker.", 12f, R.color.muted), lp(8))
        }

        // ─────────────────────────── CONTENIDO Y PRIVACIDAD ───────────────────────────
        designSection(p, "privacy", "Contenido y privacidad", "Palabras que se ocultan y apps silenciadas", "shield", 0xFFB388FF.toInt()) { b ->
            b.addView(label("Palabras ocultas", 13f, R.color.muted, true), lp(2))
            b.addView(editText("palabra1, palabra2…", Prefs.hiddenWordsRaw(this)) { Prefs.setHiddenWords(this, it) }, lp(8))
            b.addView(label("Apague las apps que no quiera ver en la isla. Cada copia clonada se controla por separado.", 12f, R.color.muted), lp(10))
            b.addView(editText("Buscar app…", appQuery) { q -> appQuery = q; fillApps() }, lp(8))
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            appsBox = box
            b.addView(box, lp(6))
            fillApps()
        }

        // ─────────────────────────── GESTOS ───────────────────────────
        designSection(p, "gestures", "Gestos de la isla", "Con la isla abierta. Si hay varias notificaciones, se acumulan a la derecha", "tune", 0xFF4ADE80.toInt()) { b ->
            gestureRow(b, "Tocar", Prefs.gTap(this)) { Prefs.setGTap(this, it) }
            gestureRow(b, "Deslizar hacia abajo", Prefs.gDown(this)) { Prefs.setGDown(this, it) }
            gestureRow(b, "Deslizar hacia arriba", Prefs.gUp(this)) { Prefs.setGUp(this, it) }
            gestureRow(b, "Deslizar a la izquierda", Prefs.gLeft(this)) { Prefs.setGLeft(this, it) }
            gestureRow(b, "Deslizar a la derecha", Prefs.gRight(this)) { Prefs.setGRight(this, it) }
            gestureRow(b, "Mantener pulsado", Prefs.gLong(this)) { Prefs.setGLong(this, it) }
            b.addView(label("Distancia para deslizar: 50 dp (fija)", 14f, R.color.text, true), lp(12))
            b.addView(label("Un deslizamiento solo cuenta a partir de 50 dp, para que no se active sin querer. «Formato libre» abre la app flotando sobre la que estás.", 12f, R.color.muted), lp(4))
        }

        // ─────────────────────────── ANIMACIÓN ───────────────────────────
        designSection(p, "animation", "Animación", "Resorte estilo HyperOS 3: sube un poco y se asienta", "bolt", 0xFFFFC857.toInt()) { b ->
            b.addView(segmented(listOf("Relajada", "Normal", "Ágil"), Prefs.speed(this), true) { Prefs.setSpeed(this, it) }, lp(10))
            seekRow(b, "Tiempo visible", "s", 2, 15, Prefs.durationSeconds(this)) { Prefs.setDurationSeconds(this, it) }
            b.addView(rowWithSwitch("Vibrar al mostrar", "Una vibración corta al aparecer la isla", Prefs.vibrate(this)) {
                Prefs.setVibrate(this, it)
            })
            b.addView(rowWithSwitch("Vista horizontal", "Previsualiza y aplica la isla en horizontal",
                landscapeView) { landscapeView = it; render() })
        }

        // ─────────────────────────── SISTEMA Y AVISOS ───────────────────────────
        designSection(p, "system", "Sistema y avisos", "Solo la isla, conectividad y diagnóstico del puente", "signal", 0xFF3F7BEA.toInt()) { b ->
            buildBridge(b)
        }

        // ─────────────────────────── VENTANA FLOTANTE ───────────────────────────
        designSection(p, "window", "Formato libre del sistema", "Ventanas del launcher, tamaño y apps no redimensionables", "window", 0xFF40C8E0.toInt()) { b ->
            buildWindow(b)
        }

        bindDesignToggle(toggle)
    }

    /** Abre o cierra todas las tarjetas de Diseño y mantiene el texto del botón. */
    private fun bindDesignToggle(toggle: TextView) {
        val sync = {
            toggle.text = if (designHandles.isNotEmpty() && designHandles.all { it.isOpen() }) "Contraer todo" else "Abrir todo"
        }
        designHandles.forEach { it.onToggle = { sync() } }
        sync()
        toggle.setOnClickListener {
            val expand = toggle.text != "Contraer todo"
            designHandles.forEach { it.setOpen(expand, false) }
            sync()
        }
    }

    /** Tarjeta desplegable de Diseño con icono, título, subtítulo y cuerpo perezoso. */
    private fun designSection(
        parent: LinearLayout, key: String, title: String, sub: String,
        iconName: String, color: Int, defaultOpen: Boolean = false,
        body: (LinearLayout) -> Unit
    ) {
        val handle = collapsibleCard(parent, "design_$key", defaultOpen, head = { h ->
            h.addView(tileIcon(iconName, Glass.withA(color, 0x2E), color),
                LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(title, 17f, R.color.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            t.addView(label(sub, 12f, R.color.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, body = { b ->
            b.setPadding(0, px(8), 0, 0)
            body(b)
        }, topMargin = 8, lazyBody = true)
        designHandles.add(handle)
    }

    /** Nota informativa compacta: icono tonal y texto, con fondo de pastilla suave. */
    private fun infoNote(iconName: String, tone: Int, text: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.control(this@MainActivity, 0x0DFFFFFF, Glass.HAIR, 14f)
            setPadding(px(11), px(10), px(12), px(10))
        }
        box.addView(tileIcon(iconName, Glass.withA(tone, 0x26), tone),
            LinearLayout.LayoutParams(px(32), px(32)).apply { marginEnd = px(10) })
        box.addView(label(text, 12f, R.color.muted), LinearLayout.LayoutParams(0, WRAP, 1f))
        return box
    }

    private val swatchColors = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF2A2E38.toInt(), 0xFFFF8A4C.toInt(),
        0xFFFF5A5F.toInt(), 0xFFFF6B9A.toInt(), 0xFFB388FF.toInt(), 0xFF4DA3FF.toInt(), 0xFF22E5FF.toInt(),
        0xFF4ADE80.toInt(), 0xFFFFC857.toInt(), 0xFF2EE6C5.toInt(), 0xFFFF7A59.toInt(), 0xFF7C89FF.toInt(),
        0xFF00D4C8.toInt(), 0xFFE0E5EC.toInt(), 0xFFB4B8C4.toInt(), 0xFF6A62E8.toInt())

    /** Fila de colores: «Auto» (0) y una paleta para tocar. */
    private fun colorRow(parent: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        val sv = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, px(4)) }
        val views = ArrayList<TextView>()
        // El color elegido muestra una marca de verificación legible sobre cualquier fondo;
        // «Auto» conserva su texto. El anillo del acento sigue marcando la selección.
        fun paint(sel: Int) {
            views.forEachIndexed { i, tv ->
                val c = if (i == 0) 0 else swatchColors[i - 1]
                val on = c == sel
                (tv.background as? android.graphics.drawable.GradientDrawable)?.setStroke(px(if (on) 2 else 1), if (on) Glass.ACCENT else 0x55FFFFFF)
                tv.text = when { c == 0 -> "Auto"; on -> "✓"; else -> "" }
                tv.setTextColor(if (c == 0) Glass.TEXT else if (on) Glass.onAccent(c) else Color.TRANSPARENT)
                tv.textSize = if (c == 0) 10.5f else 16f
            }
        }
        (listOf(0) + swatchColors).forEach { c ->
            val on = current == c
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = if (c == 0) 10.5f else 16f
                includeFontPadding = false
                setTextColor(if (c == 0) Glass.TEXT else if (on) Glass.onAccent(c) else Color.TRANSPARENT)
                text = when { c == 0 -> "Auto"; on -> "✓"; else -> "" }
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (c == 0) 0x22FFFFFF else c)
                    setStroke(px(if (on) 2 else 1), if (on) Glass.ACCENT else 0x55FFFFFF)
                }
                setOnClickListener { paint(c); onPick(c) }
            }
            views.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(8) })
        }
        sv.addView(row)
        parent.addView(sv, lp(6))
    }

    /** Color propio escrito como #RRGGBB (se aplica al terminar de escribirlo). */
    private fun hexRow(parent: LinearLayout, onColor: (Int) -> Unit) {
        parent.addView(editText("Color propio: #RRGGBB", "") { t ->
            val h = t.trim().let { if (it.startsWith("#")) it else "#$it" }
            if (h.length == 7) try { onColor(Color.parseColor(h) or 0xFF000000.toInt()) } catch (e: Exception) {}
        }, lp(8))
    }

    private fun styleCell(sty: IslandStyle): View {
        val on = Prefs.style(this) == sty.id
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(8), px(12), px(8), px(10))
            background = Glass.ripple(this@MainActivity, Glass.chip(this@MainActivity, on, 18f), 18f)
            setOnClickListener { Prefs.setStyle(this@MainActivity, sty.id); applyAll(); svc()?.showTest(); render() }
            Glass.press(this, 0.97f)
        }
        val sw = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sty.bgTop, sty.bgBottom)).apply {
                // La miniatura enseña la esquina real del estilo, no siempre la misma pastilla.
                cornerRadius = px(minOf(sty.cornerDp, 22f).toInt()).toFloat()
                if (sty.strokeDp > 0f) setStroke(px(1), sty.strokeColor)
            }
        }
        // La miniatura lleva una insignia de verificación cuando el estilo está elegido: más claro
        // que el punto diminuto anterior y sin depender del color del texto.
        val preview = FrameLayout(this).apply {
            addView(sw, FrameLayout.LayoutParams(px(110), px(30)))
            if (on) {
                val badge = FrameLayout(this@MainActivity).apply {
                    background = Glass.pill(this@MainActivity, Glass.ACCENT, 0, 9f)
                    addView(ImageView(this@MainActivity).apply {
                        setImageDrawable(Glass.icon(this@MainActivity, "check", Glass.onAccent()))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(px(11), px(11), Gravity.CENTER))
                }
                addView(badge, FrameLayout.LayoutParams(px(18), px(18), Gravity.TOP or Gravity.END).apply {
                    topMargin = px(2); marginEnd = px(2)
                })
            }
        }
        cell.addView(preview, LinearLayout.LayoutParams(px(110), px(30)))
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nameRow.addView(label(sty.label, 12f, R.color.text, true).apply {
            gravity = Gravity.CENTER; setTextColor(if (on) Glass.ACCENT else Glass.TEXT)
        })
        cell.addView(nameRow, lp(8))
        return cell
    }

    private fun gestureRow(parent: LinearLayout, title: String, current: Int, set: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(label(title, 15f, R.color.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val selected = normalizedAction(current)
        r.addView(button(actionNames[selected], false) {
            val choices = selectableActions.map { actionNames[it] }.toTypedArray()
            glass(AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(choices, selectableActions.indexOf(selected)) { d, i ->
                    set(selectableActions[i]); d.dismiss(); render()
                }
                .setNegativeButton("Cancelar", null).show())
        })
        parent.addView(r, lp(8))
    }

    // ---------------- SISTEMA Y AVISOS (parte de Diseño) ----------------

    private fun buildBridge(p: LinearLayout) {
        val only = titled(p, "Solo la isla", "La isla registra y muestra; el sistema se calla")
        only.addView(rowWithSwitch("Bloquear notificaciones del sistema", "Quita la del panel cuando la isla la muestra",
            Prefs.blockSystem(this)) { Prefs.setBlockSystem(this, it) })
        only.addView(rowWithSwitch("Ocultar avisos emergentes", "Evita que baje el aviso de la ROM antes de la isla (permiso de una sola vez)",
            Prefs.hideHeadsUp(this)) { v ->
            Thread { val r = Shell.setHeadsUp(this, !v); runOnUiThread {
                if (r.first == 0) Prefs.setHideHeadsUp(this, v) else {
                    try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", FreeformUnlock.adbCommand(this))) } catch (e: Exception) {}
                    dialog("Falta un permiso (una sola vez)",
                        "Para ocultar el aviso emergente de la ROM, Ryx necesita el permiso WRITE_SECURE_SETTINGS. Es el mismo del desbloqueo de ventanas, " +
                        "se concede una vez y se queda (no hace falta Shizuku después):\n\n" +
                        "• Con ADB (ya copié el comando al portapapeles):\n${FreeformUnlock.adbCommand(this)}\n\n" +
                        "• O inicia Shizuku/root una vez y vuelve a activar este interruptor: Ryx se da el permiso a sí mismo.")
                    render() } } }.start()
        })
        only.addView(rowWithSwitch("Quitar lo que ya leí", "Al abrir la app o cancelarse el aviso, sale de la isla y no vuelve",
            Prefs.autoRead(this)) { Prefs.setAutoRead(this, it) })
        only.addView(label("Llamadas, multimedia, navegación y alarmas se quedan en el sistema para no romper sus controles. " +
            "Sin ocultar los avisos emergentes, Android puede mostrar el aviso un instante antes de que la isla lo quite.", 12f, R.color.muted), lp(8))
        diagCard(p, "signal", 0xFF3F7BEA.toInt(), "Diagnóstico del puente",
            "Últimos avisos que cruzaron entre el sistema y la isla, con el detalle a un toque.", Prefs.notifDiag(this))

        val ct = titled(p, "Conectividad", "El aviso de red era el que más aparecía: así se controla")
        ct.addView(segmented(listOf("Siempre", "Al cambiar", "Nunca"), Prefs.netMode(this), true) { Prefs.setNetMode(this, it) }, lp(10))
        ct.addView(label("«Al cambiar» (por defecto): solo se anuncia «Conectado a la red …» cuando entra una red nueva o " +
            "cuando pasa un buen rato desde el último aviso, nunca por las revalidaciones de la ROM. «Nunca» silencia " +
            "los avisos de red y los de Wi‑Fi apagado.", 12f, R.color.muted), lp(8))
    }

    private fun appIcon(key: String): Drawable? = iconCache.getOrPut(key) {
        try {
            val (pkg, uid) = Clones.splitKey(key)
            val pm = packageManager
            // En un clon se pide el icono a SU espacio (LauncherApps); si no, se usa el del principal.
            val base = if (Clones.isClone(uid)) {
                try {
                    val la = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
                    val ai = la.getApplicationInfo(pkg, 0, Clones.handle(uid))
                    ai.loadIcon(pm)
                } catch (e: Exception) { pm.getApplicationIcon(pkg) }
            } else pm.getApplicationIcon(pkg)
            // La copia clonada lleva la insignia que le pone la ROM (nombre/icono), para distinguirla del original
            val badged = if (Clones.isClone(uid)) pm.getUserBadgedIcon(base, Clones.handle(uid)) else base
            // Si el icono es adaptivo, asegurar que se renderice bien en cualquier forma
            badged?.let { d ->
                if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) d else d
            }
        } catch (e: Exception) { null }
    }

    /** Etiqueta pequeña para distinguir una copia clonada del original. */
    private fun cloneChip(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; gravity = Gravity.CENTER
        setTextColor(Glass.ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(8), px(3), px(8), px(3))
        background = Glass.pill(this@MainActivity, Glass.accentA(0x22), 0, 10f)
    }

    private fun fillApps() {
        val box = appsBox ?: return
        box.removeAllViews()
        val q = appQuery.trim().lowercase()
        val list = appList.filter { q.isEmpty() || it.second.lowercase().contains(q) }
        val blocked = Prefs.blocked(this)
        list.take(40).forEach { (key, name) ->
            val uid = Clones.splitKey(key).second
            val clone = Clones.isClone(uid)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(ShapedImageView(this).apply {
                shape = Prefs.iconShape(this@MainActivity); setImageDrawable(appIcon(key)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(name, 15f, R.color.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (clone) t.addView(cloneChip("copia clonada · espacio $uid"),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(4) })
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(makeSwitch(key !in blocked) { on ->
                val s = Prefs.blocked(this).toMutableSet(); if (on) s.remove(key) else s.add(key); Prefs.setBlocked(this, s)
            })
            box.addView(r, lp(8))
        }
        if (list.size > 40) box.addView(label("Mostrando 40 de ${list.size}. Escribe para buscar.", 12f, R.color.muted), lp(8))
        if (list.isEmpty()) box.addView(label("No hay apps con ese nombre.", 13f, R.color.muted), lp(8))
    }

    private fun editText(hint: String, value: String, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; setHintTextColor(Glass.MUTED); setTextColor(Glass.TEXT); textSize = 15f
        setSingleLine(true); setPadding(px(16), px(12), px(16), px(12))
        background = Glass.control(this@MainActivity, 0xFF0E1014.toInt(), Glass.HAIR, 14f)
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------------- VENTANA FLOTANTE (parte de Diseño) ----------------

    private fun buildWindow(p: LinearLayout) {
        val so = titled(p, "Formato libre del sistema", "La ventana la abre y la controla tu launcher o el sistema")
        // La «Burbuja» (Android 17) y la barra flotante propia se retiraron: la barra lateral de la ROM
        // ya mueve y redimensiona la ventana, y una barra encima tapaba sus gestos.
        so.addView(label(
            "Ryx le pide al sistema que abra la app en ventana libre con el tamaño y la posición que elijas. " +
            "La ventana se abre encima de la app en la que estás, sin pasar por Recientes, y con la notificación abre el chat exacto. " +
            "Los botones para mover, redimensionar y cerrar son los de tu ROM o tu barra lateral: Ryx no dibuja ninguna barra encima.",
            12f, R.color.muted), lp(6))
        so.addView(infoNote("window", 0xFF40C8E0.toInt(),
            "Sin barra propia: así no se tapan los gestos laterales de tu barra lateral ni los botones de la ventana del sistema."), lp(8))
        so.addView(label(if (RyxAccessibility.isEnabled(this)) "Servicio de accesibilidad: activo ✔" else
            "Servicio de accesibilidad: apagado. Actívalo para usar este método.", 12f, R.color.text), lp(8))
        so.addView(button(if (RyxAccessibility.isEnabled(this)) "Ajustes de accesibilidad" else "Activar accesibilidad", !RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) openAccessibility() else askAccessibility()
        }, lp(8))

        // Formato libre es el único modo: estas opciones van siempre.
        run {
            val un = titled(p, "Apps no redimensionables", "ROM: ${Build.DISPLAY} · Android ${Build.VERSION.RELEASE}")
            un.addView(label("Algunas apps dicen que no se pueden redimensionar y Android no las deja flotar. «Forzar que las actividades sean redimensionables» " +
                "(Opciones de desarrollador) salta esa regla, y Android la aplica sin reiniciar. NO hace falta Shizuku ni root: basta con activar a mano " +
                "«Permitir ventanas de forma libre» y esa opción. El botón de abajo solo lo automatiza, y para ello necesita un permiso concedido una vez (ADB).",
                12f, R.color.muted), lp(6))
            un.addView(rowWithSwitch("Desbloquear automáticamente", "Activa esos ajustes antes de abrir la ventana", Prefs.fwUnlock(this)) { Prefs.setFwUnlock(this, it) })
            un.addView(label(if (FreeformUnlock.unlocked(this)) "Estado: desbloqueadas ✔" else "Estado: bloqueadas (falta activarlo)", 12f, R.color.text), lp(6))
            un.addView(button("Desbloquear apps no redimensionables", !FreeformUnlock.unlocked(this)) {
                Thread {
                    val lg = StringBuilder()
                    val ok = FreeformUnlock.ensure(this, lg, true)
                    runOnUiThread {
                        render()
                        if (ok) toast("Apps no redimensionables desbloqueadas")
                        else {
                            try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", FreeformUnlock.adbCommand(this))) } catch (e: Exception) {}
                            dialog("Falta un permiso (una sola vez)",
                                "Ryx necesita el permiso WRITE_SECURE_SETTINGS para activarlo por ti. Elige una opción:\n\n" +
                                "• Con ADB, una sola vez (ya copié el comando al portapapeles):\n${FreeformUnlock.adbCommand(this)}\n\n" +
                                "• A mano: Opciones de desarrollador → «Permitir ventanas de forma libre» y «Forzar que las actividades sean redimensionables».")
                        }
                    }
                }.start()
            }, lp(8))
            un.addView(button("Abrir Opciones de desarrollador", false) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (e: Exception) { toast("No se pudo abrir") }
            }, lp(8))
            un.addView(button("Comprobar soporte", false) { dialog("Soporte de ventanas libres", FloatingWindow.support(this)) }, lp(8))

            val sz = titled(p, "Tamaño y posición")
            seekRow(sz, "Ancho", "%", 40, 100, Prefs.fwWidthPct(this)) { Prefs.setFwWidthPct(this, it) }
            seekRow(sz, "Alto", "%", 30, 90, Prefs.fwHeightPct(this)) { Prefs.setFwHeightPct(this, it) }
            sz.addView(label("Posición", 13f, R.color.muted, true), lp(12))
            sz.addView(segmented(listOf("Centro", "Arriba", "Abajo"), Prefs.fwPos(this), true) { Prefs.setFwPos(this, it) }, lp(6))
        }

        // ---------------- BARRA LATERAL ----------------

        val sb = titled(p, "Barra lateral", "Acceso rápido a tus apps desde el borde de la pantalla")
        sb.addView(rowWithSwitch("Usar barra lateral", "Muestra una tira vertical con tus apps fijadas al borde de la pantalla",
            Prefs.sidebarOn(this)) { Prefs.setSidebarOn(this, it) })
        sb.addView(rowWithSwitch("Abrir automáticamente", "Muestra la barra lateral cuando inicies una app seleccionada",
            Prefs.sidebarAuto(this)) { Prefs.setSidebarAuto(this, it) })
        sb.addView(segmented(listOf("Gesto", "Botón", "Ambos"), Prefs.sidebarCtrlMode(this), true) {
            Prefs.setSidebarCtrlMode(this, it)
        }, lp(8))
        sb.addView(rowWithSwitch("Mostrar sugerencias", "Añade apps sugeridas debajo de las fijadas",
            Prefs.sidebarSuggestions(this)) { Prefs.setSidebarSuggestions(this, it) })
        sb.addView(segmented(listOf("Izquierda", "Derecha"), Prefs.sidebarPos(this), true) {
            Prefs.setSidebarPos(this, it)
        }, lp(8))
        sb.addView(label("Tamaño del ícono: ${Prefs.sidebarIconDp(this)} dp", 12f, R.color.muted), lp(6))
        seekRow(sb, "Ícono", "dp", 28, 64, Prefs.sidebarIconDp(this)) { Prefs.setSidebarIconDp(this, it) }
        sb.addView(button("Apps fijadas", false) {
            pickSidebarApp()
        }, lp(8))
        sb.addView(button("Refrescar barra", false) {
            SidebarManager.refresh(this)
            toast("Barra lateral actualizada")
        }, lp(8))

        val t = titled(p, "Probar")
        val test = Prefs.fwTestPkg(this)
        val name = appList.firstOrNull { it.first == test }?.second ?: "Ninguna app elegida"
        t.addView(label("App de prueba: $name", 14f, R.color.text), lp(8))
        t.addView(button("Elegir app", false) { pickOneApp { pkg -> Prefs.setFwTestPkg(this, pkg); render() } }, lp(8))
        t.addView(button("▶ Probar ahora", true) {
            val key = Prefs.fwTestPkg(this)
            if (key.isEmpty()) { toast("Elige primero una app"); return@button }
            val go = {
                toast("Abriendo…")
                Thread { val log = openTestApp(key); runOnUiThread { render(); dialog("Resultado de la prueba", log) } }.start()
                Unit
            }
            if (!RyxAccessibility.isEnabled(this)) askAccessibility { go() } else go()
        }, lp(8))

        diagCard(p, "code", 0xFF6A62E8.toInt(), "Diagnóstico",
            "Estado real de la última apertura en ventana libre. Se muestra en detalle, sin llenar la pantalla.",
            Prefs.lastLog(this))
    }

    /** Lista compacta de permisos: nombre a la izquierda y estado (✔/✗) a la derecha, en filas finas. */
    private fun statusList(items: List<Pair<String, Boolean>>): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, (name, ok) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(this).apply {
                text = if (ok) "✔" else "○"; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (ok) Glass.ACCENT else Glass.MUTED)
                setPadding(0, 0, px(12), 0)
            })
            r.addView(label(name, 14f, R.color.text), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(label(if (ok) "Listo" else "Falta", 12f, if (ok) R.color.green else R.color.gold, true))
            box.addView(r, lp(if (i == 0) 2 else 9))
        }
        return box
    }

    private fun statusRow(parent: LinearLayout, name: String, value: String, ok: Boolean) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(label(name, 15f, R.color.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(label(value, 13f, if (ok) R.color.green else R.color.gold, true))
        parent.addView(r, lp(10))
    }

    private fun pickOneApp(onPick: (String) -> Unit) {
        val names = appList.map { it.second }.toTypedArray()
        glass(AlertDialog.Builder(this).setTitle("Elegir app")
            .setItems(names) { _, i -> onPick(appList[i].first) }
            .setNegativeButton("Cancelar", null).show())
    }

    /** Elige qué apps fijar en la barra lateral. */
    private fun pickSidebarApp() {
        val all = SidebarCatalog.all(this)
        val pinned = Prefs.sidebarPinned(this).toMutableSet()
        val builder = AlertDialog.Builder(this).setTitle("Apps fijadas en la barra lateral")
        val checked = BooleanArray(all.size) { pinned.contains(all[it].key()) }
        builder.setMultiChoiceItems(
            all.map { it.displayName() }.toTypedArray(),
            checked
        ) { _, i, isChecked ->
            if (isChecked) pinned.add(all[i].key()) else pinned.remove(all[i].key())
        }
        builder.setPositiveButton("Guardar") { _, _ ->
            Prefs.setSidebarPinned(this, pinned.toList().sortedBy { key ->
                all.indexOfFirst { it.key() == key }
            })
            SidebarManager.refresh(this)
            toast("Barra lateral actualizada")
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    /** Abre la app de prueba a partir de su clave («paquete» o «paquete@espacio» si es un clon). Fuera del hilo principal. */
    private fun openTestApp(key: String): String {
        val (pkg, uid) = Clones.splitKey(key)
        val clone = Clones.isClone(uid)
        return FloatingWindow.open(this, pkg, null, null, if (clone) Clones.handle(uid) else null, if (clone) uid else -1)
    }

    // ---------------- AJUSTES ----------------

    private fun buildSettings(p: LinearLayout) {
        pageHero(p, "settings", 0xFF2DAA55.toInt(), "Ajustes", "Permisos, copias y acerca de")
        heading(p, "APARIENCIA")
        item(p, "palette", 0xFF7B5CE0.toInt(), "Tema de la app", "Color de acento de la aplicación", Glass.theme.label) { themeDialog() }
        heading(p, "DATOS")
        item(p, "backup", 0xFF3F7BEA.toInt(), "Copia de seguridad",
            if (Prefs.backupAuto(this)) "Automática cada 30 s en «${Backup.FOLDER}»" else "Manual: exporta o importa ajustes",
            if (Prefs.backupLast(this) > 0) relative(Prefs.backupLast(this)) else "") { backupDialog() }
        item(p, "upload", 0xFF2D8CCB.toInt(), "Copia en la nube",
            "Solo si usas Google Drive u OneDrive", if (Backup.configured(this)) "Activa" else "Sin carpeta") { cloudDialog() }
        heading(p, "SISTEMA")
        item(p, "shield", 0xFF2DAA55.toInt(), "Permisos", "Superposición, acceso a notificaciones, batería", "") { subPage = "perms"; render() }
        item(p, "download", 0xFF2DAA55.toInt(), "Actualizaciones",
            "Ryx mira GitHub cada ${Prefs.updEverySec(this)} s y te avisa en la isla",
            "v${BuildConfig.VERSION_NAME}") { updateDialog() }
        item(p, "code", 0xFF5F6B80.toInt(), "Opciones de desarrollador", "Diagnóstico avanzado y pruebas internas", "") { devDialog() }
        item(p, "info", 0xFF5F6B80.toInt(), "Acerca de", "Versión y créditos", "") {
            dialog("Acerca de Ryx Island", "Ryx Island v${BuildConfig.VERSION_NAME}\nCreador: David Joel\n\nLa interfaz está inspirada en Smart Island, " +
                "de Animesh Gupta (licencia GPL-3.0): github.com/agupta07505/SmartIsland, y en Hyper Bridge.\n\n" +
                "Este código es propio y no copia el de esos proyectos. Si en el futuro se incorpora código ajeno, " +
                "hay que conservar su aviso de copyright y respetar su licencia.")
        }
    }

    /** Historial: tres secciones (Aplicaciones, En pantalla y Sistema), cada app/grupo cerrado hasta que se toca. */
    private fun buildHistory(p: LinearLayout) {
        pageHero(p, "history", 0xFF6A62E8.toInt(), "Historial", "Avisos, mensajes vistos en pantalla, media y llamadas, organizados por aplicación y por grupo del sistema")
        // Una sola lectura del JSON para toda la pantalla. Antes se repetía al contar, filtrar y dibujar.
        histCache = Prefs.histAll(this)
        val all = histCache ?: emptyList()
        val nScreen = all.count { !Sys.isSystemPkg(it.pkg) && it.src == Prefs.SRC_SCREEN }
        val nApps = all.count { !Sys.isSystemPkg(it.pkg) && it.src != Prefs.SRC_SCREEN }
        val nSys = all.count { Sys.isSystemPkg(it.pkg) }
        if (histFilter !in setOf("__apps__", "__screen__", "__sys__")) histFilter = "__apps__"

        val top = card()
        top.addView(rowWithSwitch("Guardar historial", "Todo lo que pasa por la isla, con el texto completo", Prefs.keepHistory(this)) { Prefs.setKeepHistory(this, it) })
        val sum = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0) }
        sum.addView(summaryChipCompact("grid", 0xFF6A62E8.toInt(), "$nApps", "Aplicaciones"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(3) })
        sum.addView(summaryChipCompact("screen", 0xFF35B6C8.toInt(), "$nScreen", "En pantalla"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
        sum.addView(summaryChipCompact("settings", 0xFF2DAA55.toInt(), "$nSys", "Sistema"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3) })
        top.addView(sum)
        top.addView(label("El historial conserva el aviso aunque la app elimine el mensaje después. Los mensajes largos se archivan completos y se muestran plegados en su propia tarjeta: pulsa «Leer más» para verlos enteros.", 12f, R.color.muted), lp(8))
        top.addView(label("En pantalla guarda lo que viste en WhatsApp, Telegram, Instagram, TikTok, TikTok Lite, Whee y otras apps de chat, con el texto completo y los recortes de fotos, stickers y GIF.", 12f, R.color.muted), lp(6))
        top.addView(button("Borrar historial", false) {
            Prefs.clearHist(this)
            histCache = null; histQuery = ""; histFilter = "__apps__"
            histExpandToken++; histSearchToken++
            clearHistExpansion()
            render()
        }, lp(10))
        p.addView(top, lp(8))

        // Tres secciones: avisos de apps, lo que se vio en pantalla y los grupos del sistema.
        val menu = card()
        menu.addView(label("Menú del historial", 16f, R.color.text, true))
        val selected = when (histFilter) {
            "__screen__" -> 1
            "__sys__" -> 2
            else -> 0
        }
        menu.addView(segmented(listOf("Aplicaciones", "En pantalla", "Sistema"), selected) { i ->
            histFilter = when (i) {
                1 -> "__screen__"
                2 -> "__sys__"
                else -> "__apps__"
            }
            // Cambiar de sección solo vuelve a dibujar la lista; no reconstruye toda la pantalla.
            fillHist()
        }, lp(10))
        menu.addView(label("En Aplicaciones cada app (y cada clon) tiene su propia tarjeta. En pantalla se agrupa lo que Ryx vio al abrir un chat, con sus mensajes y adjuntos. En Sistema pasa lo mismo con Batería, Wi-Fi, Red, Bluetooth… «Ver todo» abre todas las tarjetas y «Ver más» carga el resto de avisos de cada una.", 12f, R.color.muted), lp(8))
        p.addView(menu, lp(8))

        val search = card()
        search.addView(label("Buscar", 16f, R.color.text, true))
        search.addView(editText("Escribe para buscar en los mensajes…", histQuery) { q ->
            histQuery = q
            // Se espera a que dejes de teclear: reconstruir el historial en cada letra era parte del tirón.
            val token = ++histSearchToken
            histBox?.postDelayed({
                if (token == histSearchToken && histBox?.isAttachedToWindow == true) fillHist()
            }, 220)
        }, lp(8))
        search.addView(label("La búsqueda mira dentro de la sección elegida. Los mensajes largos se abren completos con el botón «Leer más».", 12f, R.color.muted), lp(8))
        search.addView(button("Descargar como .html", true) { exportHtml() }, lp(12))
        search.addView(label("Se descarga el texto completo y las imágenes en un archivo que abre cualquier navegador.",
            12f, R.color.muted), lp(6))
        p.addView(search, lp(8))

        // Registro de batería a tiempo real: una sola lectura, sin duplicar avisos
        batteryStrip(p)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histBox = box
        p.addView(box)
        fillHist()
    }

    /** Reconstruye solo la lista del historial (sin perder el cursor del buscador). */
    private fun fillHist() {
        val box = histBox ?: return
        histExpandToken++
        histSearchToken++
        // Cada búsqueda, cambio de sección o refresco vuelve a mostrar los grupos cerrados: así nunca
        // se reconstruyen de golpe todos los cuerpos que quedaron abiertos en la vista anterior.
        clearHistExpansion()
        box.removeAllViews()
        histGroupHandles.clear()
        val items = filteredHist()
        if (items.isEmpty()) {
            box.addView(label("No hay nada que coincida.", 14f, R.color.muted)
                .apply { setPadding(px(4), px(16), px(4), px(4)) })
            return
        }
        // Solo se dibuja la sección elegida. Cada grupo nace cerrado y sus filas se crean al abrirlo.
        when (histFilter) {
            "__sys__" -> {
                val color = 0xFF2DAA55.toInt()
                historyHeader(box, "settings", color, "Sistema", items.size)
                val toggle = historyTools(box, color, "Toca un grupo para ver sus avisos")
                items.groupBy { Sys.kind(it.pkg, it.title, it.text) }
                    .entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
                    .forEach { (k, its) -> sysHistoryGroup(box, k, its) }
                bindHistoryToggle(toggle)
            }
            "__screen__" -> {
                val color = 0xFF35B6C8.toInt()
                historyHeader(box, "screen", color, "En pantalla", items.size)
                val toggle = historyTools(box, color, "Toca una aplicación para ver lo que se vio en pantalla")
                items.groupBy { it.pkg }
                    .entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
                    .forEach { (pkg, its) -> appHistoryGroup(box, pkg, its, screen = true) }
                bindHistoryToggle(toggle)
            }
            else -> {
                val color = 0xFF6A62E8.toInt()
                historyHeader(box, "grid", color, "Aplicaciones", items.size)
                val toggle = historyTools(box, color, "Toca una aplicación para abrir su historial")
                items.groupBy { it.pkg }
                    .entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
                    .forEach { (pkg, its) -> appHistoryGroup(box, pkg, its) }
                bindHistoryToggle(toggle)
            }
        }
    }

    /** Olvida qué grupos del historial estaban abiertos (al cambiar de sección, buscar o refrescar). */
    private fun clearHistExpansion() {
        expandedGroups.removeAll {
            it.startsWith("h_") || it.startsWith("hs_") ||
                it.startsWith("\u0000close_h_") || it.startsWith("\u0000close_hs_")
        }
    }

    /** Mantiene el texto del botón en «Ver todo» o «Contraer todo» según lo que esté abierto. */
    private fun bindHistoryToggle(toggle: TextView) {
        val sync = {
            toggle.text = if (histGroupHandles.isNotEmpty() && histGroupHandles.all { it.isOpen() })
                "Contraer todo" else "Ver todo"
        }
        histGroupHandles.forEach { it.onToggle = { sync() } }
        sync()
    }

    /** Botón «Ver todo / Contraer todo» de la sección: abre los grupos ya dibujados, sin rehacer la pantalla. */
    private fun historyTools(p: LinearLayout, color: Int, hint: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(4), px(4), px(2))
        }
        row.addView(label(hint, 12f, R.color.muted), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(8) })
        val toggle = TextView(this).apply {
            text = "Ver todo"; textSize = 12.5f; gravity = Gravity.CENTER
            setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(px(14), px(8), px(14), px(8))
            background = Glass.ripple(this@MainActivity,
                Glass.pill(this@MainActivity, Glass.withA(color, 0x26), Glass.withA(color, 0x55), 15f), 15f)
        }
        toggle.setOnClickListener {
            // El propio texto dice qué hará: si ya está todo abierto, cierra; si no, abre lo que falte.
            val expand = toggle.text != "Contraer todo"
            histExpandToken++
            val token = histExpandToken
            if (!expand) {
                histGroupHandles.forEach { it.setOpen(false, false) }
                return@setOnClickListener
            }
            // Se abren en pequeños pasos para que la interfaz siga respondiendo aunque haya muchas apps.
            histGroupHandles.forEachIndexed { i, handle ->
                p.postDelayed({
                    if (token == histExpandToken && p.isAttachedToWindow) handle.setOpen(true, false)
                }, i * 20L)
            }
        }
        row.addView(toggle)
        p.addView(row, lp(6))
        return toggle
    }

    /** Lista completa del historial, cacheada durante este render. */
    private fun histItems(): List<Prefs.HistItem> =
        histCache ?: Prefs.histAll(this).also { histCache = it }

    /** Aplica la búsqueda y la sección actuales sobre el historial cacheado. */
    private fun filteredHist(): List<Prefs.HistItem> {
        val q = histQuery.trim()
        var items = histItems()
        items = when (histFilter) {
            "__sys__" -> items.filter { Sys.isSystemPkg(it.pkg) }
            "__screen__" -> items.filter { !Sys.isSystemPkg(it.pkg) && it.src == Prefs.SRC_SCREEN }
            else -> items.filter { !Sys.isSystemPkg(it.pkg) && it.src != Prefs.SRC_SCREEN }
        }
        if (q.isNotEmpty()) items = items.filter {
            it.title.contains(q, ignoreCase = true) || it.text.contains(q, ignoreCase = true)
        }
        return items
    }

    /** «Hoy», «Ayer» o la fecha. */
    private fun dayLabel(ts: Long): String {
        val cal = java.util.Calendar.getInstance()
        val d = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val yest = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(d, cal) -> "Hoy"
            sameDay(d, yest) -> "Ayer"
            d.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) ->
                java.text.SimpleDateFormat("EEEE d 'de' MMMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
            else -> java.text.SimpleDateFormat("d 'de' MMMM 'de' yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }

    private fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)

    /** Descarga el historial (con el filtro puesto) como un .html autónomo y presentable. */
    private fun exportHtml() {
        if (htmlBuilding) return
        val items = filteredHist()
        if (items.isEmpty()) { toast("No hay nada que descargar"); return }
        htmlBuilding = true
        toast("Preparando el archivo…")
        // El HTML incrusta imágenes en base64: leerlas y codificarlas en el hilo principal congelaba la app.
        Thread {
            val html = try { buildHtml(items) } catch (e: Throwable) { "" }
            runOnUiThread {
                htmlBuilding = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (html.isEmpty()) { toast("No se pudo preparar el historial"); return@runOnUiThread }
                pendingHtml = html
                val name = "ryx_historial_" +
                    java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.getDefault())
                        .format(java.util.Date()) + ".html"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/html"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try { startActivityForResult(i, REQ_HTML) }
                catch (e: Exception) { toast("No pude abrir el guardado de archivos") }
            }
        }.start()
    }

    /** Arma el HTML completo: todo el texto, agrupado por día y por app/sistema. */
    private fun buildHtml(items: List<Prefs.HistItem>): String {
        val screen = items.filter { !Sys.isSystemPkg(it.pkg) && it.src == Prefs.SRC_SCREEN }
        val apps = items.filter { !Sys.isSystemPkg(it.pkg) && it.src != Prefs.SRC_SCREEN }
        val sys = items.filter { Sys.isSystemPkg(it.pkg) }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Ryx Island · Historial</title><style>")
        sb.append("*{box-sizing:border-box}body{margin:0;padding:24px;background:#0f1115;color:#e9edf5;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;line-height:1.5}")
        sb.append("h1{font-size:22px;margin:0 0 4px}.sub{color:#94a0b4;font-size:13px;margin-bottom:18px}")
        sb.append("h2{font-size:16px;margin:28px 0 8px;padding-left:10px;border-left:4px solid #6a62e8}")
        sb.append("h2.sysc{border-color:#2daa55;color:#7ee2a0}.app{color:#b9b3ff}.screenc{border-color:#35b6c8;color:#8be4ef}")
        sb.append("h3{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:#94a0b4;margin:18px 0 6px}")
        sb.append(".day{font-weight:700;color:#ffb26b;font-size:14px;margin:16px 0 2px}")
        sb.append(".card{background:#181b22;border:1px solid #262b36;border-radius:14px;padding:10px 14px;margin:6px 0}")
        sb.append(".it{padding:9px 0;border-top:1px solid #222733}.it:first-child{border-top:0}")
        sb.append(".t{font-weight:700}.x{color:#c7cfdd;white-space:pre-wrap;word-break:break-word}")
        sb.append(".meta{color:#7f8b9e;font-size:11px;margin-top:4px}img{max-width:100%;border-radius:10px;margin-top:8px;display:block}")
        sb.append("</style></head><body>")
        sb.append("<h1>Ryx Island · Historial</h1><div class=\"sub\">")
        sb.append(esc("" + items.size + " avisos · " +
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
        sb.append("</div>")
        if (apps.isNotEmpty()) {
            sb.append("<h2 class=\"app\">Aplicaciones (").append(apps.size).append(")</h2>")
            apps.groupBy { dayLabel(it.ts) }.forEach { (day, dl) ->
                sb.append("<div class=\"day\">").append(esc(day)).append("</div>")
                dl.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.forEach { (pkg, its) ->
                    sb.append("<h3>").append(esc(appNameOf(pkg))).append("</h3><div class=\"card\">")
                    its.sortedByDescending { it.ts }.forEach { h -> sb.append(htmlItem(h)) }
                    sb.append("</div>")
                }
            }
        }
        if (screen.isNotEmpty()) {
            sb.append("<h2 class=\"screenc\">En pantalla (").append(screen.size).append(")</h2>")
            screen.groupBy { dayLabel(it.ts) }.forEach { (day, dl) ->
                sb.append("<div class=\"day\">").append(esc(day)).append("</div>")
                dl.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.forEach { (pkg, its) ->
                    sb.append("<h3>").append(esc(appNameOf(pkg))).append("</h3><div class=\"card\">")
                    its.sortedByDescending { it.ts }.forEach { h -> sb.append(htmlItem(h)) }
                    sb.append("</div>")
                }
            }
        }
        if (sys.isNotEmpty()) {
            sb.append("<h2 class=\"sysc\">Sistema (").append(sys.size).append(")</h2>")
            sys.groupBy { dayLabel(it.ts) }.forEach { (day, dl) ->
                sb.append("<div class=\"day\">").append(esc(day)).append("</div>")
                dl.groupBy { Sys.kind(it.pkg, it.title, it.text) }
                    .entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.forEach { (k, its) ->
                        sb.append("<h3>").append(esc(Sys.label(k))).append("</h3><div class=\"card\">")
                        its.sortedByDescending { it.ts }.forEach { h -> sb.append(htmlItem(h)) }
                        sb.append("</div>")
                    }
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun htmlItem(h: Prefs.HistItem): String {
        val sb = StringBuilder("<div class=\"it\">")
        if (h.title.isNotEmpty()) sb.append("<div class=\"t\">").append(esc(h.title)).append("</div>")
        // La exportación también usa la base: si el texto visible venía cortado, se escribe el completo.
        val full = MsgBase.fullText(this, h)
        if (full.isNotEmpty()) sb.append("<div class=\"x\">").append(esc(full)).append("</div>")
        MsgBase.mediaFiles(this, h).forEach { f ->
            if (f.exists() && f.length() < 1_200_000) try {
                val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                // El tipo real del archivo (JPEG o PNG), no siempre PNG: los stickers se guardan en PNG y el
                // resto en JPEG. Si se declara mal, el navegador no muestra la imagen.
                val mime = if (esPng(f)) "image/png" else "image/jpeg"
                sb.append("<img src=\"data:").append(mime).append(";base64,").append(b64).append("\">")
            } catch (e: Exception) {}
        }
        sb.append("<div class=\"meta\">")
        if (h.kind.isNotEmpty()) sb.append(esc(h.kind)).append(" · ")
        if (h.src == Prefs.SRC_SCREEN) sb.append("En pantalla · ")
        sb.append(esc(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.ts))))
        sb.append("</div></div>")
        return sb.toString()
    }

    /** Nombre de una app del historial. La clave puede ser «pkg@usuario» para un clon. */
    private fun appNameOf(key: String): String {
        val (pkg, uid) = Clones.splitKey(key)
        return Clones.label(this, pkg, uid)
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** ¿El archivo es un PNG? (se mira su firma real, no la extensión: se guardan en .img). */
    private fun esPng(f: java.io.File): Boolean = try {
        val head = ByteArray(8)
        java.io.FileInputStream(f).use { it.read(head) }
        head.size == 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
    } catch (e: Exception) { false }

    /**
     * Descodifica una imagen reduciéndola al tamaño en el que se va a mostrar. Se leen primero las
     * dimensiones y se calcula `inSampleSize`, en vez de cargar el JPEG completo (varias veces la pantalla).
     * Llamar fuera del hilo principal.
     */
    private fun decodeSampled(path: String, maxW: Int, maxH: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            // inSampleSize solo divide entre potencias de 2: se ajusta al tamaño EXACTO para no retener
            // imágenes de 2000 px en filas del historial (con 25 filas se agotaba la memoria).
            if (bmp.width > maxW || bmp.height > maxH) {
                val f = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
                android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1),
                    (bmp.height * f).toInt().coerceAtLeast(1), true)
            } else bmp
        } catch (e: Throwable) { null }
    }

    /** Encabezado de sección del historial, con icono, título y número de avisos. */
    private fun historyHeader(p: LinearLayout, iconName: String, color: Int, title: String, count: Int) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(18), px(4), px(6))
        }
        r.addView(tileIcon(iconName, (color and 0x00FFFFFF) or 0x33000000, color),
            LinearLayout.LayoutParams(px(34), px(34)).apply { marginEnd = px(10) })
        r.addView(label(title, 16f, R.color.text, true).apply { letterSpacing = 0.06f },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(TextView(this).apply {
            text = "$count"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(px(12), px(5), px(12), px(5))
            background = Glass.pill(this@MainActivity, Glass.withA(color, 0x26), 0, 12f)
        })
        p.addView(r)
    }

    /** Recuadro resumen (número + etiqueta) con color propio. */
    private fun summaryChip(iconName: String, color: Int, value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(12), px(12), px(12), px(12))
        background = Glass.pill(this@MainActivity, Glass.SURFACE_HI, Glass.HAIR, 16f)
        addView(tileIcon(iconName, Glass.withA(color, 0x2E), color), LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(10) })
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(value, 18f, R.color.text, true))
        t.addView(label(caption, 12f, R.color.muted))
        addView(t)
    }

    /** Versión compacta para que las tres secciones del historial quepan en una fila. */
    private fun summaryChipCompact(iconName: String, color: Int, value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(9), px(8), px(9))
        background = Glass.pill(this@MainActivity, Glass.SURFACE_HI, Glass.HAIR, 14f)
        addView(tileIcon(iconName, Glass.withA(color, 0x2E), color), LinearLayout.LayoutParams(px(27), px(27)).apply { marginEnd = px(6) })
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(value, 15f, R.color.text, true))
        t.addView(label(caption, 9.5f, R.color.muted).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** Batería en tiempo real: nivel actual y estado de carga, leído del sistema. */
    private fun batteryStrip(p: LinearLayout) {
        val b = Battery.now(this)
        if (b.pct < 0) return
        val c = card()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon("battery", 0x3330D158, 0xFF30D158.toInt()),
            LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label("Batería en tiempo real", 16f, R.color.text, true))
        t.addView(label("${b.pct}% · ${b.label}", 13f, R.color.muted))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(r)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.pill(this@MainActivity, Glass.SURFACE_TOP, 0, 10f)
            setPadding(px(1), px(1), px(1), px(1))
        }
        val pctF = b.pct.coerceIn(1, 100).toFloat()
        bar.addView(View(this).apply { background = Glass.pill(this@MainActivity, 0xFF30D158.toInt(), 0, 10f) },
            LinearLayout.LayoutParams(0, px(9), pctF))
        bar.addView(View(this), LinearLayout.LayoutParams(0, px(9), (100f - pctF).coerceAtLeast(0.01f)))
        c.addView(bar, lp(10))
        c.addView(label("Se lee directamente del sistema: los avisos de batería no se repiten.", 12f, R.color.muted), lp(8))
        p.addView(c, lp(10))
    }

    /** Grupo de historial de una aplicación (con su icono), cerrado hasta que se toca. */
    private fun appHistoryGroup(p: LinearLayout, pkg: String, items: List<Prefs.HistItem>, screen: Boolean = false) {
        // pkg puede ser una clave de clon («com.whatsapp@999»): appIcon ya la entiende y el nombre
        // sale con la insignia de la ROM («WhatsApp · clon»).
        val appName = appNameOf(pkg)
        val sorted = items.sortedByDescending { it.ts }
        val color = if (screen) 0xFF35B6C8.toInt() else 0xFF6A62E8.toInt()
        val what = if (screen) {
            if (sorted.size == 1) "1 vista en pantalla" else "${sorted.size} vistas en pantalla"
        } else {
            if (sorted.size == 1) "1 notificación" else "${sorted.size} notificaciones"
        }
        val handle = collapsibleCard(p, (if (screen) "h_screen_" else "h_") + pkg, false, head = { h ->
            h.addView(ShapedImageView(this).apply {
                shape = Prefs.iconShape(this@MainActivity); setImageDrawable(appIcon(pkg)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(appName, 17f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            t.addView(label(what, 12f, R.color.muted))
            h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, body = { b ->
            historyGroupBody(b, sorted, px(54), color)
        }, lazyBody = true)
        histGroupHandles.add(handle)
    }

    /** Grupo de historial del sistema (Batería, Wi-Fi, Red…), cerrado hasta que se toca. */
    private fun sysHistoryGroup(p: LinearLayout, kind: String, items: List<Prefs.HistItem>) {
        val color = Sys.color(kind)
        val sorted = items.sortedByDescending { it.ts }
        val handle = collapsibleCard(p, "hs_$kind", false, head = { h ->
            h.addView(tileIcon(Sys.symbol(kind), (color and 0x00FFFFFF) or 0x33000000, color),
                LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(Sys.label(kind), 17f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            t.addView(label("${sorted.size} avisos del sistema", 12f, R.color.muted))
            h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, body = { b ->
            historyGroupBody(b, sorted, px(54), color)
        }, lazyBody = true)
        histGroupHandles.add(handle)
    }

    /**
     * Filas de un grupo. Se añaden de 12 en 12: abrir una app con cientos de avisos no crea cientos
     * de vistas de golpe, y aun así se puede llegar a todas con «Ver más».
     */
    private fun historyGroupBody(b: LinearLayout, items: List<Prefs.HistItem>, indent: Int, color: Int) {
        b.setPadding(0, px(2), 0, 0)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b.addView(rows)
        val more = TextView(this).apply {
            textSize = 12.5f; gravity = Gravity.CENTER
            setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(px(12), px(9), px(12), px(9))
            background = Glass.ripple(this@MainActivity,
                Glass.pill(this@MainActivity, Glass.withA(color, 0x1C), Glass.withA(color, 0x3A), 15f), 15f)
            visibility = View.GONE
        }
        var shown = 0
        fun addPage() {
            val end = minOf(shown + 12, items.size)
            for (i in shown until end) {
                val item = items[i]
                // Un «testamento» no cabe en una fila normal: se dibuja como tarjeta propia, plegada y con «Leer más».
                if (isLongHistory(item)) rows.addView(historyLongCard(item, indent, color))
                else rows.addView(historyItemRow(item, indent))
            }
            shown = end
            val left = items.size - shown
            if (left > 0) {
                more.text = if (left == 1) "Ver más · 1 restante" else "Ver más · $left restantes"
                more.visibility = View.VISIBLE
            } else more.visibility = View.GONE
        }
        more.setOnClickListener { addPage() }
        b.addView(more, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(4) })
        addPage()
    }

    /** Una entrada del historial: título, texto COMPLETO, imagen (si hay), tipo y hora. */
    private fun historyItemRow(h2: Prefs.HistItem, indent: Int): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(indent, px(10), 0, 0) }
        // Siempre se usa la copia más larga: el texto visible pudo llegar cortado, pero el .bin o el .b64
        // conservan el mensaje completo.
        val shownText = MsgBase.fullText(this, h2)
        if (h2.title.isNotEmpty()) r.addView(label(h2.title, 14f, R.color.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (shownText.isNotEmpty()) r.addView(label(shownText, 13f, R.color.muted).apply {
            // El historial guardaba el texto completo pero la vista lo cortaba a 2 líneas. Ahora se ve
            // entero; el grupo se puede cerrar y las filas se crean solo al abrirlo.
            setLineSpacing(0f, 1.08f)
        })
        historyImage(h2, indent, px(300))?.let { r.addView(it) }
        if (MsgBase.mediaFiles(this, h2).isEmpty() && isMediaKind(h2.kind)) {
            // Se conoce el TIPO del adjunto pero no hay píxeles: la app no compartió la vista previa en el
            // aviso (o la entrada se capturó leyendo la pantalla). Se dice claro para que no parezca un fallo.
            r.addView(label("📎 ${h2.kind} · el aviso no trajo la vista previa",
                11f, R.color.gold).apply { setPadding(0, px(4), 0, 0) })
        }
        historyCopyRow(h2, Glass.ACCENT)?.let { r.addView(it, lp(6)) }
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(2), 0, 0) }
        if (h2.src == Prefs.SRC_SCREEN) {
            foot.addView(label("En pantalla", 11f, 0xFF35B6C8.toInt(), true))
            foot.addView(View(this), LinearLayout.LayoutParams(px(8), px(1)))
        }
        if (h2.kind.isNotEmpty()) {
            foot.addView(label(h2.kind, 11f, R.color.gold, true))
            foot.addView(View(this), LinearLayout.LayoutParams(px(8), px(1)))
        }
        foot.addView(label(relative(h2.ts), 11f, R.color.gold))
        r.addView(foot)
        return r
    }

    /** ¿El texto es un «testamento»? Se le da tarjeta propia y altura libre. */
    private fun isLongHistory(h: Prefs.HistItem): Boolean {
        val text = MsgBase.fullText(this, h)
        return text.length > 320 || text.count { it == '\n' } >= 6 ||
            (text.isBlank() && (h.b64.isNotEmpty() || h.bin.isNotEmpty() || h.b64f.isNotEmpty()))
    }

    private fun isMediaKind(kind: String): Boolean =
        kind.startsWith("Imagen") || kind.startsWith("Foto") || kind.startsWith("Vídeo") ||
            kind.startsWith("Video") || kind.startsWith("Sticker") || kind.startsWith("GIF") ||
            kind.startsWith("Animación") || kind.startsWith("Audio") ||
            kind.startsWith("Documento") || kind.startsWith("Ver una vez")

    /**
     * Acciones de copia de una entrada. El texto se toma de la base: se queda con la copia más larga
     * entre el texto visible, el archivo binario y el Base64. Las imágenes/stickers se publican como
     * imagen normal para poder pegarlas en WhatsApp, Telegram o cualquier app.
     */
    private fun historyCopyRow(h2: Prefs.HistItem, color: Int): View? {
        val hasText = h2.text.isNotEmpty() || h2.b64.isNotEmpty() || h2.bin.isNotEmpty() || h2.b64f.isNotEmpty()
        val hasMedia = MsgBase.mediaFile(this, h2) != null
        if (!hasText && !hasMedia) return null
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, mode: Int) {
            val v = copyChip(label, color) { copyHist(h2, mode) }
            row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = px(6) })
        }
        if (hasText) chip("Copiar mensaje", 0)
        if (hasMedia) {
            chip(if (h2.kind.contains("Sticker", ignoreCase = true)) "Copiar sticker" else "Copiar imagen", 3)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun copyChip(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(12), px(8), px(12), px(8))
        background = Glass.ripple(this@MainActivity,
            Glass.pill(this@MainActivity, Glass.withA(color, 0x18), Glass.withA(color, 0x3A), 14f), 14f)
        setOnClickListener { onClick() }
    }

    private fun copyHist(h2: Prefs.HistItem, mode: Int) {
        when (mode) {
            0 -> {
                val text = MsgBase.fullText(this, h2)
                if (text.isEmpty()) { toast("No hay texto que copiar"); return }
                if (Clip.text(this, "Ryx", text)) toast("Mensaje completo copiado (${text.length} caracteres)")
                else toast("No se pudo copiar el mensaje")
            }
            3 -> {
                val f = MsgBase.mediaFile(this, h2)
                if (f == null) { toast("Esta entrada no tiene imagen ni sticker"); return }
                if (Clip.image(this, f, h2.kind)) toast("Imagen copiada: pégala en el chat")
                else toast("No se pudo copiar la imagen")
            }
        }
    }

    /**
     * Imágenes de una entrada del historial. Una captura de «En pantalla» puede contener varias:
     * se muestran en columna, conservando la animación de los GIF/WebP que sí llegaron completos.
     */
    private fun historyImage(h2: Prefs.HistItem, indent: Int, maxHeightPx: Int): View? {
        val files = MsgBase.mediaFiles(this, h2)
        if (files.isEmpty()) return null
        if (files.size == 1) return historySingleImage(h2, files[0], indent, maxHeightPx)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val eachH = minOf(maxHeightPx, px(220))
        files.take(4).forEach { f ->
            historySingleImage(h2, f, indent, eachH)?.let { box.addView(it) }
        }
        return box
    }

    /**
     * Una imagen grande, descodificada fuera del hilo principal.
     *
     * La vista se crea al instante con un marcador; la imagen entra con un fundido/zoom suave. Los GIF y
     * WebP animados se arrancan solo mientras la vista está en pantalla, y llevan una etiqueta visible.
     * Los stickers conservan su transparencia y se muestran enteros, sin recortes.
     */
    private fun historySingleImage(h2: Prefs.HistItem, f: java.io.File, indent: Int, maxHeightPx: Int): View? {
        if (!f.exists()) return null
        val sticker = h2.kind.contains("Sticker", ignoreCase = true)
        val gif = h2.kind.contains("GIF", ignoreCase = true) ||
            h2.kind.contains("Animación", ignoreCase = true) ||
            h2.kind.contains("Animacion", ignoreCase = true)
        // Se ve GRANDE (todo el ancho de la fila), no como miniatura: así se mira sin abrirla.
        val maxW = (resources.displayMetrics.widthPixels - indent - px(16)).coerceAtLeast(px(160))
        val maxH = maxHeightPx
        val frame = FrameLayout(this).apply {
            background = Glass.tile(this@MainActivity, Glass.SURFACE_TOP, 16f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, px(16).toFloat()) }
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = if (sticker) "Sticker; toca para ampliar" else "Imagen; toca para ampliar"
            setOnClickListener { showImage(f.absolutePath) }
            setOnLongClickListener {
                if (Clip.image(this@MainActivity, f, h2.kind)) toast("Imagen copiada: pégala en el chat")
                else toast("No se pudo copiar la imagen")
                true
            }
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            minimumHeight = px(96)
            maxHeight = maxH
            setPadding(if (sticker) px(12) else 0, if (sticker) px(12) else 0,
                if (sticker) px(12) else 0, if (sticker) px(12) else 0)
            alpha = 0f
            contentDescription = if (sticker) "Sticker" else "Imagen"
        }
        frame.addView(iv, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val tone = if (sticker) 0xFFFFC857.toInt() else 0xFF35B6C8.toInt()
        val badge = TextView(this).apply {
            textSize = 9.5f; letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(tone)
            setPadding(px(8), px(3), px(8), px(3))
            background = Glass.pill(this@MainActivity, 0xCC10141C.toInt(), Glass.withA(tone, 0x66), 10f)
            visibility = when {
                sticker -> View.VISIBLE
                gif -> View.VISIBLE
                else -> View.GONE
            }
            text = when {
                sticker -> "STICKER"
                gif -> "GIF"
                else -> ""
            }
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            topMargin = px(8); marginEnd = px(8)
        })
        try {
            imgWork.execute {
                // ImageDecoder conserva los GIF/WebP animados; el bitmap de antes solo mostraba el
                // primer fotograma y por eso los stickers animados parecían imágenes rotas.
                val art = MediaArt.fromFile(this, f, maxOf(maxW, maxH).coerceAtMost(1400))
                val bmp = if (art == null) decodeSampled(f.absolutePath, maxW, maxH) else null
                val animatedFile = MediaArt.isAnimatedFile(f)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (art != null) {
                            iv.setImageDrawable(art)
                            if (iv.isAttachedToWindow) MediaArt.start(art)
                            if (!sticker && !MediaArt.isAnimated(art)) frame.background = null
                        } else if (bmp != null) {
                            iv.setImageBitmap(bmp)
                            if (!sticker) frame.background = null
                        } else {
                            badge.text = "NO SE PUDO CARGAR"
                            badge.visibility = View.VISIBLE
                            badge.setTextColor(0xFFFFB26B.toInt())
                            return@runOnUiThread
                        }
                        if ((animatedFile || MediaArt.isAnimated(art)) && !sticker) {
                            badge.text = "ANIM"
                            badge.visibility = View.VISIBLE
                        }
                        iv.alpha = 0f
                        iv.scaleX = 0.985f
                        iv.scaleY = 0.985f
                        iv.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(220)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                }
            }
        } catch (e: Throwable) {}
        iv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { MediaArt.start((v as ImageView).drawable) }
            override fun onViewDetachedFromWindow(v: View) { MediaArt.stop((v as ImageView).drawable) }
        })
        return frame.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
        }
    }

    /**
     * Mensaje largo en su propia tarjeta. Se guarda completo y se muestra plegado a [HISTORY_PREVIEW_LINES]
     * líneas; «Leer más» lo despliega entero. La imagen conserva un tope aparte para no agotar memoria.
     */
    private fun historyLongCard(h2: Prefs.HistItem, indent: Int, color: Int): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(indent, px(12), 0, px(2))
        }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(13), px(14), px(12))
            background = Glass.ripple(this@MainActivity,
                Glass.pill(this@MainActivity, Glass.SURFACE_HI, Glass.withA(color, 0x66), 18f), 18f)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "MENSAJE LARGO"; textSize = 10f; letterSpacing = 0.08f
            setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(px(10), px(5), px(10), px(5))
            background = Glass.pill(this@MainActivity, Glass.withA(color, 0x22), 0, 11f)
        })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(label(relative(h2.ts), 11f, R.color.muted))
        c.addView(head)
        if (h2.title.isNotEmpty()) c.addView(label(h2.title, 15f, R.color.text, true).apply {
            setPadding(0, px(9), 0, 0)
        })
        // La tarjeta larga muestra la versión más larga disponible, no el texto truncado del aviso.
        // Se abre plegada para que un «testamento» no ocupe media pantalla; «Leer más» la despliega.
        val shownText = MsgBase.fullText(this, h2)
        if (shownText.isNotEmpty()) {
            val textView = label(shownText, 13.5f, R.color.text).apply {
                setTextIsSelectable(true)
                setLineSpacing(px(2).toFloat(), 1.08f)
                setPadding(0, px(8), 0, px(4))
                maxLines = HISTORY_PREVIEW_LINES
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            c.addView(textView)
            if (shownText.length > 280 || shownText.count { it == '\n' } >= HISTORY_PREVIEW_LINES) {
                var expanded = false
                val more = copyChip("Leer más · ${shownText.length} caracteres", color) {}
                more.setOnClickListener {
                    expanded = !expanded
                    textView.maxLines = if (expanded) Int.MAX_VALUE else HISTORY_PREVIEW_LINES
                    textView.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                    more.text = if (expanded) "Leer menos" else "Leer más · ${shownText.length} caracteres"
                    if (!expanded) textView.scrollTo(0, 0)
                }
                c.addView(more, lp(6))
            }
        }
        historyImage(h2, indent, px(420))?.let { c.addView(it) }
        if (MsgBase.mediaFiles(this, h2).isEmpty() && isMediaKind(h2.kind)) {
            c.addView(label("📎 ${h2.kind} · el aviso no trajo la vista previa", 11f, R.color.gold)
                .apply { setPadding(0, px(4), 0, 0) })
        }
        historyCopyRow(h2, color)?.let { c.addView(it, lp(6)) }
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (h2.src == Prefs.SRC_SCREEN) {
            foot.addView(label("En pantalla", 11f, 0xFF35B6C8.toInt(), true))
            foot.addView(View(this), LinearLayout.LayoutParams(px(8), px(1)))
        }
        if (h2.kind.isNotEmpty()) {
            foot.addView(label(h2.kind, 11f, R.color.gold, true))
            foot.addView(View(this), LinearLayout.LayoutParams(px(8), px(1)))
        }
        foot.addView(label("Texto completo · ${shownText.length} caracteres", 11f, R.color.muted))
        c.addView(foot, lp(8))
        outer.addView(c)
        return outer
    }

    private fun relative(ts: Long): String = android.text.format.DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

    private fun showImage(path: String) {
        // Se descodifica a la resolución de la pantalla, en segundo plano: una foto grande ya no bloquea la app
        // ni puede agotar la memoria al abrirla a tamaño completo.
        val m = resources.displayMetrics
        val maxW = m.widthPixels.coerceAtMost(1600)
        val maxH = m.heightPixels.coerceAtMost(2000)
        try {
            imgWork.execute {
                val art = MediaArt.fromFile(this, java.io.File(path), maxOf(maxW, maxH))
                val bmp = if (art == null) decodeSampled(path, maxW, maxH) else null
                runOnUiThread {
                    if ((art == null && bmp == null) || isFinishing || isDestroyed) return@runOnUiThread
                    // Visor a pantalla completa con zoom: pellizco, doble toque y arrastre. Los GIF/WebP
                    // siguen animándose; la imagen se cierra con el botón ✕ o tocándola sin zoom.
                    val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
                    val viewer = ZoomImageView(this).apply {
                        if (art != null) { setImageDrawable(art); MediaArt.start(art) }
                        else setImageBitmap(bmp)
                        setBackgroundColor(0xFF000000.toInt())
                        contentDescription = "Imagen ampliada; pellizca para acercar"
                        alpha = 0f
                        setOnClickListener { dlg.dismiss() }
                    }
                    root.addView(viewer, FrameLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
                    val close = TextView(this).apply {
                        text = "✕"; textSize = 18f; gravity = Gravity.CENTER
                        setTextColor(0xFFFFFFFF.toInt())
                        background = Glass.pill(this@MainActivity, 0x99000000.toInt(), 0x55FFFFFF, 22f)
                        contentDescription = "Cerrar imagen"
                        setOnClickListener { dlg.dismiss() }
                    }
                    root.addView(close, FrameLayout.LayoutParams(px(44), px(44), Gravity.TOP or Gravity.END).apply {
                        topMargin = px(24); marginEnd = px(16)
                    })
                    val copy = copyChip("Copiar imagen", 0xFF35B6C8.toInt()) {
                        if (Clip.image(this, java.io.File(path), "")) toast("Imagen copiada: pégala en el chat")
                        else toast("No se pudo copiar la imagen")
                    }
                    root.addView(copy, FrameLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(28) })
                    dlg.setContentView(root)
                    dlg.setOnDismissListener { MediaArt.stop(viewer.drawable) }
                    dlg.window?.apply {
                        setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
                        setDimAmount(0f)
                        setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT)
                    }
                    dlg.show()
                    viewer.animate().alpha(1f).setDuration(200).start()
                }
            }
        } catch (e: Throwable) {}
    }

    private fun buildPerms(p: LinearLayout) {
        p.addView(maxAccessCard(), lp(8))
        val c = card()
        permsContent(c)
        p.addView(c, lp(8))
    }

    /**
     * Poder máximo real de una app normal: notificaciones + accesibilidad + superposición + uso +
     * batería + permisos runtime. Sin root y sin Shizuku no existe poder absoluto: Android siempre
     * exige que el usuario confirme los accesos especiales. Esta tarjeta los reúne y guía uno a uno.
     */
    private fun maxAccessCard(): View {
        val c = card()
        c.addView(label("Poder máximo", 18f, R.color.text, true))
        c.addView(label("Sin root y sin Shizuku. Ryx usa todo lo que Android permite a una app normal: " +
            "avisos, accesibilidad, superposición, uso, batería y permisos de llamadas, contactos y fotos.", 12f, R.color.muted), lp(4))
        val states = listOf(
            "Avisos" to notifEnabled(),
            "Encima" to Settings.canDrawOverlays(this),
            "A11y" to RyxAccessibility.isEnabled(this),
            "Uso" to Foreground.hasAccess(this),
            "Batería" to batteryFree(),
            "Permisos" to runtimeMissing().isEmpty())
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val (name, ok) = states[r * 3 + i]
                line.addView(accessChip(name, ok), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    marginEnd = px(4)
                    topMargin = px(4)
                })
            }
            grid.addView(line)
        }
        c.addView(grid, lp(6))
        val granted = states.count { it.second }
        c.addView(label("$granted de ${states.size} accesos concedidos", 12f,
            if (granted == states.size) R.color.green else R.color.gold, true), lp(8))
        c.addView(button(if (granted == states.size) "Revisar accesos" else "Activar todo lo posible",
            granted != states.size) { requestMaxAccess() }, lp(10))
        c.addView(rowWithSwitch("Modo máximo de Ryx",
            "Historial completo, lectura de chats, fotos de la carpeta, isla permanente y sin restricciones de batería",
            Prefs.maxMode(this)) { v -> applyMaxMode(v) }, lp(4))
        c.addView(label("Límite honesto: sin root no se puede tener poder absoluto. Android obliga a confirmar " +
            "cada acceso especial en sus Ajustes; Ryx no puede saltarse esa confirmación. Esto es el máximo real.", 11f,
            R.color.muted), lp(8))
        return c
    }

    private fun accessChip(name: String, ok: Boolean) = TextView(this).apply {
        text = (if (ok) "✓ " else "• ") + name
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(if (ok) 0xFF3DDC84.toInt() else 0xFFF2A01F.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(6), px(9), px(6), px(9))
        background = Glass.pill(this@MainActivity,
            if (ok) 0x243DDC84 else 0x24F2A01F,
            if (ok) 0x663DDC84 else 0x66F2A01F, 13f)
    }

    private fun batteryFree(): Boolean = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) { false }

    /** Permisos runtime que faltan para el modo máximo. No incluye los especiales de Ajustes. */
    private fun runtimeMissing(): List<String> {
        val out = ArrayList<String>()
        fun need(p: String) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p)
        }
        if (Build.VERSION.SDK_INT >= 33) need("android.permission.POST_NOTIFICATIONS")
        need(android.Manifest.permission.READ_CONTACTS)
        need(android.Manifest.permission.READ_CALL_LOG)
        need(android.Manifest.permission.ANSWER_PHONE_CALLS)
        need(android.Manifest.permission.CALL_PHONE)
        need(android.Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) need("android.permission.BLUETOOTH_CONNECT")
        if (!imagePermOk()) imagePerms().forEach { need(it) }
        return out.distinct()
    }

    private fun requestMaxAccess() {
        val missing = runtimeMissing()
        if (missing.isNotEmpty()) {
            try {
                requestPermissions(missing.toTypedArray(), 12)
                toast("Concede los permisos que Android muestra; después seguimos con los accesos especiales")
                return
            } catch (t: Throwable) {}
        }
        openNextMaxAccess()
    }

    /** Abre el siguiente acceso especial que falte. Todos se confirman a mano en Ajustes. */
    private fun openNextMaxAccess() {
        when {
            !notifEnabled() -> {
                toast("Activa Ryx en «Acceso a notificaciones»")
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            !Settings.canDrawOverlays(this) -> {
                toast("Permite «Mostrar sobre otras apps»")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            !RyxAccessibility.isEnabled(this) -> askAccessibility()
            !Foreground.hasAccess(this) -> {
                toast("Activa Ryx en «Acceso de uso»")
                askUsageAccess()
            }
            !batteryFree() -> {
                toast("Quita la optimización de batería")
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) {}
                }
            }
            else -> {
                applyMaxMode(true)
                toast("Poder máximo activado: ya están todos los accesos que Android permite")
                render()
            }
        }
    }

    /** Enciende las funciones que dependen de esos accesos. Al apagarlo no se revierte lo ya concedido. */
    private fun applyMaxMode(v: Boolean) {
        Prefs.setMaxMode(this, v)
        if (!v) return
        Prefs.setKeepHistory(this, true)
        Prefs.setAutoRead(this, true)
        Prefs.setIslandOn(this, true)
        Prefs.setPermanent(this, true)
        Prefs.setChatCapture(this, true)
        Prefs.setChatMedia(this, true)
        Prefs.setChatShots(this, true)
        Prefs.setMediaFolder(this, true)
        if (!imagePermOk()) askImagePermission()
        applyAll()
    }

    private fun permsContent(b: LinearLayout) {
        permRow(b, "Shizuku (solo para ocultar avisos emergentes)", "Opcional. Estado: ${Shell.shizukuStatus(this)}. El formato libre del sistema no lo necesita.",
            Shell.shizukuGranted()) {
            when {
                !Shell.shizukuInstalled(this) -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                !Shell.shizukuRunning() -> packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)?.let { startActivity(it) }
                else -> Shell.requestShizuku()
            }
        }
        if (hasAppsPerm()) permRow(b, "Lista de apps (HyperOS)", "Sin esto no se ven los iconos ni los nombres de las apps",
            appsPermOk()) {
            try { requestPermissions(arrayOf(appsPerm), 5) } catch (e: Throwable) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        permRow(b, "Mostrar sobre otras apps", "Permite que la isla flote sobre la barra de estado y la cámara",
            Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow(b, "Acceso a notificaciones", "Lee mensajes, llamadas y alertas, y las registra", notifEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow(b, "Accesibilidad (leer chats en pantalla)",
            if (!RyxAccessibility.isEnabled(this)) "Para registrar los chats ARCHIVADOS o RESTRINGIDOS que abras (no mandan notificación)"
            else if (Prefs.chatCapture(this)) "Activada la lectura de chats en pantalla"
            else "Accesibilidad lista. Actívala abajo en «Chats en pantalla»",
            RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) chatCaptureDialog() else askAccessibility()
        }
        // ---- Llamadas ----
        // El sistema SIEMPRE tiene una app de llamadas; Ryx la detecta y usa la predeterminada (o la que elijas).
        val dialerName = Calls.chosenLabel(this)
        val dialerOk = Calls.hasDialer(this)
        permRow(b, "App de llamadas", "Usando: $dialerName. Elige cuál usa Ryx al abrir una llamada",
            dialerOk) { callsDialog() }
        val callGranted = Calls.canReadContacts(this) && Calls.canReadCallLog(this) &&
            Calls.canAnswer(this) && Calls.canCall(this)
        permRow(b, "Permisos de llamadas y contactos",
            "Para contestar/colgar, abrir el marcador, ver el registro y detectar tus contactos favoritos (prioritarios)",
            callGranted) {
            try { requestPermissions(Calls.runtimePerms(), 7) } catch (e: Throwable) { Calls.openAppPerms(this) }
        }
        permRow(b, "Contactos favoritos (prioritarios)",
            if (!Calls.canReadContacts(this)) "Da el permiso de contactos para leer el grupo «Favoritos» de tu agenda"
            else "Leídos: ${Contacts.favoriteCount(this)} favoritos. Sus llamadas aparecen SIEMPRE, aunque estés en un juego",
            Calls.canReadContacts(this)) {
            if (Calls.canReadContacts(this)) Calls.openAppPerms(this) else
                try { requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 7) } catch (e: Throwable) { Calls.openAppPerms(this) }
        }
        permRow(b, "Dispositivos cercanos", "Detecta conexiones Bluetooth",
            Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 1)
        }
        permRow(b, "Accesibilidad (Formato libre del launcher)", "Para abrir la app en formato libre desde la isla o tu launcher",
            RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) openAccessibility() else askAccessibility()
        }
        permRow(b, "Acceso de uso (detectar juegos)", "Para ocultar la isla cuando hay un juego o una app elegida al frente",
            Foreground.hasAccess(this)) { askUsageAccess() }
        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        permRow(b, "Sin optimización de batería", "Evita que el sistema cierre la isla en segundo plano", ignoring) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        permRow(b, "Info de la app", "Si Android bloquea un permiso: ⋮ → «Permitir ajustes restringidos». Si tu ROM tiene «Autoinicio», actívalo.", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun themeDialog() = sheet("Tema de la app") { b ->
        Glass.THEMES.forEach { t ->
            val on = Prefs.appTheme(this) == t.id
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(12), px(12), px(12))
                background = Glass.ripple(this@MainActivity, Glass.pill(this@MainActivity, if (on) Glass.withA(t.accent, 0x22) else 0, if (on) t.accent else 0, 16f), 16f)
                setOnClickListener { Prefs.setAppTheme(this@MainActivity, t.id); Glass.applyTheme(this@MainActivity); recreate() }
            }
            r.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(t.accent) }
            }, LinearLayout.LayoutParams(px(28), px(28)).apply { marginEnd = px(14) })
            r.addView(label(t.label, 16f, R.color.text, true))
            b.addView(r, lp(6))
        }
    }

    /** Ajustes de LLAMADAS: qué app usar, saltar el bloqueo por contactos prioritarios y gestionar la lista. */
    private fun callsDialog() { callsSheet() }
    private fun callsSheet() {
        // Como la hoja se construye antes de devolverse, se guarda en una referencia para poder cerrarla y
        // reabrirla con los datos nuevos cuando el usuario cambia algo.
        var dlgRef: Dialog? = null
        val dlg = sheet("Llamadas") { b ->
            val reload: () -> Unit = { dlgRef?.dismiss(); callsSheet() }
            val dialers = Calls.dialers(this)
            val def = Calls.defaultDialer(this)
            b.addView(label("App de llamadas", 16f, R.color.text, true))
            b.addView(label("Ryx usa la predeterminada del sistema salvo que elijas otra aquí. " +
                "Hay ${dialers.size} app(s) de llamadas en tu teléfono.", 12f, R.color.muted), lp(4))
            val rows = ArrayList<Pair<String, String>>()
            rows.add("" to "Predeterminada del sistema")
            dialers.forEach { rows.add(it) }
            rows.forEach { (pkg, name) ->
                val on = Prefs.callApp(this) == pkg
                val isDef = pkg.isNotEmpty() && pkg == def
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(12), px(12), px(12), px(12))
                    background = Glass.ripple(this@MainActivity,
                        Glass.pill(this@MainActivity, if (on) Glass.accentA(0x22) else 0, if (on) Glass.ACCENT else 0, 16f), 16f)
                    setOnClickListener { Prefs.setCallApp(this@MainActivity, pkg); toast("Listo"); reload() }
                }
                r.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (on) Glass.ACCENT else Glass.SURFACE_HI) }
                }, LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(14) })
                r.addView(label(if (pkg.isEmpty()) "Predeterminada del sistema" else name, 16f, R.color.text, true),
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                if (isDef) r.addView(label("predeterminada", 11f, R.color.gold, true))
                b.addView(r, lp(6))
            }

            b.addView(label("Contactos prioritarios", 16f, R.color.text, true).apply { setPadding(0, px(14), 0, 0) })
            b.addView(rowWithSwitch("La isla aparece SIEMPRE",
                "Si la llamada es de un contacto prioritario, la isla se muestra aunque estés en un juego o la isla esté oculta",
                Prefs.callPriorityBypass(this)) { Prefs.setCallPriorityBypass(this, it) }, lp(4))
            b.addView(rowWithSwitch("Usar el grupo «Favoritos» de la agenda",
                if (Calls.canReadContacts(this)) "Lee los contactos con estrella de tu agenda"
                else "Necesita el permiso de contactos (está en Ajustes → Permisos)",
                Prefs.callUseFavorites(this)) {
                Prefs.setCallUseFavorites(this, it)
                if (it && !Calls.canReadContacts(this))
                    try { requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 7) } catch (e: Throwable) {}
            }, lp(4))

            b.addView(label("Añade nombres o números a mano (no hace falta permiso de contactos):", 12f, R.color.muted)
                .apply { setPadding(0, px(10), 0, 0) })
            val inOwn = EditText(this).apply {
                hint = "Nombre o número"; setHintTextColor(Glass.MUTED); setTextColor(Glass.TEXT); textSize = 15f
                setSingleLine(true); setPadding(px(16), px(12), px(16), px(12))
                background = Glass.control(this@MainActivity, 0xFF0E1014.toInt(), Glass.HAIR, 14f)
            }
            b.addView(inOwn, lp(4))
            b.addView(button("Añadir a prioritarios", false) {
                val v = inOwn.text.toString().trim()
                if (v.isNotEmpty()) { Prefs.addPriorityName(this, v); toast("Añadido"); reload() }
            }, lp(6))
            val own = Prefs.priorityNames(this)
            if (own.isEmpty()) {
                b.addView(label("(Sin nombres propios añadidos)", 12f, R.color.muted), lp(4))
            } else {
                own.forEach { nm ->
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(px(6), px(4), px(6), px(4))
                    }
                    r.addView(label(nm, 15f, R.color.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
                    r.addView(button("Quitar", false) { Prefs.removePriorityName(this, nm); reload() })
                    b.addView(r)
                }
            }
            b.addView(label("Con el permiso de contactos, Ryx detecta solo los favoritos (estrella). Sin él, usa la lista de arriba.",
                12f, R.color.muted), lp(8))
        }
        dlgRef = dlg
    }

    /** Chats en pantalla: activar la lectura por accesibilidad y elegir qué apps vigilar. */
    private fun chatCaptureDialog() {
        var dlgRef: Dialog? = null
        val dlg = sheet("Chats en pantalla") { b ->
            val reload: () -> Unit = { dlgRef?.dismiss(); chatCaptureDialog() }
            b.addView(rowWithSwitch("Registrar chats que abro",
                "Ryx lee el texto VISIBLE cuando abres WhatsApp, Telegram, Instagram, TikTok y demás. " +
                "Así se guardan también los chats ARCHIVADOS o RESTRINGIDOS (esos no mandan notificación).",
                Prefs.chatCapture(this)) { Prefs.setChatCapture(this, it); reload() }, lp(4))
            b.addView(rowWithSwitch("Registrar los adjuntos que se ven",
                "Guarda en «En pantalla» si en el chat se ve una Foto, Vídeo, Sticker, GIF, Audio, Documento o «Ver una vez». " +
                "El tipo se lee de la descripción de la pantalla; el recorte visual se controla abajo.",
                Prefs.chatMedia(this)) { Prefs.setChatMedia(this, it); reload() }, lp(4))
            b.addView(rowWithSwitch("Copiar también la imagen visible",
                "Cuando hay una foto, sticker o GIF en pantalla, guarda un recorte del adjunto. La accesibilidad de Android " +
                "solo entrega el fotograma visible, así que un GIF queda como imagen fija; las notificaciones sí conservan " +
                "el GIF/WebP animado cuando la app comparte el archivo original. Android puede mostrar su indicador de captura.",
                Prefs.chatShots(this)) { Prefs.setChatShots(this, it); reload() }, lp(4))
            if (!RyxAccessibility.isEnabled(this)) {
                b.addView(label("Falta el servicio de accesibilidad: sin él, Ryx no puede leer la pantalla.",
                    12f, R.color.gold, true), lp(8))
                b.addView(button("Activar accesibilidad", true) { askAccessibility() }, lp(8))
            }
            // Apps de chat detectadas en el teléfono
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { Sys.isChatApp(it) }
                .map { it to try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it } }
                .sortedBy { it.second.lowercase() }
            val chosen = Prefs.chatPkgs(this)
            b.addView(label("Apps de chat detectadas (${installed.size}). Si no marcas ninguna, se vigilan todas.",
                12f, R.color.muted).apply { setPadding(0, px(10), 0, 0) })
            if (installed.isEmpty()) {
                b.addView(label("(No se reconoció ninguna app de chat instalada)", 12f, R.color.muted), lp(4))
            }
            installed.forEach { (p, name) ->
                val on = chosen.contains(p)
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(8), px(6), px(8))
                    setOnClickListener {
                        val s = Prefs.chatPkgs(this@MainActivity).toMutableSet()
                        if (on) s.remove(p) else s.add(p)
                        Prefs.setChatPkgs(this@MainActivity, s); reload()
                    }
                }
                r.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (on) Glass.ACCENT else Glass.SURFACE_HI) }
                }, LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(12) })
                r.addView(label(name, 15f, R.color.text, on), LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(label(p, 10f, R.color.muted))
                b.addView(r)
            }
        }
        dlgRef = dlg
    }

    private fun backupDialog() = sheet("Copia de seguridad") { b ->
        val last = Prefs.backupLast(this)
        val copies = publicCopies(this).size
        val status = when {
            last <= 0 -> "Todavía no hay ningún respaldo guardado."
            else -> "Último respaldo: ${relative(last)} · $copies ${if (copies == 1) "copia" else "copias"} en «${Backup.FOLDER}»"
        }
        b.addView(label(status, 14f, R.color.text), lp(6))
        b.addView(label("Carpeta del teléfono:\n${Backup.publicDir(this).absolutePath}", 12f, R.color.muted), lp(6))
        b.addView(rowWithSwitch("Guardar respaldos solo", "Ryx crea la carpeta «ryx respaldo» en tu almacenamiento y guarda por su cuenta, sin que pulses nada",
            Prefs.backupAuto(this)) { v -> Prefs.setBackupAuto(this, v); if (v) Backup.autoLocal(this, true) })
        b.addView(label("Guardar cada", 13f, R.color.muted, true), lp(12))
        b.addView(segmented(listOf("30 s", "1 min", "5 min", "30 min"), when (Prefs.backupEverySec(this)) {
            60 -> 1; 300 -> 2; 1800 -> 3; else -> 0 }, true) {
            Prefs.setBackupEverySec(this, listOf(30, 60, 300, 1800)[it])
        }, lp(6))
        b.addView(label("Copias que se conservan", 13f, R.color.muted, true), lp(12))
        b.addView(segmented(listOf("3", "7", "14", "30"), when (Prefs.backupKeep(this)) {
            3 -> 0; 14 -> 2; 30 -> 3; else -> 1 }, true) { Prefs.setBackupKeep(this, listOf(3, 7, 14, 30)[it]) }, lp(6))
        b.addView(button("Guardar respaldo ahora", true) {
            toast("Guardando…")
            Thread { val r = Backup.writeLocal(this); runOnUiThread { toast(if (r.isEmpty()) "Respaldo guardado" else r); render() } }.start()
        }, lp(12))
        b.addView(button("Abrir la carpeta en Archivos", false) { openPublicFolder() }, lp(8))
        val locals = allCopies(this)
        if (locals.isNotEmpty()) {
            b.addView(label("Respaldos guardados en «${Backup.FOLDER}» (toca para restaurar)", 13f, R.color.muted, true), lp(12))
            locals.take(6).forEach { f ->
                val when0 = f.name.removeSuffix(".zip").substringAfterLast('_').toLongOrNull() ?: 0L
                val size = "${f.length() / 1024} KB"
                item(b, "backup", 0xFF3F7BEA.toInt(),
                    if (when0 > 0) relative(when0) else f.name, size, "") {
                    Thread {
                        val msg = try { f.inputStream().use { Backup.restore(this, it) } } catch (e: Exception) { "No pude restaurar: ${e.message}" }
                        runOnUiThread { applyAll(); render(); dialog("Restaurar copia", msg) }
                    }.start()
                }
            }
        }
        b.addView(button("Ajustes al portapapeles", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ryx", Prefs.exportAll(this))); toast("Ajustes copiados")
        }, lp(12))
        b.addView(button("Ajustes desde el portapapeles", false) {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (Prefs.importAll(this, txt)) { applyAll(); render(); toast("Ajustes importados") } else toast("El portapapeles no tiene una copia válida")
        }, lp(8))
        b.addView(label("Los respaldos se guardan en la carpeta «${Backup.FOLDER}» de tu almacenamiento interno " +
            "(Download/${Backup.FOLDER}), a la vista en cualquier app de Archivos, y se guardan solos cada 30 segundos cuando algo " +
            "cambia (historial o ajustes). Para que sobrevivan a desinstalar la app, usa «Copia en la nube»: deja el archivo " +
            "en Google Drive o OneDrive.", 12f, R.color.muted), lp(10))
    }

    /** Copias de la carpeta VISIBLE del teléfono. */
    private fun publicCopies(c: android.content.Context): List<java.io.File> =
        Backup.publicDir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: emptyList()

    /** Copias de la carpeta privada (fiable) y de la visible, la más reciente primero. */
    private fun allCopies(c: android.content.Context): List<java.io.File> =
        (java.io.File(c.filesDir, Backup.FOLDER).listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: emptyList())
            .plus(publicCopies(c))
            .distinctBy { it.name }
            .sortedByDescending { it.name.substringAfterLast('_').removeSuffix(".zip").toLongOrNull() ?: 0L }

    /** Abre la app de Archivos en la carpeta del respaldo (o el selector si no hubiera app de Archivos). */
    private fun openPublicFolder() {
        val dir = Backup.publicDir(this)
        try { dir.mkdirs() } catch (e: Exception) {}
        val u = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" +
            Uri.encode("Download/" + Backup.FOLDER))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "resource/folder")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "*/*")) }
            catch (e2: Exception) { toast("Carpeta: ${dir.absolutePath}") }
        }
    }

    /** Ajustes de actualizaciones: con qué frecuencia se mira GitHub y por dónde te avisa. */
    private fun updateDialog() = sheet("Actualizaciones") { b ->
        b.addView(label("Versión instalada: v${BuildConfig.VERSION_NAME}", 14f, R.color.text), lp(6))
        // Detección del respaldo propio: al abrir la app, si hay una copia y el historial está vacío, se pregunta.
        val hasLocal = Backup.hasLocal(this)
        val hasCloud = Backup.configured(this) && Backup.cloudFileUri(this) != null
        val respTxt = when {
            hasLocal -> "Respaldo encontrado en «${Backup.FOLDER}»" + (if (Prefs.histAll(this).isEmpty()) " · se ofrecerá al abrir" else "")
            Prefs.backupAuto(this) -> "Respaldo propio activo (carpeta «${Backup.FOLDER}»), todavía sin copias"
            else -> "Respaldo propio desactivado"
        }
        b.addView(label(respTxt, 13f, R.color.muted), lp(6))
        if (hasLocal || hasCloud) b.addView(button("Restaurar el respaldo encontrado", true) {
            if (hasLocal) Backup.latestLocal(this)?.let { restoreLocal(it) } else restoreFromCloud()
        }, lp(8))
        b.addView(rowWithSwitch("Avisar con la isla", "Ryx te avisa dentro de la isla, no en el panel del sistema",
            Prefs.updViaIsland(this)) { v -> Prefs.setUpdViaIsland(this, v) })
        b.addView(rowWithSwitch("Avisar también en el panel", "Además del aviso de la isla, deja una notificación normal",
            Prefs.updAlsoPanel(this)) { v -> Prefs.setUpdAlsoPanel(this, v) })
        b.addView(label("Revisar GitHub cada", 13f, R.color.muted, true), lp(12))
        b.addView(segmented(listOf("15 s", "30 s", "1 min", "5 min", "30 min"), when (Prefs.updEverySec(this)) {
            15 -> 0; 60 -> 2; 300 -> 3; 1800 -> 4; else -> 1 }, true) {
            Prefs.setUpdEverySec(this, listOf(15, 30, 60, 300, 1800)[it])
        }, lp(6))
        b.addView(button("Buscar ahora", true) { checkUpdates(true) }, lp(12))
        b.addView(label("Ryx consulta la última versión en GitHub mientras está en marcha. Cuando sale una nueva, " +
            "la anuncia DENTRO de la isla: tócala para verlo todo y actualizar. Así no se mezcla con los avisos de tus apps.", 12f, R.color.muted), lp(8))
    }

    private fun cloudDialog() = sheet("Copia en la nube") { b ->
        val last = Prefs.cloudLast(this)
        val drive = Backup.hasDrive(this)
        val one = Backup.hasOneDrive(this)
        val prov = Backup.providerLabel(this)
        val cloudTxt = when {
            !Backup.configured(this) -> when {
                drive && one -> "Detecté Google Drive y OneDrive. Elige en cuál quieres guardar."
                drive -> "Detecté Google Drive ✔ Elige la carpeta «Mi unidad»."
                one -> "Detecté OneDrive ✔ Elige tu carpeta en la nube."
                else -> "No encontré Google Drive ni OneDrive instalados. Instala uno para usar la copia en la nube."
            }
            last > 0 -> "Guardando en: $prov ✔\nÚltima copia: " + android.text.format.DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
            else -> "Guardando en: $prov ✔ · aún sin copias"
        }
        b.addView(label(cloudTxt, 14f, R.color.text), lp(6))
        if (drive) b.addView(button("Usar Google Drive (Mi unidad)", true) { pickCloudFolder(Backup.driveRootUri()) }, lp(10))
        if (one) b.addView(button(if (drive) "Usar OneDrive" else "Usar OneDrive", !drive) { pickCloudFolder(Backup.oneDriveRootUri()) }, lp(8))
        b.addView(button("Elegir otra carpeta", false) { pickCloudFolder(null) }, lp(8))
        if (Backup.configured(this)) {
            b.addView(button("Guardar copia ahora", false) {
                toast("Guardando…")
                Thread {
                    val r = Backup.upload(this)
                    runOnUiThread { toast(if (r.isEmpty()) "Copia guardada en $prov" else r) }
                }.start()
            }, lp(8))
            b.addView(button("Buscar copia en la nube", false) {
                toast("Buscando…")
                Thread {
                    val has = Backup.hasCloud(this)
                    runOnUiThread {
                        if (has) glass(AlertDialog.Builder(this).setTitle("Hay una copia en la nube")
                            .setMessage("Encontré «$prov/${Backup.FOLDER}/${Backup.NAME}» (o en la carpeta elegida). " +
                                "¿Quieres traerla y restaurarla ahora?")
                            .setPositiveButton("Restaurar") { _, _ -> restoreFromCloud() }
                            .setNegativeButton("Ahora no", null).show())
                        else dialog("Copia en la nube", "No encontré ninguna copia en la carpeta elegida.")
                    }
                }.start()
            }, lp(8))
        }
        b.addView(button("Restaurar desde un archivo", false) { pickRestore() }, lp(8))
        b.addView(label("Ryx guarda dentro de la nube una carpeta «${Backup.FOLDER}» con el archivo ${Backup.NAME} " +
            "(tus ajustes, el historial y sus imágenes). Al abrir la app, si encuentra esa copia y tu historial está vacío, " +
            "te pregunta si quieres restaurarla. Como queda en tu Drive u OneDrive, sigue ahí aunque desinstales la app.", 12f, R.color.muted), lp(10))
    }

    private fun devDialog() = sheet("Opciones de desarrollador") { b ->
        b.addView(label("Registro de la última apertura en formato libre:", 13f, R.color.muted))
        b.addView(logBlock(Prefs.lastLog(this).ifEmpty { "Todavía no hay registro. Haz una prueba en la pestaña Ventana." }), lp(8))
        b.addView(button("Copiar registro", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", Prefs.lastLog(this))); toast("Registro copiado")
        }, lp(8))
        b.addView(button("Probar iconos de apps", false) {
            val pm = packageManager; val sb = StringBuilder()
            val n = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).size
            sb.append("Apps visibles para Ryx: $n\nAcceso a notificaciones: ${if (notifEnabled()) "sí" else "no"}\n\n")
            val pkgs = (Prefs.histAll(this).map { it.pkg }.distinct().take(4) + listOf("com.android.settings", packageName)).distinct()
            pkgs.forEach { pkg ->
                sb.append(try {
                    val d = pm.getApplicationIcon(pkg); "$pkg\n  OK ${d.javaClass.simpleName} ${d.intrinsicWidth}x${d.intrinsicHeight}"
                } catch (e: Exception) { "$pkg\n  ERROR ${e.javaClass.simpleName}" }).append("\n")
            }
            dialog("Prueba de iconos", sb.toString())
        }, lp(10))
        b.addView(button("Probar la isla", true) { svc()?.showTest() ?: toast("Activa el acceso a notificaciones") }, lp(10))
    }

    private fun notifEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun glass(d: AlertDialog): AlertDialog {
        d.window?.apply {
            setBackgroundDrawable(Glass.solid(this@MainActivity, 26f))
            setDimAmount(0.62f)
        }
        for (w in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL))
            d.getButton(w)?.setTextColor(Glass.ACCENT)
        // Título con el mismo peso tipográfico que el resto de la interfaz.
        // `alertTitle` es un id interno del sistema: se resuelve en tiempo de ejecución (android.R.id.alertTitle no es público).
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        d.setOnShowListener {
            try {
                if (titleId != 0) d.findViewById<TextView>(titleId)?.apply {
                    setTextColor(Glass.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    textSize = 19f
                }
            } catch (e: Throwable) {}
        }
        return d
    }

    private fun dialog(title: String, msg: String) =
        glass(AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Cerrar", null).show())

    /** Hoja inferior con asa: sube desde abajo, con las esquinas de arriba redondeadas, y se cierra deslizando hacia abajo. */
    private fun sheet(title: String, build: (LinearLayout) -> Unit): Dialog {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Glass.sheet(this@MainActivity, 30f)
            setPadding(px(22), px(10), px(22), px(20))
        }
        // Asa: zona táctil ancha (invisible) con la barrita visible dentro, para poder deslizar cómodamente.
        val handle = android.widget.FrameLayout(this)
        handle.addView(View(this).apply { background = Glass.pill(this@MainActivity, 0x40FFFFFF, 0, 2f) },
            android.widget.FrameLayout.LayoutParams(px(40), px(4), Gravity.CENTER))
        box.addView(handle,
            LinearLayout.LayoutParams(px(96), px(26)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(8) })
        box.addView(label(title, 21f, R.color.text, true).apply { letterSpacing = -0.015f })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        build(inner)
        val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        box.addView(MaxHeightScroll(this, maxH).apply { addView(inner); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        box.addView(button("Cerrar", true) { dlg.dismiss() }, lp(14))
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(0)); setDimAmount(0.6f)
            setGravity(Gravity.BOTTOM)
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        // El asa indica «desliza hacia abajo»: ese gesto ahora SÍ cierra la hoja (antes no hacía nada).
        // El arrastre se escucha SOLO en el asa: así el contenido de dentro sigue desplazándose con normalidad.
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var y0 = 0f
            private var dragging = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { y0 = e.rawY; dragging = false; return true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - y0
                        if (dy > touchSlop) dragging = true
                        if (dragging) box.translationY = maxOf(0f, dy)
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - y0
                        if (dragging && dy > px(90)) {
                            // Cae hacia abajo y se cierra, como una hoja de verdad.
                            box.animate().translationY(box.height.toFloat())
                                .setDuration(160).withEndAction { dlg.dismiss() }.start()
                        } else box.animate().translationY(0f).setDuration(160).start()
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })
        dlg.setOnDismissListener { render() }
        dlg.show()
        return dlg
    }

    private fun pickApps(title: String, current: Set<String>, max: Int = 999, onDone: (Set<String>) -> Unit) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val sel = BooleanArray(apps.size) { apps[it].first in current }
        glass(AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), sel) { _, i, v -> sel[i] = v }
            .setPositiveButton("Guardar") { _, _ ->
                val r = apps.filterIndexed { i, _ -> sel[i] }.map { it.first }.take(max).toSet()
                onDone(r); render()
            }.setNegativeButton("Cancelar", null).show())
    }

    private fun icon(name: String, sizeDp: Int, color: Int) = ImageView(this).apply {
        setImageDrawable(Glass.icon(this@MainActivity, name, color))
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** Cuadro redondeado con icono. Por defecto, tonal: fondo con un velo del acento e icono del acento. */
    private fun tileIcon(name: String, bg: Int = Glass.accentA(0x26), tint: Int = Glass.ACCENT): View =
        FrameLayout(this).apply {
            background = Glass.tile(this@MainActivity, bg, 13f)
            addView(icon(name, 22, tint).apply { layoutParams = FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER) })
        }

    /** Rótulo de grupo: pequeño, en mayúsculas y espaciado, con una marca del acento. */
    private fun heading(p: LinearLayout, t: String) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), 0, 0, 0)
        }
        r.addView(View(this).apply { background = Glass.pill(this@MainActivity, Glass.accentA(0xCC), 0, 2f) },
            LinearLayout.LayoutParams(px(3), px(12)).apply { marginEnd = px(8) })
        r.addView(label(t, 11.5f, R.color.muted, true).apply { letterSpacing = 0.12f })
        p.addView(r, lp(22))
    }

    /** Fila de lista con icono tonal, título, detalle y flecha; toda la tarjeta responde al toque. */
    private fun item(p: LinearLayout, iconName: String, color: Int, title: String, sub: String, trailing: String, click: () -> Unit) {
        val c = card().apply {
            background = Glass.ripple(this@MainActivity, Glass.card(this@MainActivity, 24f), 24f)
            setPadding(px(15), px(15), px(13), px(15))
            setOnClickListener { click() }
            Glass.press(this)
        }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon(iconName, Glass.withA(color, 0x2E), color), LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 16f, R.color.text, true))
        t.addView(label(sub, 12.5f, R.color.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing.isNotEmpty()) r.addView(label(trailing, 12.5f, R.color.muted).apply { setPadding(px(6), 0, px(4), 0) })
        val chevBox = FrameLayout(this).apply {
            background = Glass.tile(this@MainActivity, Glass.SURFACE_HI, 12f)
            addView(icon("chev_right", 18, Glass.FAINT), FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
        }
        r.addView(chevBox, LinearLayout.LayoutParams(px(32), px(32)))
        c.addView(r); p.addView(c, lp(10))
    }

    /**
     * Sección con cabecera desplegable. La barra de arriba (título + flecha) ABRE y CIERRA la sección EN EL SITIO,
     * con una animación corta de altura y opacidad, sin rehacer toda la pantalla. El estado se recuerda en
     * [expandedGroups] para que sobreviva a cualquier refresco. Antes, tocar la barra llamaba a [render], que
     * reconstruía la página entera: se sentía lento y la cabecera parecía «no responder».
     */
    private fun section(p: LinearLayout, title: String, sub: String, body: (LinearLayout) -> Unit) {
        collapsibleCard(p, "sec_$title", false, head = { top ->
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(title, 17f, R.color.text, true)); t.addView(label(sub, 13f, R.color.muted))
            top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, body = { b ->
            b.setPadding(0, px(8), 0, 0)
            body(b)
        }, topMargin = 6)
    }

    /** Ejecuta [block] al terminar la animación (sin depender de androidx). */
    private inline fun android.animation.Animator.doOnEndCompat(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { block() }
        })
    }

    /** Control de una tarjeta desplegable: permite abrirla o cerrarla desde otro botón («Ver todo»). */
    private class CollapsibleHandle(
        val isOpen: () -> Boolean,
        val setOpen: (Boolean, Boolean) -> Unit
    ) {
        /** Avisa del nuevo estado para refrescar el texto de «Ver todo / Contraer todo». */
        var onToggle: ((Boolean) -> Unit)? = null
    }

    /**
     * Cabecera desplegable genérica: la barra de arriba (contenido [head] + flecha) abre y cierra su cuerpo
     * EN EL SITIO, sin rehacer la pantalla. [defaultOpen] fija el estado cuando la clave todavía no existe.
     * Con [lazyBody] el cuerpo no se construye hasta la primera apertura: el historial puede tener cientos
     * de avisos y no tiene sentido crear todas las filas (e imágenes) para grupos que siguen cerrados.
     */
    private fun collapsibleCard(
        parent: LinearLayout, key: String, defaultOpen: Boolean,
        head: (LinearLayout) -> Unit, body: (LinearLayout) -> Unit, topMargin: Int = 10,
        lazyBody: Boolean = false
    ): CollapsibleHandle {
        val open0 = if (expandedGroups.contains(key)) true else if (expandedGroups.contains("\u0000close_$key")) false else defaultOpen
        val c = card()
        val chev = icon(if (open0) "chev_up" else "chev_down", 22, Glass.MUTED)
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.ripple(this@MainActivity, Glass.pill(this@MainActivity, 0, 0, 16f), 16f)
        }
        head(h)
        h.addView(chev)
        c.addView(h)
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (open0) View.VISIBLE else View.GONE }
        var bodyBuilt = false
        var opened = open0
        var handle: CollapsibleHandle? = null
        fun ensureBody() {
            if (bodyBuilt) return
            bodyBuilt = true
            b.setPadding(0, px(2), 0, 0)
            body(b)
        }
        // Un grupo cerrado y perezoso solo cuesta su cabecera. Las filas (y sus imágenes) nacen al abrirlo.
        if (!lazyBody || open0) ensureBody()
        c.addView(b)

        fun remember(open: Boolean) {
            opened = open
            // Se guardan ambos estados: reabrir conserva la elección del usuario entre refrescos.
            if (open) { expandedGroups.add(key); expandedGroups.remove("\u0000close_$key") }
            else { expandedGroups.remove(key); expandedGroups.add("\u0000close_$key") }
        }
        fun apply(open: Boolean, animate: Boolean) {
            if (open == opened) return
            // Construir ANTES de animar: si no, la altura medida sería cero y la tarjeta no se abriría.
            if (open) ensureBody()
            remember(open)
            handle?.onToggle?.invoke(open)
            chev.setImageDrawable(Glass.icon(this@MainActivity, if (open) "chev_up" else "chev_down", Glass.MUTED))
            if (!animate) { b.visibility = if (open) View.VISIBLE else View.GONE; return }
            if (open) {
                b.visibility = View.VISIBLE; b.alpha = 0f
                b.measure(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                val target = b.measuredHeight
                val anim = android.animation.ValueAnimator.ofInt(0, target)
                anim.duration = 220; anim.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                anim.addUpdateListener { b.layoutParams = LinearLayout.LayoutParams(MATCH, it.animatedValue as Int); b.requestLayout() }
                anim.doOnEndCompat { b.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP); b.requestLayout() }
                anim.start(); b.animate().alpha(1f).setDuration(200).start()
            } else {
                val anim = android.animation.ValueAnimator.ofInt(maxOf(b.height, 1), 0)
                anim.duration = 180; anim.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                anim.addUpdateListener { b.layoutParams = LinearLayout.LayoutParams(MATCH, it.animatedValue as Int); b.requestLayout() }
                anim.doOnEndCompat { b.visibility = View.GONE; b.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP); b.requestLayout() }
                anim.start(); b.animate().alpha(0f).setDuration(140).start()
            }
        }
        // La barra responde al toque (abre/cierra) y al deslizar (arriba abre, abajo cierra). Se comprueba primero
        // que el toque NO haya caído en un control de la cabecera (interruptor, icono ⤢…): esos siguen a lo suyo.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        h.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var y0 = 0f
            private var moved = false
            private var ours = false
            private fun hitClickable(v: View, x: Float, y: Float): Boolean {
                if (v !== h && v.isClickable && v.visibility == View.VISIBLE) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) return true
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (hitClickable(v.getChildAt(i), x, y)) return true
                return false
            }
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        y0 = e.rawY; moved = false
                        ours = !hitClickable(h, e.rawX, e.rawY)   // ¿el toque fue en zona «libre» de la cabecera?
                        return ours
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!ours) return false
                        if (kotlin.math.abs(e.rawY - y0) > slop) moved = true
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!ours) return false
                        val dy = e.rawY - y0
                        if (dy > slop * 2) apply(false, true)
                        else if (dy < -slop * 2) apply(true, true)
                        else if (!moved) apply(expandedGroups.contains(key).not(), true)   // era un toque
                        return true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> return ours
                }
                return false
            }
        })
        parent.addView(c, lp(topMargin))
        val cardHandle = CollapsibleHandle(isOpen = { opened }, setOpen = { open, animate -> apply(open, animate) })
        handle = cardHandle
        return cardHandle
    }

    private fun permRow(p: LinearLayout, title: String, sub: String, ok: Boolean, click: () -> Unit) {
        val tone = if (ok) 0xFF3DDC84.toInt() else 0xFFF2A01F.toInt()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon(if (ok) "shield" else "battery_alert", Glass.withA(tone, 0x26), tone),
            LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 15.5f, R.color.text, true))
        t.addView(label(if (ok) "Concedido" else "Acción necesaria", 12f, if (ok) R.color.green else R.color.gold, true))
        t.addView(label(sub, 12f, R.color.muted))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(8) })
        r.addView(button(if (ok) "Abrir" else "Conceder", !ok) { click() })
        p.addView(r, lp(14))
    }

    /** Selector segmentado: pista oscura y segmento elegido elevado (o con el acento si [green]). */
    /**
     * Selector de opciones tipo «pastilla». Se actualiza SOLO al tocar: cada opción se re-ilumina a sí misma.
     * Antes dependía de [render], que reconstruye la pantalla principal pero NO la hoja abierta encima,
     * así que dentro de una hoja el toque no cambiaba nada a la vista.
     */
    private fun segmented(opts: List<String>, selected: Int, green: Boolean = false, onPick: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Pista con un degradado corto y borde de 1 dp: antes era un relleno plano y parecía un botón pegado.
            background = Glass.control(this@MainActivity, 0xFF0F131A.toInt(), Glass.HAIR, 16f)
            setPadding(px(4), px(4), px(4), px(4))
            val views = ArrayList<TextView>(opts.size)
            fun paint(sel: Int) {
                views.forEachIndexed { i, tv ->
                    val on = i == sel
                    tv.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
                    tv.setTextColor(if (on) (if (green) Glass.onAccent() else Glass.TEXT) else Glass.MUTED)
                    tv.background = if (on) Glass.ripple(this@MainActivity,
                        if (green) Glass.control(this@MainActivity, Glass.ACCENT, 0x33FFFFFF, 12f)
                        else Glass.control(this@MainActivity, Glass.SURFACE_TOP, Glass.EDGE, 12f), 12f)
                    else Glass.ripple(this@MainActivity, Glass.pill(this@MainActivity, 0, 0, 12f), 12f)
                }
            }
            opts.forEachIndexed { i, o ->
                val tv = TextView(this@MainActivity).apply {
                    text = o; gravity = Gravity.CENTER; textSize = 13.5f
                    setPadding(px(6), px(10), px(6), px(10))
                    setOnClickListener {
                        // Se ilumina al instante (funciona igual dentro de una hoja) y luego avisa al ajuste.
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        paint(i); onPick(i)
                    }
                }
                views.add(tv)
                addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i > 0) marginStart = px(2)
                })
            }
            paint(selected)
        }

    /** Control deslizante: nombre a la izquierda, valor en el acento a la derecha. */
    private fun seekRow(parent: LinearLayout, title: String, unit: String, lo: Int, hi: Int, value: Int, onChange: (Int) -> Unit) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val vl = label("$value $unit", 13.5f, R.color.gold, true)
        head.addView(label(title, 14f, R.color.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(vl)
        val seek = SeekBar(this).apply {
            max = hi - lo; progress = value.coerceIn(lo, hi) - lo
            progressTintList = ColorStateList.valueOf(Glass.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    vl.text = "${p + lo} $unit"; onChange(p + lo); previewView?.refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { svc()?.let { it.refreshLive(); it.showTest() } }
            })
        }
        parent.addView(head, lp(14)); parent.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /**
     * Fila de FORMA: cuadrado, redondeado o circular. Es un atajo visual del «radio de esquina»,
     * para no tener que arrastrar el deslizador a mano: escribe el valor en [onPick] (0 = cuadrado,
     * 24 = redondeado, 999 = circular/pastilla).
     */
    private fun shapeRow(parent: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        val names = listOf("Cuadrado", "Redondeado", "Circular")
        val icons = listOf("window", "expand", "ball")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = ArrayList<LinearLayout>(names.size)
        val marks = ArrayList<ImageViewHolder>(names.size)
        // Se re-ilumina sola al tocar (igual que [segmented]): así funciona dentro de una hoja sin depender de [render].
        fun paint(shape: Int) {
            names.forEachIndexed { i, _ ->
                val on = i == shape
                cells[i].background = Glass.ripple(this@MainActivity, Glass.chipCached(this@MainActivity, on, 16f), 16f)
                marks[i].iv.setImageDrawable(Glass.icon(this@MainActivity, icons[i], if (on) Glass.ACCENT else Glass.MUTED))
            }
        }
        names.forEachIndexed { i, n ->
            val iv = icon(icons[i], 26, Glass.MUTED)
            marks.add(ImageViewHolder(iv))
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(px(4), px(12), px(4), px(10))
                background = Glass.ripple(this@MainActivity, Glass.chipCached(this@MainActivity, false, 16f), 16f)
                setOnClickListener { paint(i); onPick(if (i == 0) 0 else if (i == 1) 24 else 999) }
            }
            cell.addView(iv)
            cell.addView(label(n, 11.5f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(6))
            cells.add(cell)
            row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
        }
        paint(when { current <= 1 -> 0; current >= 900 -> 2; else -> 1 })
        parent.addView(label("Forma", 13f, R.color.muted, true), lp(12))
        parent.addView(row, lp(6))
    }

    /** Envoltura mínima para poder repintar el icono de una celda de forma. */
    private class ImageViewHolder(val iv: ImageView)

    /**
     * Fila con interruptor. El interruptor se actualiza al instante al tocarlo (o al tocar la fila entera) y el
     * fila devuelve su vista para poder reflejar cambios externos sin reconstruir la pantalla.
     */
    private fun rowWithSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // Cada ajuste es una pastilla de bajo contraste: separa las filas sin llenar la tarjeta de cajas.
            setPadding(px(13), px(11), px(12), px(11))
            background = Glass.ripple(this@MainActivity, Glass.row(this@MainActivity, 16f), 16f)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, R.color.text, true))
        if (sub.isNotEmpty()) col.addView(label(sub, 13f, R.color.muted).apply { setPadding(0, px(1), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(14) })
        val sw = makeSwitch(checked, onChange)
        row.addView(sw)
        // Tocar toda la fila también cambia el interruptor (y su reflejo visual va con él).
        row.setOnClickListener {
            sw.checked = !sw.checked
            sw.onChange?.invoke(sw.checked)
        }
        return row
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        // Casi siempre llega un recurso R.color.*; las etiquetas de sección usan un color directo.
        val c = when {
            color == R.color.gold -> Glass.ACCENT
            (color ushr 24) == 0xFF -> color
            else -> try { getColor(color) } catch (t: Throwable) { 0xFFE9EDF5.toInt() }
        }
        setTextColor(c)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Fila de datos rápidos bajo la cabecera: fichas con «etiqueta» pequeña y valor. */
    private fun heroStat(p: LinearLayout, color: Int, items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, kv) in items.withIndex()) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), px(11), px(14), px(11))
                background = Glass.pill(this@MainActivity, Glass.SURFACE_HI, Glass.HAIR, 16f)
            }
            cell.addView(label(kv.first.uppercase(), 10f, R.color.muted, true).apply { letterSpacing = 0.1f })
            cell.addView(label(kv.second, 14f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(3) })
            row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = px(8) })
        }
        p.addView(row, lp(10))
    }

    /** Rejilla de 2 columnas de fichas con icono tonal, dato grande y pie. Para Inicio y Ajustes. */
    private fun statGrid(p: LinearLayout, cells: List<Triple<String, String, String>>) {
        cells.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (iconName, value, title) ->
                val c = card().apply { setPadding(px(14), px(14), px(14), px(14)) }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(tileIcon(iconName), LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(10) })
                top.addView(label(value, 21f, R.color.text, true).apply {
                    letterSpacing = -0.02f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                c.addView(top)
                c.addView(label(title, 12.5f, R.color.muted).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, lp(10))
                r.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5); marginEnd = px(5) })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            p.addView(r, lp(10))
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.card(this@MainActivity, 24f)
        setPadding(px(18), px(16), px(18), px(16))
    }

    private fun makeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = GlassSwitch(this).apply {
        // onChange primero: así el valor inicial nunca se confunde con un toque del usuario.
        this.onChange = onChange
        this.checked = checked
    }

    /** Botón: lleno con el acento (texto de máximo contraste) o tonal con un velo del acento; con destello al tocar. */
    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; stateListAnimator = null; elevation = 0f
        textSize = 14f; letterSpacing = 0.01f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        minHeight = px(46); minimumHeight = px(46); minWidth = 0; minimumWidth = 0
        setPadding(px(18), px(11), px(18), px(11))
        setTextColor(if (filled) Glass.onAccent() else Glass.ACCENT)
        background = Glass.ripple(this@MainActivity,
            if (filled) Glass.accentFill(this@MainActivity, 16f)
            else Glass.control(this@MainActivity, Glass.accentA(0x20), Glass.accentA(0x3A), 16f), 16f)
        Glass.press(this, 0.97f)
        setOnClickListener { onClick() }
    }
}
