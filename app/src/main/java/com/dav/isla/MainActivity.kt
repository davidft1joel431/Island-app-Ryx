package com.dav.isla

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.view.ViewOutlineProvider
import android.view.Window
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var activePill: TextView
    private var tab = 0
    private var landscapeView = false
    private val expandedGroups = HashSet<String>().apply { add("power") }
    private var demoShowing = 0
    private var previewView: IslandPreview? = null
    private var previewOpen = false

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Cabecera: logo redondo, nombre y estado
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(16), px(20), px(10))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(px(54), px(54)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label("Ryx Island", 24f, R.color.text, true))
        names.addView(label("Isla dinámica flotante", 14f, R.color.muted))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(14) })
        activePill = label("", 14f, R.color.green, true).apply { setPadding(px(16), px(8), px(16), px(8)) }
        head.addView(activePill)
        rootView.addView(head)

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Glass.pill(this@MainActivity, 0xE6171910.toInt(), 0, 0f)
            setPadding(px(8), px(10), px(8), px(10))
        }
        rootView.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(rootView)
        render()
    }

    override fun onResume() { super.onResume(); updatePill() }

    private fun updatePill() {
        val on = Prefs.islandOn(this) && Settings.canDrawOverlays(this)
        activePill.text = if (on) "● Activa" else "● Inactiva"
        activePill.setTextColor(col(if (on) R.color.green else R.color.red))
        activePill.background = Glass.pill(this, if (on) 0x1A3DDC84 else 0x1AFF6B6B,
            if (on) 0x883DDC84.toInt() else 0x88FF6B6B.toInt(), 22f)
    }

    // ---------------- Navegación ----------------

    private val tabs = listOf("bolt" to "Inicio", "tune" to "Diseño", "science" to "Demo", "settings" to "Ajustes")

    private fun render() {
        updatePill()
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, (ic, name) ->
            val sel = i == tab
            val colr = if (sel) 0xFFD9E6B0.toInt() else Glass.MUTED
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, px(8), 0, px(8))
                if (sel) background = Glass.pill(this@MainActivity, 0xCC5F6B3E.toInt(), 0, 22f)
                setOnClickListener { tab = i; render() }
            }
            b.addView(icon(ic, 24, colr))
            b.addView(label(name, 12f, R.color.text, true).apply { setTextColor(colr) })
            tabBar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(4); marginEnd = px(4) })
        }
        content.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(6), px(18), px(24))
        }
        when (tab) {
            0 -> buildHome(page)
            1 -> buildLayout(page)
            2 -> buildDemo(page)
            else -> buildSettings(page)
        }
        val scroll = ScrollView(this).apply { addView(page); isVerticalScrollBarEnabled = false }
        previewView = null
        if (tab == 1) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(buildPreview(), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                marginStart = px(18); marginEnd = px(18); topMargin = px(4)
            })
            col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            content.addView(col)
        } else content.addView(scroll)
    }

    /** Editor visual fijo arriba: muestra la parte superior de ESTE teléfono con la isla en vivo. */
    private fun buildPreview(): View {
        val c = card().apply { setPadding(px(12), px(12), px(12), px(12)) }
        val m = resources.displayMetrics
        val wDp = (minOf(m.widthPixels, m.heightPixels) / m.density).toInt()
        val hDp = (maxOf(m.widthPixels, m.heightPixels) / m.density).toInt()
        c.addView(label("Vista previa · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", 15f, R.color.text, true))
        c.addView(label("Pantalla $wDp × $hDp dp · Android ${Build.VERSION.RELEASE} · " +
            (if (landscapeView) "horizontal" else "vertical"), 12f, R.color.muted))
        val pv = IslandPreview(this)
        pv.landscape = landscapeView
        val ins = window.decorView.rootWindowInsets
        if (Build.VERSION.SDK_INT >= 28) {
            ins?.displayCutout?.boundingRects?.firstOrNull { it.top <= 5 }?.let {
                pv.cutout = android.graphics.RectF(it)
            }
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

    private fun svc() = NotifListener.instance
    private fun applyAll() { svc()?.applySettings() }

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
                            if (isOn) 0x40555E3E else 0x14FFFFFF, if (isOn) 0xAAC3D29A.toInt() else 0x33FFFFFF, 20f)
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
    }

    // ---------------- DISEÑO ----------------

    private fun buildLayout(p: LinearLayout) {
        p.addView(segmented(listOf("Vertical", "Horizontal"), if (landscapeView) 1 else 0) {
            landscapeView = it == 1; render()
        }, lp(8))
        val styleIdx = if (Prefs.style(this) == "ios") 1 else 0
        p.addView(segmented(listOf("Píldora compacta", "Modo notch iPhone"), styleIdx) {
            Prefs.setStyle(this, if (it == 1) "ios" else "hyperos"); applyAll(); svc()?.showTest()
        }, lp(10))

        section(p, "Preajustes rápidos", "Diseños de un toque para cámaras comunes") { b ->
            listOf("Punch-hole centrado" to Triple(8, 96, 32), "Notch ancho" to Triple(4, 150, 34),
                "Isla grande tipo iPhone" to Triple(10, 126, 36)).forEach { (n, v) ->
                b.addView(button(n, false) {
                    Prefs.setYOffsetDp(this, v.first); Prefs.setIdleWidthDp(this, v.second)
                    Prefs.setHeightDp(this, v.third); applyAll(); svc()?.showTest(); toast("Aplicado: $n"); render()
                }, lp(6))
            }
        }
        section(p, "Apps exentas", "Apps que apagan la isla por completo en vertical") { b ->
            b.addView(button("Elegir apps (${Prefs.exempt(this).size})", false) {
                pickApps("Apps exentas", Prefs.exempt(this)) { Prefs.setExempt(this, it); applyAll() }
            })
        }
        section(p, "Tamaño y posición", "Ancho, alto, desplazamientos y radio de esquina") { b ->
            seekRow(b, "Ancho en reposo", "dp", 60, 220, Prefs.idleWidthDp(this)) { Prefs.setIdleWidthDp(this, it) }
            seekRow(b, "Ancho expandido", "dp", 220, 420,
                Prefs.expandedWidthDp(this).let { if (it > 0) it else Styles.byId(Prefs.style(this)).expandedWidthDp }) { Prefs.setExpandedWidthDp(this, it) }
            seekRow(b, "Margen lateral mínimo", "dp", 0, 40, Prefs.sideMarginDp(this)) { Prefs.setSideMarginDp(this, it) }
            seekRow(b, "Alto", "dp", 20, 60, Prefs.heightDp(this)) { Prefs.setHeightDp(this, it) }
            seekRow(b, "Posición vertical", "dp", 0, 80, Prefs.yOffsetDp(this)) { Prefs.setYOffsetDp(this, it) }
            seekRow(b, "Posición horizontal", "dp", -100, 100, Prefs.xOffsetDp(this)) { Prefs.setXOffsetDp(this, it) }
            seekRow(b, "Radio de esquina", "dp", 0, 50, Prefs.cornerDp(this).coerceAtLeast(0)) { Prefs.setCornerDp(this, it) }
        }
        section(p, "Recorte de cámara", "Ajusta el agujero de la cámara a mano si no se detecta") { b ->
            b.addView(label("Mueve «Posición horizontal» y «Posición vertical» hasta que la isla " +
                "cubra exactamente el agujero de la cámara.", 13f, R.color.muted))
            b.addView(button("Restablecer posición", false) {
                Prefs.setXOffsetDp(this, 0); Prefs.setYOffsetDp(this, 8); Prefs.setSideMarginDp(this, 12)
                Prefs.setExpandedWidthDp(this, -1); applyAll(); svc()?.showTest(); render()
            }, lp(8))
        }
        section(p, "Sombra", "Sombra bajo la isla y su opacidad") { b ->
            b.addView(rowWithSwitch("Mostrar sombra", "", Prefs.shadow(this)) { v ->
                Prefs.setShadow(this, v); applyAll(); svc()?.showTest(); previewView?.refresh() })
            seekRow(b, "Opacidad", "%", 30, 100, Prefs.opacity(this)) { Prefs.setOpacity(this, it) }
        }

        val sp = card()
        sp.addView(label("Velocidad de animación", 16f, R.color.text, true))
        sp.addView(label("Qué tan rápido se abre, cierra y mueve la isla. Normal es la sensación original.", 13f, R.color.muted))
        sp.addView(segmented(listOf("Relajada", "Normal", "Ágil"), Prefs.speed(this), true) { Prefs.setSpeed(this, it) }, lp(10))
        p.addView(sp, lp(12))

        val mg = card()
        mg.addView(label("Estilo de unión del recorte", 16f, R.color.text, true))
        mg.addView(label("Cómo se une el círculo pequeño con el principal. Deslizar es el original; " +
            "Burbuja los junta en una sola forma líquida.", 13f, R.color.muted))
        mg.addView(segmented(listOf("Deslizar", "Burbuja"), 0, true) { toast("Burbuja llegará en una próxima versión") }, lp(10))
        p.addView(mg, lp(12))

        val iw = card()
        iw.addView(label("Widgets en reposo", 16f, R.color.text, true))
        iw.addView(label("Muestra un pequeño dato en vivo en la isla cuando no hay eventos.", 13f, R.color.muted))
        iw.addView(rowWithSwitch("Isla siempre visible", "", Prefs.permanent(this)) { v ->
            Prefs.setPermanent(this, v); applyAll() })
        p.addView(iw, lp(12))
    }

    // ---------------- DEMO ----------------

    private fun buildDemo(p: LinearLayout) {
        val c = card()
        c.addView(label("Tarjetas de ejemplo", 17f, R.color.text, true))
        c.addView(label("Toca «Mostrar» para poner una tarjeta en la isla. Los eventos cortos se van solos; " +
            "los demás se quedan hasta que los quites. Los eventos desactivados en Inicio se mantienen ocultos.",
            13f, R.color.muted))
        c.addView(rowWithSwitch("Abrir la tarjeta expandida", "", Prefs.demoExpanded(this)) { v ->
            Prefs.setDemoExpanded(this, v) })
        c.addView(button("Quitar tarjetas de ejemplo", false) { svc()?.clearDemo() }, lp(10))
        p.addView(c, lp(8))
        if (svc() == null) p.addView(label("⚠ Activa primero el acceso a notificaciones (Ajustes → Permisos).",
            13f, R.color.red), lp(8))
        Events.GROUPS.forEach { g ->
            val open = "demo_${g.id}" in expandedGroups
            val gc = card()
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { val k = "demo_${g.id}"; if (open) expandedGroups.remove(k) else expandedGroups.add(k); render() }
            }
            top.addView(tileIcon(g.symbol), LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(14) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(g.label, 18f, R.color.text, true))
            t.addView(label("${g.events.size} ejemplos", 14f, R.color.muted))
            top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(icon(if (open) "chev_up" else "chev_down", 22, Glass.MUTED))
            gc.addView(top)
            if (open) g.events.forEach { ev ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(icon(ev.symbol, 20, Glass.ACCENT), LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(12) })
                r.addView(label(ev.label, 15f, R.color.text), LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(button("Mostrar", false) {
                    val s = svc()
                    if (s == null) toast("Activa el acceso a notificaciones") else s.showEvent(ev.id, true)
                })
                gc.addView(r, lp(6))
            }
            p.addView(gc, lp(10))
        }
    }

    // ---------------- AJUSTES ----------------

    private fun buildSettings(p: LinearLayout) {
        val purple = 0xFF7B5CE0.toInt(); val dark = 0xFF3A4030.toInt(); val lime = 0xFFA9C47A.toInt()
        val indigo = 0xFF6A62E8.toInt(); val teal = 0xFF1FB5A3.toInt(); val pink = 0xFFE8458B.toInt()
        val orange = 0xFFF2A01F.toInt(); val blue = 0xFF1E9BE8.toInt(); val green = 0xFF2DAA55.toInt()
        val blue2 = 0xFF3F7BEA.toInt(); val gray = 0xFF5F6B80.toInt()
        heading(p, "APARIENCIA")
        item(p, "palette", purple, "Colores y opacidad", "Colores, opacidad y estilo de la tarjeta", "${Prefs.opacity(this)}% opac.") { colorsDialog() }
        item(p, "font", dark, "Fuente de la isla", "Tipografía y negrita", "") { fontDialog() }
        heading(p, "FUNCIONES")
        item(p, "bell", lime, "Notificaciones y privacidad", "Qué apps y eventos se muestran, pantalla de bloqueo",
            if (Prefs.lockScreen(this)) "Bloqueo" else "") { notifDialog() }
        item(p, "history", indigo, "Historial de notificaciones", "Revisa lo que mostró la isla", if (Prefs.keepHistory(this)) "Activo" else "Pausado") { historyDialog() }
        item(p, "grid", teal, "Atajos de apps", "Abre tus apps favoritas desde la isla", "${Prefs.pinned(this).size}/8 fijadas") { shortcutsDialog() }
        heading(p, "INTERACCIÓN")
        item(p, "touch", pink, "Gestos", "Toques, pulsación larga, sensibilidad y vibración", "") { gesturesDialog() }
        item(p, "widgets", orange, "Widgets en reposo", "Interruptores por orientación, widgets y temporizadores", if (Prefs.permanent(this)) "Sí" else "No") { idleDialog() }
        item(p, "timer", blue, "Temporización", "Cuánto dura la tarjeta y cuándo vuelve al reposo", "${Prefs.durationSeconds(this)} s") { timingDialog() }
        heading(p, "SISTEMA")
        item(p, "shield", green, "Permisos", "Accesibilidad, acceso a notificaciones, batería", "") { permsDialog() }
        item(p, "backup", blue2, "Copia de seguridad", "Exporta o importa todos los ajustes", "") { backupDialog() }
        item(p, "download", teal, "Actualizaciones", "Busca versiones nuevas en GitHub", "") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com")))
        }
        item(p, "people", pink, "Soporte y comunidad", "Reporta errores, pide funciones y únete", "") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com")))
        }
        item(p, "code", gray, "Opciones de desarrollador", "Diagnóstico, registros y opciones avanzadas", "") { devDialog() }
        item(p, "info", gray, "Acerca de", "Versión, el proyecto original y licencias", "") {
            dialog("Acerca de Ryx Island", "Ryx Island v0.6\nCreador: David Joel\n\nLa interfaz está inspirada en Smart Island, " +
                "de Animesh Gupta (licencia GPL-3.0): github.com/agupta07505/SmartIsland.\n\n" +
                "Este código es propio y no copia el de Smart Island. Si en el futuro se incorpora código suyo, " +
                "hay que conservar su aviso de copyright y publicar el proyecto bajo GPL-3.0.")
        }
    }

    private fun colorsDialog() = sheet("Colores y opacidad") { b ->
        b.addView(label("Estilo", 14f, R.color.muted))
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Styles.ALL.forEach { st ->
            row.addView(button(st.label + if (Prefs.style(this) == st.id) "  ✓" else "", Prefs.style(this) == st.id) {
                Prefs.setStyle(this, st.id); applyAll(); svc()?.showTest()
            }, lp(4))
        }
        b.addView(row)
        seekRow(b, "Opacidad", "%", 30, 100, Prefs.opacity(this)) { Prefs.setOpacity(this, it) }
    }

    private fun fontDialog() = sheet("Fuente de la isla") { b ->
        b.addView(rowWithSwitch("Título en negrita", "", Prefs.fontBold(this)) { v ->
            Prefs.setFontBold(this, v); applyAll() })
    }

    private fun notifDialog() = sheet("Notificaciones y privacidad") { b ->
        b.addView(rowWithSwitch("Mostrar con el teléfono bloqueado", "", Prefs.lockScreen(this)) { Prefs.setLockScreen(this, it) })
        b.addView(rowWithSwitch("Ocultar avisos emergentes del sistema (root)", "", Prefs.hideHeadsUp(this)) { v ->
            Thread { val r = Root.setHeadsUp(!v); runOnUiThread {
                if (r.first == 0) Prefs.setHideHeadsUp(this, v) else toast("No se pudo cambiar (¿root denegado?)") } }.start()
        })
        b.addView(button("Apps bloqueadas (${Prefs.blocked(this).size})", false) {
            pickApps("Apps bloqueadas", Prefs.blocked(this)) { Prefs.setBlocked(this, it) }
        }, lp(10))
    }

    private fun historyDialog() = sheet("Historial de notificaciones") { b ->
        b.addView(rowWithSwitch("Guardar historial", "", Prefs.keepHistory(this)) { Prefs.setKeepHistory(this, it) })
        val h = Prefs.history(this).ifBlank { "Todavía no hay notificaciones." }
        b.addView(label(h, 13f, R.color.text), lp(10))
        b.addView(button("Borrar historial", false) { Prefs.clearHistory(this); toast("Historial borrado") }, lp(10))
    }

    private fun shortcutsDialog() = sheet("Atajos de apps") { b ->
        b.addView(label("Elige hasta 8 apps para abrir desde la isla.", 13f, R.color.muted))
        b.addView(button("Elegir apps (${Prefs.pinned(this).size}/8)", false) {
            pickApps("Apps fijadas", Prefs.pinned(this).toSet(), 8) { Prefs.setPinned(this, it.toList()) }
        }, lp(10))
    }

    private fun gesturesDialog() = sheet("Gestos") { b ->
        b.addView(rowWithSwitch("Tocar la isla abre la app", "", Prefs.tapOpens(this)) { Prefs.setTapOpens(this, it) })
        b.addView(rowWithSwitch("Abrir en ventana flotante", "", Prefs.freeform(this)) { Prefs.setFreeform(this, it) })
        b.addView(rowWithSwitch("Forzar ventana nueva si la app ya está abierta", "", Prefs.forceNew(this)) { Prefs.setForceNew(this, it) })
        b.addView(rowWithSwitch("Vibrar al mostrar", "", Prefs.vibrate(this)) { Prefs.setVibrate(this, it) })
        b.addView(label("Pulsación larga sobre la tarjeta: la cierra.", 12f, R.color.muted), lp(8))
    }

    private fun idleDialog() = sheet("Widgets en reposo") { b ->
        b.addView(rowWithSwitch("Isla permanente (siempre visible)", "", Prefs.permanent(this)) { v ->
            Prefs.setPermanent(this, v); applyAll() })
    }

    private fun timingDialog() = sheet("Temporización") { b ->
        seekRow(b, "Tiempo visible", "s", 2, 15, Prefs.durationSeconds(this)) { Prefs.setDurationSeconds(this, it) }
    }

    private fun permsDialog() = sheet("Permisos") { b ->
        permRow(b, "Mostrar sobre otras apps", "Permite que la isla flote sobre la barra de estado y la cámara",
            Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow(b, "Acceso a notificaciones", "Lee mensajes, llamadas y alertas en vivo", notifEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow(b, "Dispositivos cercanos", "Detecta conexiones Bluetooth",
            Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 1)
        }
        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        permRow(b, "Sin optimización de batería", "Evita que el sistema cierre la isla en segundo plano", ignoring) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        permRow(b, "Info de la app", "Si Android bloquea un permiso: ⋮ → «Permitir ajustes restringidos»", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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

    private fun devDialog() = sheet("Opciones de desarrollador") { b ->
        b.addView(label("Registro de la última apertura en ventana flotante:", 13f, R.color.muted))
        b.addView(label(Prefs.lastLog(this).ifEmpty { "Todavía no hay registro. Toca la isla una vez." }, 12f, R.color.text), lp(8))
        b.addView(button("Probar la isla", true) { svc()?.showTest() ?: toast("Activa el acceso a notificaciones") }, lp(10))
    }

    // ---------------- Utilidades ----------------

    private fun notifEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun glass(d: AlertDialog): AlertDialog {
        d.window?.setBackgroundDrawable(Glass.card(this, 28f, true)); return d
    }

    private fun dialog(title: String, msg: String) =
        glass(AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Cerrar", null).show())

    private fun sheet(title: String, build: (LinearLayout) -> Unit) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Glass.card(this@MainActivity, 30f, true)
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
    private fun tileIcon(name: String, bg: Int = 0xFF3A4030.toInt(), tint: Int = Glass.ACCENT): View =
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
                    setTextColor(if (sel) (if (green) 0xFF22301A.toInt() else Glass.TEXT) else Glass.MUTED)
                    setPadding(px(6), px(13), px(6), px(13))
                    background = Glass.pill(this@MainActivity,
                        if (sel) (if (green) Glass.ACCENT else 0xFF12140E.toInt()) else 0, 0, 20f)
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
        text = t; textSize = size; setTextColor(col(color))
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
        setTextColor(if (filled) 0xFF22301A.toInt() else Glass.ACCENT)
        background = if (filled) Glass.pill(this@MainActivity, Glass.ACCENT, 0, 22f)
            else Glass.pill(this@MainActivity, 0x14FFFFFF, 0x88C3D29A.toInt(), 22f)
        setOnClickListener { onClick() }
    }
}
