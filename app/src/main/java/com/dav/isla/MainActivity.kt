package com.dav.isla

import android.app.Activity
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

        // Permisos
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

        // Ajustes
        val settings = card()
        settings.addView(label("Ajustes", 17f, R.color.gold, true))

        val sw = Switch(this).apply {
            isChecked = Prefs.freeform(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setFreeform(this@MainActivity, v) }
        }
        val swRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(4))
            addView(
                label("Abrir en ventana flotante al tocar", 15f, R.color.text),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            addView(sw)
        }
        settings.addView(swRow)

        val durationLabel = label(
            "Tiempo visible: ${Prefs.durationSeconds(this)} s", 14f, R.color.muted
        )
        val seek = SeekBar(this).apply {
            max = 8
            progress = Prefs.durationSeconds(this@MainActivity) - 2
            progressTintList = ColorStateList.valueOf(col(R.color.gold))
            thumbTintList = ColorStateList.valueOf(col(R.color.gold))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val sec = p + 2
                    durationLabel.text = "Tiempo visible: $sec s"
                    Prefs.setDurationSeconds(this@MainActivity, sec)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        settings.addView(durationLabel, lp(12))
        settings.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(settings, lp(14))

        // Probar
        root.addView(button("Probar la isla", true) {
            val svc = NotifListener.instance
            if (svc != null) svc.showTest()
            else Toast.makeText(this, "Activa primero el acceso a notificaciones", Toast.LENGTH_SHORT).show()
        }, lp(18))

        // Ayuda
        val help = card()
        help.addView(label("¿Android bloquea un permiso?", 15f, R.color.text, true))
        help.addView(
            label(
                "En apps instaladas fuera de Play Store, abre \"Info de la app\", toca ⋮ " +
                    "(arriba a la derecha) y elige \"Permitir ajustes restringidos\". Luego vuelve y activa el permiso.",
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

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        setTextColor(if (filled) Color.BLACK else col(R.color.gold))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = px(14).toFloat()
            if (filled) {
                setColor(col(R.color.gold))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(px(1), col(R.color.gold))
            }
        }
        setPadding(px(18), px(10), px(18), px(10))
        setOnClickListener { onClick() }
    }
}
