package com.dav.isla

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var notifStatus: TextView
    private var headsUpSwitch: Switch? = null
    private var ignoreHeadsUp = false
    private val chips = LinkedHashMap<String, Button>()

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(col(R.color.bg))
            addView(root)
        }

        // Android 15/16/17: la app va de borde a borde, hay que respetar las barras del sistema
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        // Logo
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(MATCH, px(150)))
        root.addView(
            label("Isla dinámica para tu Android", 14f, R.color.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        // ---- Permisos ----
        overlayStatus = label("", 13f, R.color.muted)
        notifStatus = label("", 13f, R.color.muted)
        val perms = card()
        perms.addView(label("Permisos", 17f, R.color.gold, true))
        perms.addView(permRow("Mostrar sobre otras apps", overlayStatus, "Activar") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        perms.addView(permRow("Acceso a notificaciones", notifStatus, "Activar") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(perms, lp(18))

        // ---- Notificaciones ----
        val notif = card()
        notif.addView(label("Notificaciones", 17f, R.color.gold, true))
        notif.addView(
            switchRow(
                "Isla permanente (siempre visible)",
                makeSwitch(Prefs.permanent(this)) { v ->
                    Prefs.setPermanent(this, v)
                    NotifListener.instance?.applySettings()
                }
            )
        )
        headsUpSwitch = makeSwitch(Prefs.hideHeadsUp(this)) { v ->
            if (!ignoreHeadsUp) onHeadsUpToggle(v)
        }
        notif.addView(switchRow("Ocultar avisos emergentes del sistema (root)", headsUpSwitch!!))
        notif.addView(
            label(
                "Los avisos siguen llegando al panel de notificaciones; solo se oculta el aviso " +
                    "que baja desde arriba para que se vea únicamente la isla.",
                12f, R.color.muted
            ), lp(4)
        )
        root.addView(notif, lp(14))

        // ---- Ventana flotante ----
        val win = card()
        win.addView(label("Ventana flotante", 17f, R.color.gold, true))
        win.addView(
            switchRow(
                "Abrir en ventana flotante al tocar",
                makeSwitch(Prefs.freeform(this)) { v -> Prefs.setFreeform(this, v) }
            )
        )
        win.addView(
            switchRow(
                "Forzar ventana nueva (si la app ya está abierta)",
                makeSwitch(Prefs.forceNew(this)) { v -> Prefs.setForceNew(this, v) }
            )
        )
        win.addView(button("Ver registro de la última apertura", false) {
            val log = Prefs.lastLog(this).ifEmpty { "Todavía no hay registro. Toca la isla una vez." }
            AlertDialog.Builder(this)
                .setTitle("Registro")
                .setMessage(log)
                .setPositiveButton("Cerrar", null)
                .show()
        }, lp(12))
        root.addView(win, lp(14))

        // ---- Personalización ----
        val custom = card()
        custom.addView(label("Personalización", 17f, R.color.gold, true))
        custom.addView(label("Estilo de la isla", 14f, R.color.muted), lp(10))
        custom.addView(styleChips())
        seekRow(custom, "Tiempo visible", "s", 2, 10, Prefs.durationSeconds(this), false) {
            Prefs.setDurationSeconds(this, it)
        }
        seekRow(custom, "Posición vertical", "dp", 0, 60, Prefs.yOffsetDp(this), true) {
            Prefs.setYOffsetDp(this, it)
        }
        seekRow(custom, "Ancho en reposo", "dp", 60, 180, Prefs.idleWidthDp(this), true) {
            Prefs.setIdleWidthDp(this, it)
        }
        root.addView(custom, lp(14))

        // ---- Probar ----
        root.addView(button("Probar la isla", true) {
            val svc = NotifListener.instance
            if (svc != null) svc.showTest()
            else Toast.makeText(this, "Activa primero el acceso a notificaciones", Toast.LENGTH_SHORT).show()
        }, lp(18))

        // ---- Ayuda ----
        val help = card()
        help.addView(label("¿Algo no funciona?", 15f, R.color.text, true))
        help.addView(
            label(
                "• Si Android bloquea un permiso (apps fuera de Play Store): abre \"Info de la app\", " +
                    "toca ⋮ y elige \"Permitir ajustes restringidos\".\n" +
                    "• Para que la isla no se cierre sola, quita la optimización de batería de esta app " +
                    "y permite el inicio automático.",
                13f, R.color.muted
            ), lp(6)
        )
        help.addView(button("Abrir info de la app", false) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }, lp(10))
        root.addView(help, lp(14))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(notifStatus, notificationAccessEnabled())
    }

    private fun notificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun setStatus(tv: TextView, ok: Boolean) {
        tv.text = if (ok) "✓ Activado" else "✗ Falta activar"
        tv.setTextColor(col(if (ok) R.color.green else R.color.red))
    }

    // on = true -> ocultar avisos emergentes del sistema
    private fun onHeadsUpToggle(on: Boolean) {
        Thread {
            val res = Root.setHeadsUp(!on)
            runOnUiThread {
                if (res.first == 0) {
                    Prefs.setHideHeadsUp(this, on)
                } else {
                    Toast.makeText(this, "No se pudo cambiar (¿root denegado?)", Toast.LENGTH_LONG).show()
                    ignoreHeadsUp = true
                    headsUpSwitch?.isChecked = !on
                    ignoreHeadsUp = false
                }
            }
        }.start()
    }

    // ---------- Selector de estilo ----------

    private fun styleChips(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL },
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        )
        rows.forEach { box.addView(it, lp(8)) }
        Styles.ALL.forEachIndexed { i, st ->
            val b = button(st.label, false) {
                Prefs.setStyle(this, st.id)
                refreshChips()
                NotifListener.instance?.let {
                    it.applySettings()
                    it.showTest()
                }
            }
            b.textSize = 13f
            chips[st.id] = b
            rows[i / 2].addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = px(3)
                marginEnd = px(3)
            })
        }
        refreshChips()
        return box
    }

    private fun refreshChips() {
        val current = Prefs.style(this)
        chips.forEach { (id, b) -> paintButton(b, id == current) }
    }

    private fun seekRow(
        parent: LinearLayout, title: String, unit: String, lo: Int, hi: Int, value: Int,
        rebuild: Boolean, onChange: (Int) -> Unit
    ) {
        val lbl = label("$title: $value $unit", 14f, R.color.muted)
        val seek = SeekBar(this).apply {
            max = hi - lo
            progress = value - lo
            progressTintList = ColorStateList.valueOf(col(R.color.gold))
            thumbTintList = ColorStateList.valueOf(col(R.color.gold))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    lbl.text = "$title: $v $unit"
                    onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (rebuild) {
                        NotifListener.instance?.let {
                            it.applySettings()
                            it.showTest()
                        }
                    }
                }
            })
        }
        parent.addView(lbl, lp(14))
        parent.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ---------- Helpers de UI ----------

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(top) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(col(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(col(R.color.card))
            cornerRadius = px(18).toFloat()
        }
        setPadding(px(16), px(14), px(16), px(16))
    }

    private fun makeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun switchRow(title: String, sw: Switch) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(10), 0, px(4))
        addView(label(title, 15f, R.color.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sw)
    }

    private fun permRow(title: String, status: TextView, action: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(4))
            val left = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, R.color.text, true))
                addView(status)
            }
            addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button(action, false, onClick))
        }

    private fun paintButton(b: Button, filled: Boolean) {
        b.setTextColor(if (filled) Color.BLACK else col(R.color.gold))
        b.background = GradientDrawable().apply {
            cornerRadius = px(14).toFloat()
            if (filled) {
                setColor(col(R.color.gold))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(px(1), col(R.color.gold))
            }
        }
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        setTypeface(typeface, Typeface.BOLD)
        setPadding(px(18), px(10), px(18), px(10))
        paintButton(this, filled)
        setOnClickListener { onClick() }
    }
}
