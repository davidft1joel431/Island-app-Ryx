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
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var activePill: TextView
    private var tab = 0
    private var subPage: String? = null
    private var landscapeView = false
    private val expandedGroups = HashSet<String>().apply { add("power") }
    private var previewView: IslandPreview? = null
    private var previewOpen = false
    private var appQuery = ""
    // Historial: búsqueda por texto y por app (se reconstruye solo la lista, sin perder el cursor del buscador)
    private var histQuery = ""
    private var histFilter = ""
    private var histBox: LinearLayout? = null
    private var pendingHtml = ""
    private var rootStatus = "Sin comprobar"
    private val actionNames = listOf("Nada", "Abrir app", "Ventana (según Formato libre / Burbuja)", "Descartar tarjeta",
        "Siguiente tarjeta", "Tarjeta anterior", "Contraer isla", "Ver detalle",
        "Burbuja", "Formato libre", "Copiar texto", "Silenciar esta app")
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

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)
    private fun svc() = NotifListener.instance
    private fun applyAll() { svc()?.applySettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Cabecera propia de Ryx: logo redondo, nombre grande y estado
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), px(16), px(22), px(10))
        }
        val logoBox = FrameLayout(this).apply {
            setPadding(px(3), px(3), px(3), px(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(px(2), Glass.accentA(0x88))
            }
        }
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        head.addView(logoBox, LinearLayout.LayoutParams(px(54), px(54)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(label("Ryx", 27f, R.color.gold, true).apply { letterSpacing = -0.03f })
        nameRow.addView(label(" Island", 27f, R.color.text, true).apply { letterSpacing = -0.03f })
        names.addView(nameRow)
        names.addView(label("Panel de control · por David Joel", 12.5f, R.color.muted))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(14) })
        activePill = label("", 13f, R.color.green, true).apply { setPadding(px(14), px(7), px(14), px(7)) }
        head.addView(activePill)
        rootView.addView(head)

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Barra de navegación flotante (propia, separada de los bordes)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.card(this@MainActivity, 32f, true)
            setPadding(px(6), px(6), px(6), px(6))
        }
        rootView.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(px(16), px(4), px(16), px(10))
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
        offerRestore()
        if (!RyxAccessibility.isEnabled(this) && !Prefs.a11yAsked(this)) {
            Prefs.setA11yAsked(this, true); askAccessibility()
        }
        if (System.currentTimeMillis() - Prefs.updLast(this) > 12 * 3600_000L) checkUpdates(false)
        handleExtras()
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

    private fun pickCloudFolder() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_TREE)

    /** Instalación nueva y sin historial: ofrece traer la copia guardada (una sola vez). */
    private fun offerRestore() {
        if (Prefs.restoreAsked(this) || Prefs.histAll(this).isNotEmpty() || Backup.configured(this)) return
        Prefs.setRestoreAsked(this, true)
        glass(AlertDialog.Builder(this).setTitle("¿Restaurar tu historial?")
            .setMessage("Si ya tenías una copia de Ryx Island (por ejemplo en Google Drive), puedes traerla ahora: " +
                "recupera tus ajustes y el historial de notificaciones.")
            .setPositiveButton("Elegir copia") { _, _ -> pickRestore() }
            .setNegativeButton("Ahora no", null).show())
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
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(deadListener)
        } catch (e: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); updatePill(); if (tab == 3 || subPage == "perms") render() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (subPage != null) { subPage = null; render() } else super.onBackPressed()
    }

    private fun updatePill() {
        val on = Prefs.islandOn(this) && Settings.canDrawOverlays(this)
        activePill.text = if (on) "● Activa" else "● Inactiva"
        activePill.setTextColor(col(if (on) R.color.green else R.color.red))
        activePill.background = Glass.pill(this, if (on) 0x1A3DDC84 else 0x1AFF6B6B,
            if (on) 0x883DDC84.toInt() else 0x88FF6B6B.toInt(), 22f)
    }

    // ---------------- Navegación ----------------

    private val tabs = listOf("bolt" to "Isla", "tune" to "Diseño", "bell" to "Puente", "window" to "Ventana", "settings" to "Ajustes")

    private fun render() {
        updatePill()
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, (ic, name) ->
            val sel = i == tab && subPage == null || (subPage != null && i == 4)
            val colr = if (sel) Glass.ACCENT else Glass.MUTED
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, px(8), 0, px(8))
                if (sel) background = Glass.pill(this@MainActivity, Glass.accentA(0x33), Glass.accentA(0x66), 26f)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    tab = i; subPage = null; render()
                }
            }
            b.addView(icon(ic, 22, colr))
            b.addView(label(name, 11f, R.color.text, true).apply { setTextColor(colr); gravity = Gravity.CENTER })
            tabBar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(2); marginEnd = px(2) })
        }
        content.removeAllViews()
        previewView = null; appsBox = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(6), px(18), px(24))
        }
        val sp = subPage
        if (sp != null) {
            val heroColor = when (sp) {
                "history" -> 0xFF6A62E8.toInt()
                "demon" -> 0xFFFF9F0A.toInt()
                else -> 0xFF2DAA55.toInt()
            }
            val heroTitle = when (sp) { "history" -> "Historial"; "demon" -> "Demon"; else -> "Permisos" }
            val heroSub = when (sp) {
                "history" -> "Todo lo que pasa por la isla, buscable y exportable"
                "demon" -> "Pruebas de la isla, aparte del resto de la app"
                else -> "Accesos que Ryx necesita para trabajar"
            }
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(14), px(14), px(14))
                background = heroBg(heroColor)
                setOnClickListener { subPage = null; render() }
            }
            hero.addView(tileIcon("back", (heroColor and 0x00FFFFFF) or 0x66000000, Color.WHITE),
                LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(14) })
            val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ht.addView(label(heroTitle, 21f, R.color.text, true))
            ht.addView(label(heroSub, 12f, R.color.muted))
            hero.addView(ht, LinearLayout.LayoutParams(0, WRAP, 1f))
            page.addView(hero, lp(6))
            when (sp) {
                "history" -> buildHistory(page)
                "demon" -> buildDemon(page)
                else -> buildPerms(page)
            }
            content.addView(ScrollView(this).apply { addView(page); isVerticalScrollBarEnabled = false })
            return
        }
        when (tab) {
            0 -> buildHome(page)
            1 -> buildLayout(page)
            2 -> buildBridge(page)
            3 -> buildWindow(page)
            else -> buildSettings(page)
        }
        val scroll = ScrollView(this).apply { addView(page); isVerticalScrollBarEnabled = false }
        val fixed: View? = when (tab) { 1 -> buildPreview(); else -> null }
        if (fixed != null) {
            val colm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            colm.addView(fixed, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = px(18); marginEnd = px(18); topMargin = px(4) })
            colm.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            content.addView(colm)
        } else content.addView(scroll)
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
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply { background = Glass.pill(this@MainActivity, Glass.ACCENT, 0, 6f) },
            LinearLayout.LayoutParams(px(4), px(24)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 18f, R.color.text, true))
        if (sub.isNotEmpty()) t.addView(label(sub, 13f, R.color.muted))
        head.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(head)
        p.addView(c, lp(12))
        return c
    }

    /** Fondo degradado para las cabeceras: del color de la sección hacia el fondo oscuro, con un borde fino. */
    private fun heroBg(color: Int): android.graphics.drawable.Drawable {
        val rgb = color and 0x00FFFFFF
        // Los literales con el bit alto activado (0x8C..., 0x2E...) no caben en Int y Kotlin los infiere como
        // Long; se declaran como Int con «.toInt()» en la propia constante para que la expresión sea siempre Int.
        val c1: Int = (rgb or 0x8C000000.toInt())
        val c2: Int = (rgb or 0x3A000000.toInt())
        val c3: Int = 0x1F000000
        val body = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(c1, c2, c3)).apply { cornerRadius = px(30).toFloat() }
        val s1: Int = 0x2EFFFFFF.toInt()
        val s2: Int = 0x00FFFFFF
        val shine = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(s1, s2)).apply { cornerRadius = px(30).toFloat() }
        val rimColor: Int = (rgb or 0x80000000.toInt())
        val rim = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(30).toFloat(); setColor(0); setStroke(px(1), rimColor)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(body, shine, rim))
    }

    /** Cabecera grande de pestaña, con color propio: da a cada sección un aspecto moderno y distinto. */
    private fun pageHero(p: LinearLayout, iconName: String, color: Int, title: String, sub: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(20), px(20), px(20))
            background = heroBg(color)
        }
        box.addView(tileIcon(iconName, color, Color.WHITE),
            LinearLayout.LayoutParams(px(52), px(52)).apply { marginEnd = px(16) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 23f, R.color.text, true).apply { letterSpacing = -0.02f })
        t.addView(label(sub, 12.5f, R.color.muted))
        box.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(box, lp(16))
    }

    /** Texto de registro en un recuadro oscuro y seleccionable. Solo se usa dentro de las hojas de detalle. */
    private fun logBlock(text: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.pill(this@MainActivity, 0xE60C0E13.toInt(), 0x33FFFFFF, 16f)
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
        val main = card()
        main.addView(rowWithSwitch("Ryx Island", if (Prefs.islandOn(this)) "Activa" else "Apagada",
            Prefs.islandOn(this)) { v -> Prefs.setIslandOn(this, v); applyAll(); render() })
        p.addView(main, lp(8))
        p.addView(segmented(listOf("Vertical", "Horizontal"), if (landscapeView) 1 else 0) {
            landscapeView = it == 1; render()
        }, lp(12))
        statusStrip(p)

        Events.GROUPS.forEach { g ->
            val on = g.events.count { Prefs.eventOnFor(this, it.id, landscapeView) }
            val c = card()
            val open = g.id in expandedGroups
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { if (open) expandedGroups.remove(g.id) else expandedGroups.add(g.id); render() }
            }
            top.addView(tileIcon(g.symbol), LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(14) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(g.label, 18f, R.color.text, true))
            t.addView(label("$on de ${g.events.size} activos", 14f, R.color.muted))
            top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(icon("expand", 20, Glass.ACCENT).apply {
                setPadding(px(6), px(6), px(6), px(6))
                setOnClickListener {
                    val v = !g.events.all { Prefs.eventExpand(this@MainActivity, it.id) }
                    g.events.forEach { Prefs.setEventExpand(this@MainActivity, it.id, v) }
                    toast(if (v) "${g.label}: se abre expandida" else "${g.label}: solo aviso breve"); render()
                }
            })
            top.addView(makeSwitch(on > 0) { v ->
                g.events.forEach { Prefs.setEventOnFor(this, it.id, landscapeView, v) }; render()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(6) })
            top.addView(icon(if (open) "chev_up" else "chev_down", 22, Glass.MUTED).apply { setPadding(px(6), 0, 0, 0) })
            c.addView(top)
            if (open) {
                var row: LinearLayout? = null
                g.events.forEachIndexed { i, ev ->
                    if (i % 2 == 0) {
                        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        c.addView(row, lp(8))
                    }
                    val isOn = Prefs.eventOnFor(this, ev.id, landscapeView)
                    val ex = Prefs.eventExpand(this, ev.id)
                    val chip = FrameLayout(this).apply {
                        background = Glass.pill(this@MainActivity,
                            if (isOn) Glass.accentA(0x40) else 0x14FFFFFF, if (isOn) Glass.ACCENT else 0x33FFFFFF, 20f)
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
                            setOnClickListener {
                                Prefs.setEventExpand(this@MainActivity, ev.id, !ex); render()
                            }
                        }, FrameLayout.LayoutParams(px(16), px(16), Gravity.TOP or Gravity.END).apply {
                            topMargin = px(10); marginEnd = px(10)
                        })
                        setOnClickListener { Prefs.setEventOnFor(this@MainActivity, ev.id, landscapeView, !isOn); render() }
                    }
                    row!!.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5); marginEnd = px(5) })
                }
                if (g.events.size % 2 == 1) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                c.addView(label("Toca para activar o desactivar · la flecha ⤢ elige si se abre expandida",
                    12f, R.color.muted), lp(10))
            }
            p.addView(c, lp(10))
        }
        // Accesos directos a los menús de primer nivel (Historial y Demon), igual que en Ajustes.
        heading(p, "ATAJOS")
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shortcuts.addView(button("Historial", false) { subPage = "history"; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(5) })
        shortcuts.addView(button("Demon", false) { subPage = "demon"; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5) })
        p.addView(shortcuts, lp(8))
    }

    /** Sección «Demon»: todas las pruebas de la isla, aparte del resto de la app. */
    private fun buildDemon(p: LinearLayout) {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            background = Glass.card(this@MainActivity, 26f, true)
        }
        h.addView(tileIcon("bolt", 0x33FF9F0A, 0xFFFF9F0A.toInt()),
            LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label("Demon", 20f, R.color.text, true))
        t.addView(label("Pruebas de la isla: toca un evento y aparece en vivo, sin esperar a una notificación real",
            12f, R.color.muted))
        h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(h, lp(10))
        buildTest(p)
    }

    /** Tarjeta principal de la pestaña Isla: resumen moderno del estado actual con acciones rápidas. */
    private fun heroCard(): View {
        val on = Prefs.islandOn(this) && Settings.canDrawOverlays(this)
        val st = Styles.byId(Prefs.style(this))
        val th = Glass.THEMES.firstOrNull { it.id == Prefs.appTheme(this) } ?: Glass.THEMES[0]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(this@MainActivity, 30f, true)
            setPadding(px(20), px(20), px(20), px(18))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply {
            background = Glass.pill(this@MainActivity, if (on) 0xFF3DDC84.toInt() else 0xFFFF6B6B.toInt(), 0, 20f)
        }
        top.addView(dot, LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(10) })
        top.addView(label(if (on) "Isla activa" else "Isla inactiva", 20f, R.color.text, true),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(label(if (on) "En línea" else "Detenida", 12.5f, if (on) R.color.green else R.color.red, true))
        box.addView(top)
        box.addView(label("Estilo ${st.label}   ·   Tema ${th.label}", 13f, R.color.muted), lp(10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(if (on) "Apagar" else "Encender", !on) {
            Prefs.setIslandOn(this, !Prefs.islandOn(this)); applyAll(); render()
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(5) })
        row.addView(button("Permisos", false) { subPage = "perms"; render() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5) })
        box.addView(row, lp(14))
        return box
    }

    /** Tira de accesos rápidos: lleva a Permisos con un toque, mostrando en verde lo que ya está concedido. */
    private fun statusStrip(p: LinearLayout) {
        val c = card()
        c.addView(label("Accesos", 15f, R.color.text, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, 0) }
        val chips = listOf(
            "Avisos" to notifEnabled(),
            "Encima" to Settings.canDrawOverlays(this),
            "A11y" to RyxAccessibility.isEnabled(this),
            "Shizuku" to Shell.shizukuGranted())
        chips.forEachIndexed { i, chip ->
            row.addView(TextView(this).apply {
                text = (if (chip.second) "● " else "○ ") + chip.first
                gravity = Gravity.CENTER; textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (chip.second) 0xFF3DDC84.toInt() else Glass.MUTED)
                setPadding(px(8), px(9), px(8), px(9))
                background = Glass.pill(this@MainActivity, if (chip.second) 0x1A3DDC84 else 0x14FFFFFF,
                    if (chip.second) 0x883DDC84.toInt() else 0x33FFFFFF, 16f)
                setOnClickListener { subPage = "perms"; render() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = px(6) })
        }
        c.addView(row)
        c.addView(label("Toca para abrir Permisos. En verde, lo que ya está concedido.", 12f, R.color.muted), lp(8))
        p.addView(c, lp(10))
    }

    /** Resumen corto de las vías de aviso activas, para la ficha de Inicio. */
    private fun avisosLabel(): String {
        val vias = ArrayList<String>()
        if (notifEnabled()) vias.add("Avisos")
        if (Settings.canDrawOverlays(this)) vias.add("Encima")
        if (RyxAccessibility.isEnabled(this)) vias.add("A11y")
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
        pageHero(p, "palette", 0xFF7B5CE0.toInt(), "Diseño", "Estilos, temas, tipografía y color de la isla")
        heroStat(p, 0xFF7B5CE0.toInt(), listOf(
            "Estilo" to Styles.byId(Prefs.style(this)).label,
            "Tema" to Glass.theme.label))
        val st = titled(p, "Estilo de la isla", "Toca uno para verlo arriba y en la isla real")
        Styles.ALL.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { sty ->
                r.addView(styleCell(sty), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            st.addView(r, lp(8))
        }

        val ir = titled(p, "Isla en reposo (contraída)", "Cómo se ve cuando no hay nada abierto")
        val mode = Prefs.idleMode(this)
        ir.addView(segmented(listOf("Negro", "Cristal", "Color", "Acento"), mode, true) {
            Prefs.setIdleMode(this, it); applyAll(); svc()?.showTest()
        }, lp(10))
        if (mode != 0) {
            ir.addView(label(if (mode == 1) "Tinte del cristal" else "Color", 13f, R.color.muted, true), lp(12))
            colorRow(ir, Prefs.idleColor(this)) { Prefs.setIdleColor(this, it); applyAll(); svc()?.showTest(); render() }
            hexRow(ir) { Prefs.setIdleColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
        }
        seekRow(ir, if (mode == 1) "Transparencia del cristal" else "Opacidad", "%", 15, 100, Prefs.idleOpacity(this)) { Prefs.setIdleOpacity(this, it) }
        seekRow(ir, "Borde", "dp", 0, 4, Prefs.idleBorder(this).let { if (it >= 0) it else if (mode == 1) 1 else 0 }) { Prefs.setIdleBorder(this, it) }
        ir.addView(rowWithSwitch("Desenfoque del fondo (experimental)", "Android 12+. Puede verse un rectángulo desenfocado detrás de la isla.",
            Prefs.idleBlur(this)) { v -> Prefs.setIdleBlur(this, v); applyAll(); svc()?.showTest() })

        val cr = titled(p, "Tarjeta expandida", "Cristal, color y transparencia")
        cr.addView(rowWithSwitch("Efecto cristal (Liquid Glass)", "Translúcida, con brillo arriba y borde de luz", Prefs.cardGlass(this)) { v ->
            Prefs.setCardGlass(this, v); applyAll(); svc()?.showTest(); previewView?.refresh() })
        cr.addView(label("Color de la tarjeta", 13f, R.color.muted, true), lp(12))
        colorRow(cr, Prefs.cardColor(this)) { Prefs.setCardColor(this, it); applyAll(); svc()?.showTest(); render() }
        hexRow(cr) { Prefs.setCardColor(this, it); applyAll(); svc()?.showTest(); previewView?.refresh() }
        seekRow(cr, "Transparencia", "%", 15, 100, Prefs.opacity(this)) { Prefs.setOpacity(this, it) }

        val sz = titled(p, "Tamaño y posición", "Mira el cambio arriba, en la vista previa")
        seekRow(sz, "Ancho en reposo", "dp", 60, 220, Prefs.idleWidthDp(this)) { Prefs.setIdleWidthDp(this, it) }
        seekRow(sz, "Alto", "dp", 20, 60, Prefs.heightDp(this)) { Prefs.setHeightDp(this, it) }
        seekRow(sz, "Ancho expandido", "dp", 220, 420,
            Prefs.expandedWidthDp(this).let { if (it > 0) it else Styles.byId(Prefs.style(this)).expandedWidthDp }) { Prefs.setExpandedWidthDp(this, it) }
        seekRow(sz, "Margen lateral mínimo", "dp", 0, 40, Prefs.sideMarginDp(this)) { Prefs.setSideMarginDp(this, it) }
        seekRow(sz, "Posición vertical (margen superior)", "dp", 0, 80, Prefs.yOffsetDp(this)) { Prefs.setYOffsetDp(this, it) }
        seekRow(sz, "Posición horizontal", "dp", -100, 100, Prefs.xOffsetDp(this)) { Prefs.setXOffsetDp(this, it) }
        seekRow(sz, "Radio de esquina", "dp", 0, 50, Prefs.cornerDp(this).coerceAtLeast(0)) { Prefs.setCornerDp(this, it) }
        sz.addView(button("Restablecer tamaño y posición", false) {
            Prefs.setXOffsetDp(this, 0); Prefs.setYOffsetDp(this, 8); Prefs.setSideMarginDp(this, 12)
            Prefs.setExpandedWidthDp(this, -1); Prefs.setCornerDp(this, -1); Prefs.setIdleWidthDp(this, 96); Prefs.setHeightDp(this, 32)
            applyAll(); svc()?.showTest(); render()
        }, lp(12))

        val ap = titled(p, "Apariencia")
        ap.addView(rowWithSwitch("Sombra bajo la isla", "", Prefs.shadow(this)) { v ->
            Prefs.setShadow(this, v); applyAll(); svc()?.showTest(); previewView?.refresh() })

        val ac = titled(p, "Acento de la app", "Color de los botones, interruptores y detalles")
        Glass.THEMES.chunked(3).forEach { rowThemes ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowThemes.forEach { t ->
                val on = Glass.theme.id == t.id
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(px(4), px(10), px(4), px(8))
                    background = Glass.pill(this@MainActivity, if (on) Glass.accentA(0x33) else 0x14FFFFFF,
                        if (on) t.accent else 0x22FFFFFF, 18f)
                    setOnClickListener { Prefs.setAppTheme(this@MainActivity, t.id); Glass.applyTheme(this@MainActivity); recreate() }
                }
                cell.addView(View(this).apply { background = Glass.pill(this@MainActivity, t.accent, 0, 20f) },
                    LinearLayout.LayoutParams(px(34), px(34)))
                cell.addView(label(t.label, 11f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(6))
                r.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
            }
            var n = rowThemes.size
            while (n < 3) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); n++ }
            ac.addView(r, lp(6))
        }

        val tp = titled(p, "Tipografía", "Cómo se ve el texto de la isla")
        tp.addView(rowWithSwitch("Título en negrita", "El título se resalta; el cuerpo se lee en letra normal",
            Prefs.fontBold(this)) { v -> Prefs.setFontBold(this, v); applyAll(); svc()?.showTest() })

        val an = titled(p, "Animación", "Resorte estilo HyperOS 3: sube un poco y se asienta")
        an.addView(segmented(listOf("Relajada", "Normal", "Ágil"), Prefs.speed(this), true) { Prefs.setSpeed(this, it) }, lp(10))
        seekRow(an, "Tiempo visible", "s", 2, 15, Prefs.durationSeconds(this)) { Prefs.setDurationSeconds(this, it) }

        val co = titled(p, "Comportamiento")
        co.addView(rowWithSwitch("Isla siempre visible", "Se queda en reposo junto a la cámara", Prefs.permanent(this)) { v ->
            Prefs.setPermanent(this, v); applyAll() })
        co.addView(rowWithSwitch("Vibrar al mostrar", "", Prefs.vibrate(this)) { Prefs.setVibrate(this, it) })

        val vi = titled(p, "Visibilidad", "Cuándo se muestra la isla")
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

        val ge = titled(p, "Gestos", "Con la isla abierta. Si hay varias notificaciones, se acumulan a la derecha.")
        gestureRow(ge, "Tocar", Prefs.gTap(this)) { Prefs.setGTap(this, it) }
        gestureRow(ge, "Deslizar hacia abajo", Prefs.gDown(this)) { Prefs.setGDown(this, it) }
        gestureRow(ge, "Deslizar hacia arriba", Prefs.gUp(this)) { Prefs.setGUp(this, it) }
        gestureRow(ge, "Deslizar a la izquierda", Prefs.gLeft(this)) { Prefs.setGLeft(this, it) }
        gestureRow(ge, "Deslizar a la derecha", Prefs.gRight(this)) { Prefs.setGRight(this, it) }
        gestureRow(ge, "Mantener pulsado", Prefs.gLong(this)) { Prefs.setGLong(this, it) }
        ge.addView(label("Distancia para deslizar: 50 dp (fija)", 14f, R.color.text, true), lp(12))
        ge.addView(label("Un deslizamiento solo cuenta a partir de 50 dp, para que no se active sin querer.", 12f, R.color.muted), lp(4))
        ge.addView(label("«Burbuja» y «Formato libre» abren la app de esa forma sin importar el modo elegido en Ventana. " +
            "En avisos del sistema (batería, Wi-Fi, Bluetooth…) «Abrir app» te lleva a su pantalla de Ajustes. " +
            "«Silenciar esta app» la añade a las apps bloqueadas.", 12f, R.color.muted), lp(8))
        ge.addView(label("Con la isla contraída: tocar o jalar hacia abajo la despliega. " +
            "En la vista de detalle: tocar abre la app, jalar hacia abajo usa el gesto de arriba y hacia arriba vuelve. " +
            "Prueba con «Simular acumulación» en la pestaña Isla.", 12f, R.color.muted), lp(8))
    }

    private val swatchColors = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF2A2E38.toInt(), 0xFFFF8A4C.toInt(),
        0xFFFF5A5F.toInt(), 0xFFFF6B9A.toInt(), 0xFFB388FF.toInt(), 0xFF4DA3FF.toInt(), 0xFF22E5FF.toInt(),
        0xFF4ADE80.toInt(), 0xFFFFC857.toInt(), 0xFF2EE6C5.toInt(), 0xFFFF7A59.toInt(), 0xFF7C89FF.toInt(),
        0xFF00D4C8.toInt(), 0xFFE0E5EC.toInt(), 0xFFB4B8C4.toInt(), 0xFF6A62E8.toInt())

    /** Fila de colores: «Auto» (0) y una paleta para tocar. */
    private fun colorRow(parent: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        val sv = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, px(4)) }
        (listOf(0) + swatchColors).forEach { c ->
            val on = current == c
            row.addView(TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 11f; setTextColor(Glass.TEXT)
                if (c == 0) text = "Auto"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (c == 0) 0x22FFFFFF else c)
                    setStroke(px(if (on) 3 else 1), if (on) Glass.ACCENT else 0x55FFFFFF)
                }
                setOnClickListener { onPick(c) }
            }, LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(8) })
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
            background = Glass.pill(this@MainActivity, if (on) Glass.accentA(0x26) else 0x10FFFFFF,
                if (on) Glass.ACCENT else 0x22FFFFFF, 18f)
            setOnClickListener { Prefs.setStyle(this@MainActivity, sty.id); applyAll(); svc()?.showTest(); render() }
        }
        val sw = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sty.bgTop, sty.bgBottom)).apply {
                cornerRadius = px(15).toFloat()
                if (sty.strokeDp > 0f) setStroke(px(1), sty.strokeColor)
            }
        }
        cell.addView(sw, LinearLayout.LayoutParams(px(110), px(30)))
        cell.addView(label(sty.label, 12f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(8))
        return cell
    }

    private fun gestureRow(parent: LinearLayout, title: String, current: Int, set: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(label(title, 15f, R.color.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(button(actionNames[current.coerceIn(0, actionNames.size - 1)], false) {
            glass(AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(actionNames.toTypedArray(), current) { d, i -> set(i); d.dismiss(); render() }
                .setNegativeButton("Cancelar", null).show())
        })
        parent.addView(r, lp(8))
    }

    // ---------------- PUENTE ----------------

    private fun buildBridge(p: LinearLayout) {
        pageHero(p, "signal", 0xFF3F7BEA.toInt(), "Puente", "Cómo se relacionan la isla y el sistema")
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
        only.addView(rowWithSwitch("Mostrar con el teléfono bloqueado", "", Prefs.lockScreen(this)) { Prefs.setLockScreen(this, it) })
        only.addView(label("Llamadas, multimedia, navegación y alarmas se quedan en el sistema para no romper sus controles. " +
            "Sin ocultar los avisos emergentes, Android puede mostrar el aviso un instante antes de que la isla lo quite.", 12f, R.color.muted), lp(8))
        diagCard(p, "signal", 0xFF3F7BEA.toInt(), "Diagnóstico del puente",
            "Últimos avisos que cruzaron entre el sistema y la isla, con el detalle a un toque.", Prefs.notifDiag(this))

        val ct = titled(p, "Conectividad", "El aviso de red era el que más aparecía: así se controla")
        ct.addView(segmented(listOf("Siempre", "Al cambiar", "Nunca"), Prefs.netMode(this), true) { Prefs.setNetMode(this, it) }, lp(10))
        ct.addView(label("«Al cambiar» (por defecto): solo se anuncia «Conectado a la red …» cuando entra una red nueva o " +
            "cuando pasa un buen rato desde el último aviso, nunca por las revalidaciones de la ROM. «Nunca» silencia " +
            "los avisos de red y los de Wi‑Fi apagado.", 12f, R.color.muted), lp(8))

        val ic = titled(p, "Iconos de la isla", "Del sistema o modificados")
        ic.addView(segmented(listOf("Sistema", "Notificación", "Monocromo"), Prefs.iconMode(this), true) { Prefs.setIconMode(this, it) }, lp(10))
        ic.addView(label("Forma del icono", 13f, R.color.muted, true), lp(12))
        val shapes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Círculo", "Squircle", "Flor", "Hexágono").forEachIndexed { i, n ->
            val on = Prefs.iconShape(this) == i
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(px(4), px(10), px(4), px(8))
                background = Glass.pill(this@MainActivity, if (on) Glass.accentA(0x33) else 0x14FFFFFF,
                    if (on) Glass.ACCENT else 0x22FFFFFF, 18f)
                setOnClickListener { Prefs.setIconShape(this@MainActivity, i); applyAll(); svc()?.showTest(); render() }
            }
            cell.addView(ShapedImageView(this).apply {
                shape = i; setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(px(40), px(40)))
            cell.addView(label(n, 11f, R.color.text, true).apply { gravity = Gravity.CENTER }, lp(6))
            shapes.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(3); marginEnd = px(3) })
        }
        ic.addView(shapes, lp(6))
        ic.addView(rowWithSwitch("Color inteligente", "El borde toma el color del icono de la app", Prefs.smartColor(this)) { Prefs.setSmartColor(this, it) })

        val pr = titled(p, "Privacidad", "Palabras que se ocultan en la isla y en el historial")
        pr.addView(editText("palabra1, palabra2…", Prefs.hiddenWordsRaw(this)) { Prefs.setHiddenWords(this, it) }, lp(8))

        val ap = titled(p, "Apps", "Apague las que no quiera ver en la isla (cada copia clonada se controla por separado)")
        ap.addView(editText("Buscar app…", appQuery) { q -> appQuery = q; fillApps() }, lp(8))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsBox = box
        ap.addView(box, lp(6))
        fillApps()
    }

    private fun appIcon(key: String): Drawable? = iconCache.getOrPut(key) {
        try {
            val (pkg, uid) = Clones.splitKey(key)
            val base = packageManager.getApplicationIcon(pkg)
            // La copia clonada lleva la insignia que le pone la ROM (nombre/icono), para distinguirla del original
            if (Clones.isClone(uid)) packageManager.getUserBadgedIcon(base, Clones.handle(uid)) else base
        } catch (e: Exception) { null }
    }

    /** Etiqueta pequeña para distinguir una copia clonada del original. */
    private fun cloneChip(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; gravity = Gravity.CENTER
        setTextColor(Glass.ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(px(8), px(3), px(8), px(3))
        background = Glass.pill(this@MainActivity, Glass.accentA(0x26), Glass.accentA(0x66), 12f)
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
        background = Glass.pill(this@MainActivity, 0x14FFFFFF, 0x33FFFFFF, 20f)
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------------- VENTANA FLOTANTE ----------------

    private fun buildWindow(p: LinearLayout) {
        val kind = Prefs.fwKind(this)
        // ── Permisos (lo primero: sin ellos el formato libre y los avisos se quedan a medias) ──
        val pm = titled(p, "Permisos", "Todo lo que Ryx puede hacer sin Shizuku ni root")
        pm.addView(statusList(PermGrant.status(this)), lp(6))
        pm.addView(label("Puedes dar todo de una vez. Con Shizuku o root, Ryx se concede los permisos solo (botón de abajo). " +
            "Sin ellos, se copia UN comando de ADB y se pega una sola vez: queda para siempre, no hay que repetirlo. " +
            "El comando concede dibujar encima, escribir ajustes del sistema, acceso de uso, notificaciones, Bluetooth " +
            "y desbloquea las ventanas libres.", 12f, R.color.muted), lp(8))
        pm.addView(button("Quitar permisos con Shizuku/root", Shell.privileged(this)) {
            Thread {
                val r = PermGrant.grant(this)
                runOnUiThread { render(); dialog("Permisos concedidos con ${Shell.label(this)}", r + "\n\nSi algo no se aplicó, usa el comando de ADB.") }
            }.start()
        }, lp(8))
        pm.addView(button("Copiar comando de ADB (una sola vez)", false) {
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", PermGrant.adbCommand(this)))
                dialog("Comando copiado al portapapeles",
                    "Pégalo en una consola de ADB conectada al teléfono (con el reloj de arena, sin tocar nada más). " +
                    "Concede todos los permisos y desbloquea el formato libre de golpe:\n\n" +
                    PermGrant.adbCommand(this) + "\n\nTambién puedes pedir el permiso de notificaciones, el de dibujar encima " +
                    "y el acceso de uso a mano en Ajustes.")
            } catch (e: Exception) { toast("No pude copiar el comando") }
        }, lp(8))
        pm.addView(button("Pedir permiso de notificaciones", !AppNotify.allowed(this)) { askNotifPermission() }, lp(8))
        pm.addView(button("Abrir el acceso de uso del sistema", false) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) { toast("No se pudo abrir") }
        }, lp(8))

        val so = titled(p, "Formato libre y burbujas", "Se abre encima de la app en la que estás, sin sacarte de ella")
        so.addView(segmented(listOf("Formato libre (rápido)", "Burbuja (Android 17)"), kind, true) { Prefs.setFwKind(this, it); render() }, lp(8))
        so.addView(label(if (kind == 1)
            "«Burbuja» (Android 17): flota SOBRE tu app y se queda ahí, con la decoración del sistema. Android no ofrece ninguna API para crearla: " +
            "la PRIMERA vez Ryx pulsa por ti «Burbuja» en el menú de tu launcher (con una captura de tu pantalla como cortina). " +
            "Después la burbuja queda en pantalla y Ryx solo toca su icono: instantáneo, sin cortina y sin salir de tu app. Hasta 5 burbujas a la vez."
            else
            "«Formato libre»: Ryx pide la ventana al sistema con tu tamaño desde accesibilidad. Se abre al instante encima de tu app, " +
            "sin inicio, sin Recientes y sin cortina. Con la ventana de la notificación abre el chat exacto. Ryx dibuja su propia barra para moverla. " +
            "Con Shizuku/root, además, puede lanzarla con `am start --windowingMode 5` y colocarla al píxel: funciona incluso si tu ROM ignora las opciones de Android.",
            12f, R.color.muted), lp(6))
        so.addView(label(if (RyxAccessibility.isEnabled(this)) "Servicio de accesibilidad: activo ✔" else
            "Servicio de accesibilidad: apagado. Actívalo para usar este método.", 12f, R.color.text), lp(8))
        so.addView(button(if (RyxAccessibility.isEnabled(this)) "Ajustes de accesibilidad" else "Activar accesibilidad", !RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) openAccessibility() else askAccessibility()
        }, lp(8))
        if (kind == 1) so.addView(rowWithSwitch("Volver a mi app al terminar", "Tras crear la burbuja, tu app vuelve al frente",
            Prefs.fwKeepApp(this)) { Prefs.setFwKeepApp(this, it) })
        else so.addView(rowWithSwitch("Barra de Ryx sobre la ventana", "Mover, cambiar tamaño y cerrar la ventana",
            Prefs.fwFrame(this)) { Prefs.setFwFrame(this, it) })

        if (kind == 0) {
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

    /** Abre la app de prueba a partir de su clave («paquete» o «paquete@espacio» si es un clon). Fuera del hilo principal. */
    private fun openTestApp(key: String): String {
        val (pkg, uid) = Clones.splitKey(key)
        val clone = Clones.isClone(uid)
        return FloatingWindow.open(this, pkg, null, null, if (clone) Clones.handle(uid) else null, if (clone) uid else -1)
    }

    // ---------------- AJUSTES ----------------

    private fun buildSettings(p: LinearLayout) {
        pageHero(p, "settings", 0xFF2DAA55.toInt(), "Ajustes", "Permisos, copias, historial y pruebas")
        val n = Prefs.histAll(this).size
        heading(p, "APARIENCIA")
        item(p, "palette", 0xFF7B5CE0.toInt(), "Tema de la app", "Color de acento de la aplicación", Glass.theme.label) { themeDialog() }
        item(p, "bolt", 0xFFFF9F0A.toInt(), "Demon", "Todas las pruebas de la isla, en un solo lugar", "") { subPage = "demon"; render() }
        heading(p, "DATOS")
        item(p, "history", 0xFF6A62E8.toInt(), "Historial de notificaciones", "Ordenado por aplicación, con su icono", "$n") { subPage = "history"; render() }
        item(p, "backup", 0xFF3F7BEA.toInt(), "Copia de seguridad", "Exporta o importa todos los ajustes", "") { backupDialog() }
        item(p, "upload", 0xFF2D8CCB.toInt(), "Copia en la nube",
            "Historial y ajustes en Google Drive u otra carpeta", if (Backup.configured(this)) "Activa" else "") { cloudDialog() }
        heading(p, "SISTEMA")
        item(p, "shield", 0xFF2DAA55.toInt(), "Permisos", "Superposición, acceso a notificaciones, batería", "") { subPage = "perms"; render() }
        item(p, "download", 0xFF2DAA55.toInt(), "Buscar actualizaciones", "Consulta la última versión en GitHub",
            "v${BuildConfig.VERSION_NAME}") { checkUpdates(true) }
        item(p, "code", 0xFF5F6B80.toInt(), "Opciones de desarrollador", "Diagnóstico avanzado y pruebas internas", "") { devDialog() }
        item(p, "info", 0xFF5F6B80.toInt(), "Acerca de", "Versión y créditos", "") {
            dialog("Acerca de Ryx Island", "Ryx Island v${BuildConfig.VERSION_NAME}\nCreador: David Joel\n\nLa interfaz está inspirada en Smart Island, " +
                "de Animesh Gupta (licencia GPL-3.0): github.com/agupta07505/SmartIsland, y en Hyper Bridge.\n\n" +
                "Este código es propio y no copia el de esos proyectos. Si en el futuro se incorpora código ajeno, " +
                "hay que conservar su aviso de copyright y respetar su licencia.")
        }
    }

    /** Historial: búsqueda por texto y por aplicación, agrupado por días y separando Apps y Sistema. */
    private fun buildHistory(p: LinearLayout) {
        val all = Prefs.histAll(this)
        val nApps = all.count { !Sys.isSystemPkg(it.pkg) }
        val nSys = all.count { Sys.isSystemPkg(it.pkg) }

        val top = card()
        top.addView(rowWithSwitch("Guardar historial", "Todo lo que pasa por la isla, con el texto completo", Prefs.keepHistory(this)) { Prefs.setKeepHistory(this, it) })
        val sum = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0) }
        sum.addView(summaryChip("grid", 0xFF6A62E8.toInt(), "$nApps", "Aplicaciones"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(4) })
        sum.addView(summaryChip("settings", 0xFF2DAA55.toInt(), "$nSys", "Sistema"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(4) })
        top.addView(sum)
        top.addView(button("Borrar historial", false) { Prefs.clearHist(this); histQuery = ""; histFilter = ""; render() }, lp(10))
        p.addView(top, lp(8))

        // Menú del historial (igual que el de Demon): separa Aplicaciones y Sistema en un solo toque.
        val view = when (histFilter) { "__sys__" -> 2; "" -> 0; else -> 1 }
        val menu = card()
        menu.addView(label("Menú del historial", 16f, R.color.text, true))
        menu.addView(segmented(listOf("Todo", "Aplicaciones", "Sistema"), view) { i ->
            histFilter = when (i) { 1 -> "__apps__"; 2 -> "__sys__"; else -> "" }
        }, lp(10))
        menu.addView(label("Elige qué quieres ver: los avisos de tus apps y los del sistema van separados, como en la isla.", 12f, R.color.muted), lp(8))
        p.addView(menu, lp(8))

        // Buscador: texto libre + filtro por aplicación (o Sistema)
        val search = card()
        search.addView(label("Buscar", 16f, R.color.text, true))
        search.addView(editText("Escribe para buscar en los mensajes…", histQuery) { q -> histQuery = q; fillHist() }, lp(8))
        val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val crow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, px(2)) }
        val addF: (String, String, Int) -> Unit = { id, name, color ->
            val on = histFilter == id
            crow.addView(TextView(this).apply {
                text = name; textSize = 12.5f; gravity = Gravity.CENTER
                setTextColor(if (on) 0xFF10131A.toInt() else Glass.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(px(14), px(7), px(14), px(7))
                background = Glass.pill(this@MainActivity,
                    if (on) color else 0x14FFFFFF, if (on) color else 0x33FFFFFF, 18f)
                setOnClickListener { histFilter = id; render() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = px(8) })
        }
        if (view != 2) {
            all.filter { !Sys.isSystemPkg(it.pkg) }.groupBy { it.pkg }
                .entries.sortedByDescending { e -> e.value.size }.take(12)
                .forEach { (pkg, _) -> addF(pkg, appNameOf(pkg), 0xFF6A62E8.toInt()) }
            if (crow.childCount > 0) {
                search.addView(label("Filtrar por aplicación", 13f, R.color.muted, true), lp(8))
                chips.addView(crow)
                search.addView(chips, lp(4))
            }
        }
        search.addView(button("Descargar como .html", true) { exportHtml() }, lp(12))
        search.addView(label("Se descarga TODO el texto (aunque aquí se recorte) y las imágenes, en un archivo que abre cualquier navegador.",
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
        box.removeAllViews()
        val items = filteredHist()
        if (items.isEmpty()) {
            box.addView(label("No hay nada que coincida.", 14f, R.color.muted)
                .apply { setPadding(px(4), px(16), px(4), px(4)) })
            return
        }
        val appList = items.filter { !Sys.isSystemPkg(it.pkg) }
        val sysList = items.filter { Sys.isSystemPkg(it.pkg) }
        if (appList.isNotEmpty()) {
            historyHeader(box, "grid", 0xFF6A62E8.toInt(), "Aplicaciones", appList.size)
            appList.groupBy { dayLabel(it.ts) }.forEach { (day, dl) ->
                dayHeader(box, day, dl.size, 0xFF6A62E8.toInt())
                dl.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
                    .forEach { (pkg, its) -> appHistoryGroup(box, pkg, its) }
            }
        }
        if (sysList.isNotEmpty()) {
            historyHeader(box, "settings", 0xFF2DAA55.toInt(), "Sistema", sysList.size)
            sysList.groupBy { dayLabel(it.ts) }.forEach { (day, dl) ->
                dayHeader(box, day, dl.size, 0xFF2DAA55.toInt())
                dl.groupBy { Sys.kind(it.pkg, it.title, it.text) }
                    .entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
                    .forEach { (k, its) -> sysHistoryGroup(box, k, its) }
            }
        }
    }

    /** Aplica la búsqueda y el filtro actuales sobre todo el historial. */
    private fun filteredHist(): List<Prefs.HistItem> {
        val q = histQuery.trim().lowercase()
        var items = Prefs.histAll(this)
        if (histFilter == "__sys__") items = items.filter { Sys.isSystemPkg(it.pkg) }
        else if (histFilter == "__apps__") items = items.filter { !Sys.isSystemPkg(it.pkg) }
        else if (histFilter.isNotEmpty()) items = items.filter { it.pkg == histFilter }
        if (q.isNotEmpty()) items = items.filter { it.title.lowercase().contains(q) || it.text.lowercase().contains(q) }
        return items
    }

    /** Separador de día (Hoy · Ayer · fecha). */
    private fun dayHeader(p: LinearLayout, day: String, count: Int, color: Int) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(16), px(6), px(4))
        }
        r.addView(View(this).apply { background = Glass.pill(this@MainActivity, color, 0, 4f) },
            LinearLayout.LayoutParams(px(3), px(16)).apply { marginEnd = px(10) })
        r.addView(label(day.replaceFirstChar { it.uppercase() }, 14.5f, R.color.text, true),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(label("$count", 12f, R.color.muted, true))
        p.addView(r)
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
        val items = filteredHist()
        if (items.isEmpty()) { toast("No hay nada que descargar"); return }
        pendingHtml = buildHtml(items)
        val name = "ryx_historial_" +
            java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.getDefault()).format(java.util.Date()) + ".html"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try { startActivityForResult(i, REQ_HTML) } catch (e: Exception) { toast("No pude abrir el guardado de archivos") }
    }

    /** Arma el HTML completo: todo el texto, agrupado por día y por app/sistema. */
    private fun buildHtml(items: List<Prefs.HistItem>): String {
        val apps = items.filter { !Sys.isSystemPkg(it.pkg) }
        val sys = items.filter { Sys.isSystemPkg(it.pkg) }
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Ryx Island · Historial</title><style>")
        sb.append("*{box-sizing:border-box}body{margin:0;padding:24px;background:#0f1115;color:#e9edf5;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;line-height:1.5}")
        sb.append("h1{font-size:22px;margin:0 0 4px}.sub{color:#94a0b4;font-size:13px;margin-bottom:18px}")
        sb.append("h2{font-size:16px;margin:28px 0 8px;padding-left:10px;border-left:4px solid #6a62e8}")
        sb.append("h2.sysc{border-color:#2daa55;color:#7ee2a0}.app{color:#b9b3ff}")
        sb.append("h3{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:#94a0b4;margin:18px 0 6px}")
        sb.append(".day{font-weight:700;color:#ffb26b;font-size:14px;margin:16px 0 2px}")
        sb.append(".card{background:#181b22;border:1px solid #262b36;border-radius:14px;padding:10px 14px;margin:6px 0}")
        sb.append(".it{padding:9px 0;border-top:1px solid #222733}.it:first-child{border-top:0}")
        sb.append(".t{font-weight:700}.x{color:#c7cfdd;white-space:pre-wrap;word-break:break-word}")
        sb.append(".meta{color:#7f8b9e;font-size:11px;margin-top:4px}img{max-width:100%;border-radius:10px;margin-top:8px;display:block}")
        sb.append("</style></head><body>")
        sb.append("<h1>Ryx Island · Historial de notificaciones</h1><div class=\"sub\">")
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
        if (h.text.isNotEmpty()) sb.append("<div class=\"x\">").append(esc(h.text)).append("</div>")
        if (h.img.isNotEmpty()) {
            val f = java.io.File(Prefs.histDir(this), h.img)
            if (f.exists() && f.length() < 600_000) try {
                val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                sb.append("<img src=\"data:image/png;base64,").append(b64).append("\">")
            } catch (e: Exception) {}
        }
        sb.append("<div class=\"meta\">")
        if (h.kind.isNotEmpty()) sb.append(esc(h.kind)).append(" · ")
        sb.append(esc(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.ts))))
        sb.append("</div></div>")
        return sb.toString()
    }

    private fun appNameOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
            background = Glass.pill(this@MainActivity, (color and 0x00FFFFFF) or 0x22000000, (color and 0x00FFFFFF) or 0x66000000, 14f)
        })
        p.addView(r)
    }

    /** Recuadro resumen (número + etiqueta) con color propio. */
    private fun summaryChip(iconName: String, color: Int, value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(12), px(12), px(12), px(12))
        background = Glass.pill(this@MainActivity, (color and 0x00FFFFFF) or 0x22000000, (color and 0x00FFFFFF) or 0x55000000, 18f)
        addView(tileIcon(iconName, (color and 0x00FFFFFF) or 0x4D000000, color), LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(10) })
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(value, 18f, R.color.text, true))
        t.addView(label(caption, 12f, R.color.muted))
        addView(t)
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
            background = Glass.pill(this@MainActivity, 0x22FFFFFF, 0, 10f)
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

    /** Grupo de historial de una aplicación (con su icono), desplegable. */
    private fun appHistoryGroup(p: LinearLayout, pkg: String, items: List<Prefs.HistItem>) {
        val pm = packageManager
        val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val key = "h_$pkg"
        val open = key !in expandedGroups          // por defecto abiertas
        val c = card()
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (open) expandedGroups.add(key) else expandedGroups.remove(key); render() }
        }
        h.addView(ShapedImageView(this).apply {
            shape = Prefs.iconShape(this@MainActivity); setImageDrawable(appIcon(pkg)); scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(appName, 17f, R.color.text, true))
        t.addView(label("${items.size} notificaciones", 12f, R.color.muted))
        h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(icon(if (open) "chev_up" else "chev_down", 22, Glass.MUTED))
        c.addView(h)
        if (open) items.take(25).forEach { h2 -> c.addView(historyItemRow(h2, px(54))) }
        p.addView(c, lp(10))
    }

    /** Grupo de historial del sistema (Batería, Wi-Fi, Red…), desplegable. */
    private fun sysHistoryGroup(p: LinearLayout, kind: String, items: List<Prefs.HistItem>) {
        val key = "hs_$kind"
        val open = key !in expandedGroups
        val color = Sys.color(kind)
        val c = card()
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (open) expandedGroups.add(key) else expandedGroups.remove(key); render() }
        }
        h.addView(tileIcon(Sys.symbol(kind), (color and 0x00FFFFFF) or 0x33000000, color),
            LinearLayout.LayoutParams(px(42), px(42)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(Sys.label(kind), 17f, R.color.text, true))
        t.addView(label("${items.size} avisos del sistema", 12f, R.color.muted))
        h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(icon(if (open) "chev_up" else "chev_down", 22, Glass.MUTED))
        c.addView(h)
        if (open) items.take(25).forEach { h2 -> c.addView(historyItemRow(h2, px(54))) }
        p.addView(c, lp(10))
    }

    /** Una entrada del historial: título, texto, imagen (si hay), tipo y hora. */
    private fun historyItemRow(h2: Prefs.HistItem, indent: Int): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(indent, px(10), 0, 0) }
        if (h2.title.isNotEmpty()) r.addView(label(h2.title, 14f, R.color.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (h2.text.isNotEmpty()) r.addView(label(h2.text, 13f, R.color.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (h2.img.isNotEmpty()) {
            val f = java.io.File(Prefs.histDir(this), h2.img)
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
            if (bmp != null) {
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, px(14).toFloat()) }
                    }
                    clipToOutline = true
                    setOnClickListener { showImage(f.absolutePath) }
                }
                r.addView(iv, LinearLayout.LayoutParams(px(if (h2.kind == "Sticker") 110 else 190), px(if (h2.kind == "Sticker") 110 else 130)).apply { topMargin = px(6) })
            }
        }
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(2), 0, 0) }
        if (h2.kind.isNotEmpty()) {
            foot.addView(label(h2.kind, 11f, R.color.gold, true))
            foot.addView(View(this), LinearLayout.LayoutParams(px(8), px(1)))
        }
        foot.addView(label(relative(h2.ts), 11f, R.color.gold))
        r.addView(foot)
        return r
    }

    private fun relative(ts: Long): String = android.text.format.DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

    private fun showImage(path: String) {
        val bmp = BitmapFactory.decodeFile(path) ?: return
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; setPadding(px(8), px(8), px(8), px(8))
            background = Glass.card(this@MainActivity, 24f, true)
            setOnClickListener { dlg.dismiss() }
        })
        dlg.window?.apply { setBackgroundDrawable(ColorDrawable(0)); setDimAmount(0.7f) }
        dlg.show()
    }

    private fun buildPerms(p: LinearLayout) {
        val c = card()
        permsContent(c)
        p.addView(c, lp(8))
    }

    private fun permsContent(b: LinearLayout) {
        permRow(b, "Shizuku (solo para ocultar avisos emergentes)", "Opcional. Estado: ${Shell.shizukuStatus(this)}. El formato libre y las burbujas no lo necesitan.",
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
        permRow(b, "Dispositivos cercanos", "Detecta conexiones Bluetooth",
            Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 1)
        }
        permRow(b, "Accesibilidad (Formato libre del launcher)", "Para pulsar «Formato libre» en tu launcher al abrir una ventana flotante",
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
                background = Glass.pill(this@MainActivity, if (on) 0x22FFFFFF else 0, if (on) t.accent else 0, 18f)
                setOnClickListener { Prefs.setAppTheme(this@MainActivity, t.id); Glass.applyTheme(this@MainActivity); recreate() }
            }
            r.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(t.accent) }
            }, LinearLayout.LayoutParams(px(28), px(28)).apply { marginEnd = px(14) })
            r.addView(label(t.label, 16f, R.color.text, true))
            b.addView(r, lp(6))
        }
    }

    private fun backupDialog() = sheet("Copia de seguridad") { b ->
        b.addView(button("Copiar ajustes al portapapeles", false) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ryx", Prefs.exportAll(this))); toast("Ajustes copiados")
        })
        b.addView(button("Importar desde el portapapeles", false) {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (Prefs.importAll(this, txt)) { applyAll(); render(); toast("Ajustes importados") } else toast("El portapapeles no tiene una copia válida")
        }, lp(8))
    }

    private fun cloudDialog() = sheet("Copia en la nube") { b ->
        val last = Prefs.cloudLast(this)
        b.addView(label(if (Backup.configured(this)) "Carpeta elegida ✔" + (if (last > 0) "\nÚltima copia: " +
            android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS) else "") else "Todavía no hay carpeta elegida.", 14f, R.color.text), lp(6))
        b.addView(button("Elegir carpeta (Google Drive u otra)", true) { pickCloudFolder() }, lp(10))
        b.addView(button("Guardar copia ahora", false) {
            toast("Guardando…")
            Thread {
                val r = Backup.upload(this)
                runOnUiThread { toast(if (r.isEmpty()) "Copia guardada" else r) }
            }.start()
        }, lp(8))
        b.addView(button("Restaurar desde una copia", false) { pickRestore() }, lp(8))
        b.addView(label("En el selector que se abre, entra a Google Drive y elige o crea una carpeta. Ryx guarda ahí un archivo " +
            "(ryx_respaldo.zip) con tus ajustes, el historial y sus imágenes, y lo actualiza solo cuando llegan notificaciones. " +
            "Como queda en tu Drive, sigue ahí aunque desinstales la app; al reinstalar, usa «Restaurar».", 12f, R.color.muted), lp(10))
        b.addView(label("Además, Android respalda estos datos con la copia de seguridad de tu cuenta de Google y los " +
            "restaura al reinstalar desde la tienda. Al instalar un APK a mano, Android a veces no los restaura: para eso sirve la carpeta.",
            12f, R.color.muted), lp(8))
    }

    private fun devDialog() = sheet("Opciones de desarrollador") { b ->
        b.addView(label("Registro de la última apertura en ventana flotante:", 13f, R.color.muted))
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
        d.window?.setBackgroundDrawable(Glass.solid(this, 28f)); return d
    }

    private fun dialog(title: String, msg: String) =
        glass(AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Cerrar", null).show())

    private fun sheet(title: String, build: (LinearLayout) -> Unit) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Glass.solid(this@MainActivity, 30f)
            setPadding(px(22), px(20), px(22), px(16))
        }
        box.addView(label(title, 20f, R.color.text, true))
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        build(inner)
        box.addView(ScrollView(this).apply { addView(inner); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = px(8) })
        box.addView(button("Cerrar", true) { dlg.dismiss() }, lp(12))
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(0)); setDimAmount(0.65f)
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.8f).toInt())
        }
        dlg.setOnDismissListener { render() }
        dlg.show()
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

    /** Cuadro redondeado con icono, como los de Smart Island. */
    private fun tileIcon(name: String, bg: Int = 0xFF2A2E38.toInt(), tint: Int = Glass.ACCENT): View =
        FrameLayout(this).apply {
            background = Glass.tile(this@MainActivity, bg, 15f)
            addView(icon(name, 24, tint).apply { layoutParams = FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER) })
        }

    private fun heading(p: LinearLayout, t: String) =
        p.addView(label(t, 13f, R.color.muted, true).apply { letterSpacing = 0.12f }, lp(20))

    private fun item(p: LinearLayout, iconName: String, color: Int, title: String, sub: String, trailing: String, click: () -> Unit) {
        val c = card().apply { setOnClickListener { click() } }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon(iconName, color, Color.WHITE), LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(14) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 17f, R.color.text, true))
        t.addView(label(sub, 13f, R.color.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing.isNotEmpty()) r.addView(label("$trailing ", 13f, R.color.gold).apply { setPadding(px(6), 0, px(4), 0) })
        r.addView(icon("chev_right", 22, Glass.MUTED))
        c.addView(r); p.addView(c, lp(10))
    }

    private fun section(p: LinearLayout, title: String, sub: String, body: (LinearLayout) -> Unit) {
        val open = "sec_$title" in expandedGroups
        val c = if (open) card() else LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(14)) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { val k = "sec_$title"; if (open) expandedGroups.remove(k) else expandedGroups.add(k); render() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 18f, R.color.text, true)); t.addView(label(sub, 14f, R.color.muted))
        top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(icon(if (open) "chev_up" else "chev_down", 22, Glass.MUTED))
        c.addView(top)
        if (open) { val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body(b); c.addView(b, lp(8)) }
        p.addView(c, lp(6))
    }

    private fun permRow(p: LinearLayout, title: String, sub: String, ok: Boolean, click: () -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tileIcon(if (ok) "shield" else "battery_alert", if (ok) 0xFF21402C.toInt() else 0xFF4A3A1E.toInt(),
            if (ok) 0xFF3DDC84.toInt() else 0xFFF2A01F.toInt()),
            LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(title, 16f, R.color.text, true))
        t.addView(label(if (ok) "Concedido" else "Acción necesaria", 12f, if (ok) R.color.green else R.color.gold, true))
        t.addView(label(sub, 12f, R.color.muted))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(button(if (ok) "Abrir" else "Conceder", !ok) { click() })
        p.addView(r, lp(12))
    }

    private fun segmented(opts: List<String>, selected: Int, green: Boolean = false, onPick: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.pill(this@MainActivity, 0x22FFFFFF, 0x22FFFFFF, 24f)
            setPadding(px(5), px(5), px(5), px(5))
            opts.forEachIndexed { i, o ->
                val sel = i == selected
                addView(TextView(this@MainActivity).apply {
                    text = o; gravity = Gravity.CENTER; textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (sel) (if (green) 0xFF2B1608.toInt() else Glass.TEXT) else Glass.MUTED)
                    setPadding(px(6), px(13), px(6), px(13))
                    background = Glass.pill(this@MainActivity,
                        if (sel) (if (green) Glass.ACCENT else 0xFF08090C.toInt()) else 0, 0, 20f)
                    setOnClickListener { onPick(i); render() }
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }

    private fun seekRow(parent: LinearLayout, title: String, unit: String, lo: Int, hi: Int, value: Int, onChange: (Int) -> Unit) {
        val lbl = label("$title: $value $unit", 14f, R.color.muted)
        val seek = SeekBar(this).apply {
            max = hi - lo; progress = value.coerceIn(lo, hi) - lo
            progressTintList = ColorStateList.valueOf(Glass.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    lbl.text = "$title: ${p + lo} $unit"; onChange(p + lo); previewView?.refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { svc()?.let { it.applySettings(); it.showTest() } }
            })
        }
        parent.addView(lbl, lp(12)); parent.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun rowWithSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, px(4))
            val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(title, 18f, R.color.text, true))
            if (sub.isNotEmpty()) t.addView(label(sub, 14f, R.color.muted))
            addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(makeSwitch(checked, onChange))
        }

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(if (color == R.color.gold) Glass.ACCENT else col(color))
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Fila de datos rápidos bajo la cabecera: pastillas con «etiqueta: valor», estilo panel moderno. */
    private fun heroStat(p: LinearLayout, color: Int, items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rgb = color and 0x00FFFFFF
        for ((i, kv) in items.withIndex()) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), px(11), px(14), px(11))
                background = Glass.pill(this@MainActivity, (rgb or 0x26000000), (rgb or 0x66000000).toInt(), 18f)
            }
            cell.addView(label(kv.first.uppercase(), 10f, R.color.muted, true).apply { letterSpacing = 0.1f })
            cell.addView(label(kv.second, 13.5f, R.color.text, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(3) })
            row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = px(8) })
        }
        p.addView(row, lp(10))
    }

    /** Rejilla de 2 columnas de «fichas» con icono: título, dato grande y pie. Para Inicio y Ajustes. */
    private fun statGrid(p: LinearLayout, cells: List<Triple<String, String, String>>) {
        cells.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (iconName, value, title) ->
                val c = card().apply { background = Glass.card(this@MainActivity, 24f) }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(tileIcon(iconName, 0xFF232833.toInt(), Glass.ACCENT),
                    LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(10) })
                top.addView(label(value, 21f, R.color.text, true).apply { letterSpacing = -0.02f },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                c.addView(top)
                c.addView(label(title, 12.5f, R.color.muted).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, lp(9))
                r.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(5); marginEnd = px(5) })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            p.addView(r, lp(10))
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.card(this@MainActivity, 28f)
        setPadding(px(16), px(16), px(16), px(16))
    }

    private fun makeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = GlassSwitch(this).apply {
        this.checked = checked
        this.onChange = onChange
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(px(18), px(10), px(18), px(10))
        setTextColor(if (filled) 0xFF2B1608.toInt() else Glass.ACCENT)
        background = if (filled) Glass.pill(this@MainActivity, Glass.ACCENT, 0, 22f)
            else Glass.pill(this@MainActivity, 0x14FFFFFF, Glass.accentA(0x88), 22f)
        setOnClickListener { onClick() }
    }
}
