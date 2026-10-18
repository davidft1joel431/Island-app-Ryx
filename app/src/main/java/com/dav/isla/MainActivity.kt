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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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
    private var windowPreview: WindowPreview? = null
    private var previewOpen = false
    private var appQuery = ""
    private var rootStatus = "Sin comprobar"
    private val actionNames = listOf("Nada", "Abrir app", "Ventana flotante", "Descartar tarjeta",
        "Siguiente tarjeta", "Tarjeta anterior", "Contraer isla", "Ver detalle")
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { render() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { render() } }
    private var appsBox: LinearLayout? = null
    private val iconCache = HashMap<String, Drawable?>()
    private var appListCache: List<Pair<String, String>>? = null
    private val appList: List<Pair<String, String>> get() = appListCache ?: run {
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
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
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(px(50), px(50)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label("Ryx", 28f, R.color.text, true).apply { letterSpacing = -0.02f })
        names.addView(label("Island · por David Joel", 13f, R.color.muted))
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
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListener(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
        } catch (e: Throwable) {}
        Backup.maybeUpload(this, 60 * 60_000L)
        offerRestore()
        if (Prefs.fwMode(this) == 2 && !RyxAccessibility.isEnabled(this) && !Prefs.a11yAsked(this)) {
            Prefs.setA11yAsked(this, true); askAccessibility()
        }
        if (System.currentTimeMillis() - Prefs.updLast(this) > 12 * 3600_000L) checkUpdates(false)
    }

    private val REQ_TREE = 71
    private val REQ_RESTORE = 72

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
                if (sel) background = Glass.pill(this@MainActivity, Glass.accentA(0x33), 0, 26f)
                setOnClickListener { tab = i; subPage = null; render() }
            }
            b.addView(icon(ic, 22, colr))
            b.addView(label(name, 11f, R.color.text, true).apply { setTextColor(colr); gravity = Gravity.CENTER })
            tabBar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(2); marginEnd = px(2) })
        }
        content.removeAllViews()
        previewView = null; windowPreview = null; appsBox = null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(6), px(18), px(24))
        }
        val sp = subPage
        if (sp != null) {
            val back = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { subPage = null; render() }
            }
            back.addView(icon("back", 24, Glass.TEXT))
            back.addView(label(if (sp == "history") "Historial de notificaciones" else "Permisos", 20f, R.color.text, true),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(12) })
            page.addView(back, lp(4))
            if (sp == "history") buildHistory(page) else buildPerms(page)
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
        val fixed: View? = when (tab) { 1 -> buildPreview(); 3 -> buildWindowPreview(); else -> null }
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

    private fun buildWindowPreview(): View {
        val c = card().apply { setPadding(px(12), px(12), px(12), px(12)) }
        c.addView(label("Así se abrirá la ventana", 15f, R.color.text, true))
        c.addView(label("Crece desde la isla con la animación elegida", 12f, R.color.muted))
        val wp = WindowPreview(this)
        windowPreview = wp
        c.addView(wp, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) })
        c.addView(button("▶ Reproducir animación", true) { wp.play() }, lp(8))
        return c
    }

    // ---------------- Tarjetas con titulo ----------------

    private fun titled(p: LinearLayout, title: String, sub: String = ""): LinearLayout {
        val c = card()
        c.addView(label(title, 18f, R.color.text, true))
        if (sub.isNotEmpty()) c.addView(label(sub, 13f, R.color.muted))
        p.addView(c, lp(12))
        return c
    }

    // ---------------- INICIO ----------------

    private fun buildHome(p: LinearLayout) {
        val main = card()
        main.addView(rowWithSwitch("Ryx Island", if (Prefs.islandOn(this)) "Activa" else "Apagada",
            Prefs.islandOn(this)) { v -> Prefs.setIslandOn(this, v); applyAll(); render() })
        p.addView(main, lp(8))
        p.addView(segmented(listOf("Vertical", "Horizontal"), if (landscapeView) 1 else 0) {
            landscapeView = it == 1; render()
        }, lp(12))

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
        buildTest(p)
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
        seekRow(ge, "Distancia para deslizar", "dp", 20, 80, Prefs.swipeDp(this)) { Prefs.setSwipeDp(this, it) }
        ge.addView(label("Con la isla contraída: tocar o jalar hacia abajo la despliega. " +
            "En la vista de detalle: tocar abre la app, jalar hacia abajo usa el gesto de arriba y hacia arriba vuelve. " +
            "Prueba con «Simular acumulación» en la pestaña Isla.", 12f, R.color.muted), lp(8))
    }

    private val swatchColors = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF2A2E38.toInt(), 0xFFFF8A4C.toInt(),
        0xFFFF5A5F.toInt(), 0xFFFF6B9A.toInt(), 0xFFB388FF.toInt(), 0xFF4DA3FF.toInt(), 0xFF22E5FF.toInt(),
        0xFF4ADE80.toInt(), 0xFFFFC857.toInt())

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
        val only = titled(p, "Solo la isla", "La isla registra y muestra; el sistema se calla")
        only.addView(rowWithSwitch("Bloquear notificaciones del sistema", "Quita la del panel cuando la isla la muestra",
            Prefs.blockSystem(this)) { Prefs.setBlockSystem(this, it) })
        only.addView(rowWithSwitch("Ocultar avisos emergentes (Shizuku/root)", "Evita el aviso que baja desde arriba",
            Prefs.hideHeadsUp(this)) { v ->
            Thread { val r = Shell.setHeadsUp(this, !v); runOnUiThread {
                if (r.first == 0) Prefs.setHideHeadsUp(this, v) else { toast("No se pudo (¿root denegado?)"); render() } } }.start()
        })
        only.addView(rowWithSwitch("Mostrar con el teléfono bloqueado", "", Prefs.lockScreen(this)) { Prefs.setLockScreen(this, it) })
        only.addView(label("Llamadas, multimedia, navegación y alarmas se quedan en el sistema para no romper sus controles. " +
            "Sin root, Android puede mostrar el aviso un instante antes de que la isla lo quite.", 12f, R.color.muted), lp(8))

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

        val ap = titled(p, "Apps", "Apague las que no quiera ver en la isla")
        ap.addView(editText("Buscar app…", appQuery) { q -> appQuery = q; fillApps() }, lp(8))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsBox = box
        ap.addView(box, lp(6))
        fillApps()
    }

    private fun appIcon(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
    }

    private fun fillApps() {
        val box = appsBox ?: return
        box.removeAllViews()
        val q = appQuery.trim().lowercase()
        val list = appList.filter { q.isEmpty() || it.second.lowercase().contains(q) }
        val blocked = Prefs.blocked(this)
        list.take(40).forEach { (pkg, name) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(ShapedImageView(this).apply {
                shape = Prefs.iconShape(this@MainActivity); setImageDrawable(appIcon(pkg)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(12) })
            r.addView(label(name, 15f, R.color.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(makeSwitch(pkg !in blocked) { on ->
                val s = Prefs.blocked(this).toMutableSet(); if (on) s.remove(pkg) else s.add(pkg); Prefs.setBlocked(this, s)
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
        val fz = titled(p, "Cómo abrir la ventana flotante", "Con root (Magisk) o Shizuku se abre en segundo plano")
        val order = intArrayOf(0, 1, 3, 2)               // posición del botón → método guardado
        val mth = Prefs.shellMethod(this)
        fz.addView(segmented(listOf("Auto", "Shizuku", "Sin privilegios", "Root"),
            order.indexOf(mth).coerceAtLeast(0), true) { Prefs.setShellMethod(this, order[it]) }, lp(10))
        fz.addView(label(when (mth) {
            0 -> "Auto: usa Shizuku si está activo; si no, usa root (Magisk pedirá permiso una sola vez). La ventana se abre en segundo plano, sin sacarte de tu app. " +
                "Sin ninguno de los dos, abre con las opciones de Android."
            1 -> "Solo Shizuku. Si no está activo, la ventana no se podrá forzar."
            3 -> "Sin privilegios: no necesita Shizuku ni root. La ventana se abre, pero Ryx no puede moverla ni comprobar si quedó " +
                "flotante; en ROMs sin barra de ventana usa las asas de Ryx (experimental) o cambia a Shizuku."
            else -> "Root: solo se usa si lo eliges aquí. Deja el teléfono más expuesto, por eso nunca se usa por su cuenta."
        }, 12f, R.color.muted), lp(8))
        if (mth == 0 || mth == 1) {
            val shz = Shell.shizukuStatus(this)
            statusRow(fz, "Shizuku", shz, shz == "Listo")
            when {
                !Shell.shizukuInstalled(this) -> fz.addView(button("Descargar Shizuku", true) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                }, lp(10))
                !Shell.shizukuRunning() -> {
                    fz.addView(button("Abrir Shizuku para iniciarlo", true) {
                        packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)?.let { startActivity(it) }
                    }, lp(10))
                    fz.addView(label("Sin root, se inicia con «Depuración inalámbrica» (dentro de Shizuku → Iniciar mediante " +
                        "depuración inalámbrica). Hay que repetirlo tras reiniciar el teléfono.", 12f, R.color.muted), lp(8))
                }
                !Shell.shizukuGranted() -> fz.addView(button("Dar permiso a Ryx Island", true) {
                    Shell.requestShizuku()
                }, lp(10))
            }
            fz.addView(label("Shizuku no da superusuario: Ryx solo puede ejecutar comandos de shell (como adb) mientras Shizuku " +
                "esté activo y tú lo hayas autorizado. Se apaga al reiniciar y puedes revocar el permiso desde la app Shizuku.",
                12f, R.color.muted), lp(8))
        }
        if (mth == 2) {
            statusRow(fz, "Root", rootStatus, rootStatus.startsWith("Disponible"))
            fz.addView(button("Comprobar root", false) {
                rootStatus = "Comprobando…"; render()
                Thread {
                    val r = Root.run("id")
                    runOnUiThread { rootStatus = if (r.first == 0) "Disponible" else "No disponible"; render() }
                }.start()
            }, lp(8))
        }
        val cp = titled(p, "Compatibilidad de la ROM", "${Build.DISPLAY} · Android ${Build.VERSION.RELEASE}")
        cp.addView(label("Para abrir ventanas libres hacen falta «Permitir ventanas de forma libre» y «Forzar que las actividades " +
            "sean redimensionables» (Opciones de desarrollador). En Android 17 el modo escritorio es solo para pantallas externas: " +
            "en el teléfono el sistema no dibuja la barra de la ventana para apps de terceros (sí lo hace el «Formato libre» del " +
            "lanzador, porque es una función interna). Para mover la ventana desde Ryx usa Shizuku (no es root) con las asas de Ryx.",
            12f, R.color.muted), lp(8))
        cp.addView(button("Abrir Opciones de desarrollador", false) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (e: Exception) { toast("No se pudo abrir") }
        }, lp(10))
        cp.addView(button("Comprobar soporte", false) {
            toast("Comprobando…")
            dialog("Soporte de ventanas libres", FloatingWindow.support(this))
        }, lp(10))
        cp.addView(button("Activar y reiniciar el teléfono", false) {
            if (!Shell.privileged(this)) {
                dialog("Sin privilegios", "Sin Shizuku ni root no se puede cambiar ese ajuste desde la app. " +
                    "Actívalo a mano en Opciones de desarrollador («Permitir ventanas de forma libre» y la opción de modo escritorio) y reinicia.")
                return@button
            }
            glass(AlertDialog.Builder(this).setTitle("Reiniciar teléfono")
                .setMessage("Se activarán las ventanas libres y el teléfono se reiniciará ahora. Guarda lo que estés haciendo.")
                .setPositiveButton("Reiniciar") { _, _ ->
                    Thread { Shell.run(this, FloatingWindow.PREP); Shell.run(this, "svc power reboot") }.start()
                }.setNegativeButton("Cancelar", null).show())
        }, lp(8))
        val so = titled(p, "Ventana flotante · solo accesibilidad", "Usa únicamente la función de Smart Island desde el servicio de accesibilidad")
        so.addView(rowWithSwitch("Usar solo este método", "Sin Shizuku, root, Recientes ni el menú del launcher: no te saca de tu app",
            Prefs.fwSolo(this)) { Prefs.setFwSolo(this, it); render() })
        so.addView(segmented(listOf("Formato libre del sistema", "Directo"), Prefs.fwSoloMode(this), true) { Prefs.setFwSoloMode(this, it); render() }, lp(8))
        so.addView(label(if (Prefs.fwSoloMode(this) == 0)
            "«Formato libre del sistema»: abre la ventana real del sistema, con su barra, iconos y esquinas, pulsando por ti «Formato libre» en tu launcher. " +
            "Ryx pone una captura de tu pantalla como cortina para que no veas el cambio, así que durante unos 2-4 segundos la pantalla se ve congelada: " +
            "no toques nada hasta que aparezca la ventana. Sale con el tamaño del sistema; muévela y cámbiala con su propia barra."
            else "«Directo»: pide la ventana con tu tamaño desde accesibilidad (función de Smart Island). No lleva la barra del sistema, por eso Ryx dibuja la suya.",
            12f, R.color.muted), lp(6))
        so.addView(label("Ryx lanza la app desde su servicio de accesibilidad con el modo libre (windowingMode 5) y tu tamaño, igual que Smart Island, " +
            "y en el registro anota las medidas reales de la ventana. Como Android no dibuja la barra del sistema en apps de terceros en el teléfono, " +
            "Ryx pone su propia barra de título encima (mover, cambiar tamaño). Para la barra del sistema activa «Habilitar funciones de experiencia de escritorio» " +
            "en Opciones de desarrollador (Android 16 o superior; elige que valga también en el teléfono) y reinicia. Apágalo para volver a los otros métodos de abajo.", 12f, R.color.muted), lp(6))
        so.addView(label(if (RyxAccessibility.isEnabled(this)) "Servicio de accesibilidad: activo ✔" else
            "Servicio de accesibilidad: apagado. Actívalo para usar este método.", 12f, R.color.text), lp(8))
        so.addView(button(if (RyxAccessibility.isEnabled(this)) "Ajustes de accesibilidad" else "Activar accesibilidad", !RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) openAccessibility() else askAccessibility()
        }, lp(8))
        so.addView(button("Abrir Opciones de desarrollador", false) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (e: Exception) { toast("No se pudo abrir") }
        }, lp(8))
        so.addView(rowWithSwitch("Barra de Ryx sobre la ventana", "Mover, cambiar tamaño y cerrar la barra", Prefs.fwFrame(this)) { Prefs.setFwFrame(this, it) })
        so.addView(button("▶ Probar solo accesibilidad", true) {
            val pkg = Prefs.fwTestPkg(this)
            if (pkg.isEmpty()) { toast("Elige primero una app en «Probar ventana flotante» (abajo)"); return@button }
            toast("Abriendo…")
            Thread { val log = FloatingWindow.openSolo(this, pkg); runOnUiThread { render(); dialog("Resultado de la prueba", log) } }.start()
        }, lp(8))

        val ds = titled(p, "Barra del sistema en ventanas libres", "Con root (Magisk) o Shizuku · una sola vez · instantáneo y sin cortina")
        ds.addView(label("Android dibuja la barra de título, los iconos y las esquinas redondeadas solo cuando el modo escritorio está activo, y en el teléfono viene apagado. " +
            "Este botón lo enciende (propiedades del sistema persist.wm.debug.desktop_experience_devopts=1 y desktop_mode_enforce_device_restrictions=false) y reinicia. " +
            "Después, con el método «Directo», las ventanas se abren al instante, con tu tamaño y con la barra real del sistema: sin captura, sin esperas y sin que accesibilidad pulse tu pantalla. " +
            "Es experimental y depende de tu ROM; «Quitar» lo deja como estaba.", 12f, R.color.muted), lp(6))
        ds.addView(button("Activar barra del sistema y reiniciar", true) {
            Thread {
                val ok = Shell.probeRoot() || Shell.shizukuGranted()
                runOnUiThread {
                    if (!ok) { dialog("Hace falta root o Shizuku", "No encuentro root (Magisk) ni Shizuku con permiso. Concede el permiso de superusuario a Ryx Island en Magisk o inicia Shizuku, y vuelve a intentarlo."); return@runOnUiThread }
                    glass(AlertDialog.Builder(this).setTitle("Activar y reiniciar")
                        .setMessage("Se activa el modo escritorio en la pantalla del teléfono y el teléfono se reinicia ahora. Si algo se ve raro, usa «Quitar y reiniciar». Guarda lo que estés haciendo.")
                        .setPositiveButton("Reiniciar") { _, _ ->
                            Thread {
                                Shell.runAdmin(FloatingWindow.DESKTOP_ON)
                                Prefs.setFwSoloMode(this, 1)
                                Prefs.setFwSolo(this, true)
                                Shell.runAdmin("svc power reboot")
                            }.start()
                        }.setNegativeButton("Cancelar", null).show())
                }
            }.start()
        }, lp(8))
        ds.addView(button("Quitar y reiniciar", false) {
            Thread {
                val ok = Shell.probeRoot() || Shell.shizukuGranted()
                runOnUiThread {
                    if (!ok) { dialog("Hace falta root o Shizuku", "No encuentro root (Magisk) ni Shizuku con permiso."); return@runOnUiThread }
                    glass(AlertDialog.Builder(this).setTitle("Quitar y reiniciar")
                        .setMessage("Se devuelve el modo escritorio a su valor normal y el teléfono se reinicia ahora.")
                        .setPositiveButton("Reiniciar") { _, _ ->
                            Thread { Shell.runAdmin(FloatingWindow.DESKTOP_OFF); Shell.runAdmin("svc power reboot") }.start()
                        }.setNegativeButton("Cancelar", null).show())
                }
            }.start()
        }, lp(8))
        ds.addView(button("Ver estado", false) {
            Thread {
                val a = Shell.runAdmin("getprop persist.wm.debug.desktop_experience_devopts").second.trim()
                val b = Shell.runAdmin("getprop persist.wm.debug.desktop_mode_enforce_device_restrictions").second.trim()
                val who = if (Shell.probeRoot()) "root" else if (Shell.shizukuGranted()) "Shizuku" else "ninguno"
                runOnUiThread {
                    dialog("Estado del modo escritorio", "Ejecutado con: $who\n" +
                        "desktop_experience_devopts = ${a.ifEmpty { "(vacío)" }}\n" +
                        "enforce_device_restrictions = ${b.ifEmpty { "(vacío)" }}\n\n" +
                        "Activado = 1 y false. Valor normal = vacío o -1 y true.")
                }
            }.start()
        }, lp(8))

        val g = titled(p, "Otros métodos de ventana flotante", "Se abre con el gesto «Ventana flotante» (por defecto, deslizar hacia abajo)")
        g.addView(rowWithSwitch("Forzar ventana nueva", "Aunque la app ya esté abierta", Prefs.forceNew(this)) { Prefs.setForceNew(this, it) })
        g.addView(label("Apertura sin privilegios", 13f, R.color.muted, true), lp(12))
        g.addView(segmented(listOf("Launcher", "Directo", "Animación"), when (Prefs.fwMode(this)) { 2 -> 0; 0 -> 1; else -> 2 }, true) {
            Prefs.setFwMode(this, when (it) { 0 -> 2; 1 -> 0; else -> 1 })
            if (it == 0 && !RyxAccessibility.isEnabled(this)) askAccessibility()
        }, lp(6))
        g.addView(label("«Launcher» usa el «Formato libre» de tu launcher: Ryx mantiene pulsado el icono y toca esa opción por ti " +
            "(servicio de accesibilidad), así que la ventana trae la barra del sistema para moverla y cambiarle el tamaño. " +
            "Sale con el tamaño que elige el sistema. «Directo» y «Animación» piden la ventana con tu tamaño, pero sin barra en esta ROM.",
            12f, R.color.muted), lp(6))
        g.addView(rowWithSwitch("Mantener mi app de fondo", "La ventana se abre encima de la app en la que estás, sin ir al inicio (usa Recientes)",
            Prefs.fwKeepApp(this)) { Prefs.setFwKeepApp(this, it) })
        g.addView(label(if (RyxAccessibility.isEnabled(this)) "Servicio de accesibilidad: activo ✔" else
            "Servicio de accesibilidad: apagado. Actívalo para usar «Launcher».", 12f, R.color.text), lp(8))
        g.addView(button(if (RyxAccessibility.isEnabled(this)) "Ajustes de accesibilidad" else "Activar accesibilidad", !RyxAccessibility.isEnabled(this)) {
            if (RyxAccessibility.isEnabled(this)) openAccessibility() else askAccessibility()
        }, lp(8))
        g.addView(label("Si Android no te deja activarlo (opción en gris): Ajustes → Apps → Ryx Island → ⋮ → «Permitir ajustes restringidos», " +
            "y vuelve a intentarlo.", 12f, R.color.muted), lp(6))
        g.addView(rowWithSwitch("Usar mi tamaño y posición", "Apágalo para que Android elija el tamaño, como en el lanzador",
            Prefs.fwOwnBounds(this)) { Prefs.setFwOwnBounds(this, it) })
        g.addView(rowWithSwitch("Asas de Ryx para mover y cambiar tamaño", "Úsalo si la ventana sale sin barra. Con Shizuku mueve la real; sin privilegios la reabre en el nuevo sitio (experimental)",
            Prefs.fwFrame(this)) { Prefs.setFwFrame(this, it) })

        val sz = titled(p, "Tamaño y posición")
        seekRow(sz, "Ancho", "%", 40, 100, Prefs.fwWidthPct(this)) { Prefs.setFwWidthPct(this, it) }
        seekRow(sz, "Alto", "%", 30, 90, Prefs.fwHeightPct(this)) { Prefs.setFwHeightPct(this, it) }
        sz.addView(label("Posición", 13f, R.color.muted, true), lp(12))
        sz.addView(segmented(listOf("Centro", "Arriba", "Abajo"), Prefs.fwPos(this), true) { Prefs.setFwPos(this, it) }, lp(6))

        val an = titled(p, "Animación de apertura", "Resorte: crece desde la isla, como en HyperOS")
        an.addView(segmented(listOf("Resorte", "Desvanecer", "Deslizar"), Prefs.fwAnim(this), true) { Prefs.setFwAnim(this, it) }, lp(10))
        an.addView(label("Con root, el sistema usa su propia animación; sin privilegios solo se usa con «Con animación Ryx».", 12f, R.color.muted), lp(8))

        val t = titled(p, "Probar ventana flotante")
        val test = Prefs.fwTestPkg(this)
        val name = appList.firstOrNull { it.first == test }?.second ?: "Ninguna app elegida"
        t.addView(label("App de prueba: $name", 14f, R.color.text), lp(8))
        t.addView(button("Elegir app", false) { pickOneApp { pkg -> Prefs.setFwTestPkg(this, pkg); render() } }, lp(8))
        t.addView(button("▶ Probar ahora", true) {
            val pkg = Prefs.fwTestPkg(this)
            if (pkg.isEmpty()) { toast("Elige primero una app"); return@button }
            val go = {
                toast("Abriendo…")
                Thread { val log = FloatingWindow.open(this, pkg); runOnUiThread { render(); dialog("Resultado de la prueba", log) } }.start()
                Unit
            }
            if (Prefs.fwMode(this) == 2 && !RyxAccessibility.isEnabled(this)) askAccessibility { go() } else go()
        }, lp(8))

        val log = Prefs.lastLog(this)
        if (log.isNotEmpty()) {
            val d = titled(p, "Último diagnóstico")
            d.addView(label(log, 11f, R.color.muted).apply { typeface = Typeface.MONOSPACE }, lp(8))
            d.addView(button("Copiar registro", false) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ryx", log)); toast("Registro copiado")
            }, lp(8))
        }
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

    // ---------------- AJUSTES ----------------

    private fun buildSettings(p: LinearLayout) {
        val n = Prefs.histAll(this).size
        item(p, "palette", 0xFF7B5CE0.toInt(), "Tema de la app", "Color de acento de la aplicación", Glass.theme.label) { themeDialog() }
        item(p, "history", 0xFF6A62E8.toInt(), "Historial de notificaciones", "Ordenado por aplicación, con su icono", "$n") { subPage = "history"; render() }
        item(p, "shield", 0xFF2DAA55.toInt(), "Permisos", "Superposición, acceso a notificaciones, batería", "") { subPage = "perms"; render() }
        item(p, "backup", 0xFF3F7BEA.toInt(), "Copia de seguridad", "Exporta o importa todos los ajustes", "") { backupDialog() }
        item(p, "upload", 0xFF2D8CCB.toInt(), "Copia en la nube",
            "Historial y ajustes en Google Drive u otra carpeta", if (Backup.configured(this)) "Activa" else "") { cloudDialog() }
        item(p, "download", 0xFF2DAA55.toInt(), "Buscar actualizaciones", "Consulta la última versión en GitHub",
            "v${BuildConfig.VERSION_NAME}") { checkUpdates(true) }
        item(p, "code", 0xFF5F6B80.toInt(), "Opciones de desarrollador", "Registro de la ventana flotante", "") { devDialog() }
        item(p, "info", 0xFF5F6B80.toInt(), "Acerca de", "Versión y créditos", "") {
            dialog("Acerca de Ryx Island", "Ryx Island v${BuildConfig.VERSION_NAME}\nCreador: David Joel\n\nLa interfaz está inspirada en Smart Island, " +
                "de Animesh Gupta (licencia GPL-3.0): github.com/agupta07505/SmartIsland, y en Hyper Bridge.\n\n" +
                "Este código es propio y no copia el de esos proyectos. Si en el futuro se incorpora código ajeno, " +
                "hay que conservar su aviso de copyright y respetar su licencia.")
        }
    }

    private fun buildHistory(p: LinearLayout) {
        val top = card()
        top.addView(rowWithSwitch("Guardar historial", "Todo lo que pasa por la isla", Prefs.keepHistory(this)) { Prefs.setKeepHistory(this, it) })
        top.addView(button("Borrar historial", false) { Prefs.clearHist(this); render() }, lp(8))
        p.addView(top, lp(8))
        val all = Prefs.histAll(this)
        if (all.isEmpty()) { p.addView(label("Todavía no hay notificaciones.", 14f, R.color.muted), lp(16)); return }
        val groups = all.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }
        val pm = packageManager
        groups.forEach { (pkg, items) ->
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
            if (open) items.take(25).forEach { h2 ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(54), px(10), 0, 0) }
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
                if (h2.kind.isNotEmpty()) r.addView(label(h2.kind, 11f, R.color.gold, true))
                r.addView(label(android.text.format.DateUtils.getRelativeTimeSpanString(h2.ts, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(), 11f, R.color.gold))
                c.addView(r)
            }
            p.addView(c, lp(10))
        }
    }

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
        permRow(b, "Shizuku (forzar ventana flotante)", "Estado: ${Shell.shizukuStatus(this)}. Sin root se inicia por depuración inalámbrica.",
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
        b.addView(label(Prefs.lastLog(this).ifEmpty { "Todavía no hay registro. Haz una prueba en la pestaña Ventana." }, 12f, R.color.text), lp(8))
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
                    lbl.text = "$title: ${p + lo} $unit"; onChange(p + lo); previewView?.refresh(); windowPreview?.refresh()
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
