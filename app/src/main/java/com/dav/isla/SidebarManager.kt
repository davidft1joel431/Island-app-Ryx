package com.dav.isla

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Barra lateral flotante: una tira vertical en el borde de la pantalla con los iconos de las
 * apps fijadas. Tocar un icono abre la app en formato libre (si la ROM lo soporta) o a pantalla
 * completa. La barra se muestra siempre o solo al deslizar desde el borde, según los ajustes.
 *
 * Esta barra es del sistema: Ryx no dibuja controles encima de las ventanas abiertas, solo
 * ofrece este launcher rápido en el borde de la pantalla.
 */
object SidebarManager {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sidebar: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var attached = false

    /** Crea (o reutiliza) la vista de la barra lateral y la añade al WindowManager. */
    fun show(c: Context) {
        if (sidebar != null && attached) return
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = buildSidebar(c)
        sidebar = root
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (Prefs.sidebarPos(c) == 0) Gravity.LEFT or Gravity.CENTER_VERTICAL
                else Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }
        try {
            wm?.addView(root, lp)
            attached = true
        } catch (e: Exception) {
            attached = false
        }
    }

    /** Quita la barra lateral de la pantalla. */
    fun hide() {
        sidebar?.let { v ->
            try { wm?.removeView(v) } catch (e: Exception) {}
        }
        sidebar = null
        lp = null
        attached = false
    }

    /** ¿Está la barra mostrada ahora? */
    fun isShowing(): Boolean = attached && sidebar != null

    /** Alterna mostrar/ocultar. */
    fun toggle(c: Context) {
        if (isShowing()) hide() else show(c)
    }

    /** Construye la vista de la barra lateral con las apps fijadas. */
    private fun buildSidebar(c: Context): View {
        val density = c.resources.displayMetrics.density
        val iconDp = Prefs.sidebarIconDp(c)
        val iconPx = (iconDp * density).toInt()

        val container = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF1A1E24.toInt())
                cornerRadius = 16f * density
                setStroke(maxOf(1, (1f * density).toInt()), 0x33FFFFFF.toInt())
            }
        }

        val pinned = Prefs.sidebarPinned(c)
        if (pinned.isEmpty()) {
            // Si no hay apps fijadas, mostrar un mensaje de ayuda
            val empty = TextView(c).apply {
                text = "Sin apps fijadas\nVe a Ajustes → Barra lateral"
                textSize = 11f
                setTextColor(0xFF9AA4B7.toInt())
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            }
            container.addView(empty, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return container
        }

        val pm = c.packageManager
        pinned.forEachIndexed { index, key ->
            val (pkg, uid) = Clones.splitKey(key)
            val curUser = Process.myUserHandle()
            val icon: Drawable? = try {
                val base = if (uid >= 0) {
                    val la = c.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
                    val ai = la.getApplicationInfo(pkg, 0, curUser)
                    ai.loadIcon(pm)
                } else pm.getApplicationIcon(pkg)
                if (uid >= 0) pm.getUserBadgedIcon(base, curUser) else base
            } catch (e: Exception) { null }

            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }

            val item = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
            }

            val iv = ImageView(c).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val ivLp = LinearLayout.LayoutParams(iconPx, iconPx).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (2 * density).toInt()
            }
            item.addView(iv, ivLp)

            val tv = TextView(c).apply {
                text = label.take(10)
                textSize = 10f
                setTextColor(0xFFF4F6FA.toInt())
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            item.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            // Hacer el ítem clicable para abrir la app
            item.setOnClickListener {
                openApp(c, pkg, uid)
            }
            // Efecto visual al tocar
            item.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        item.alpha = 0.5f
                        false
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        item.alpha = 1f
                        false
                    }
                    else -> false
                }
            }

            container.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt(); bottomMargin = (4 * density).toInt() })
        }

        return container
    }

    /** Abre la app: si el modo es freeform y la ROM lo soporta, ventana libre; si no, barra lateral/normal. */
    private fun openApp(c: Context, pkg: String, userId: Int) {
        val mode = Prefs.openMode(c)
        if (mode == 0) {
            // Formato libre del sistema
            val svc = RyxAccessibility.instance
            if (svc != null) {
                val b = FloatingWindow.bounds(c)
                svc.launchSmartIsland(pkg, null, b, StringBuilder(), null, userId)
            } else {
                // Accesibilidad no activa: abrir normal
                val intent = c.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: return
                c.startActivity(intent)
            }
        } else {
            // Barra lateral: abrir la app (la barra lateral ya está visible, solo lanzamos la app)
            val intent = c.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
            c.startActivity(intent)
        }
    }

    /** Refresca la vista de la barra lateral (por si cambian las apps fijadas). */
    fun refresh(c: Context) {
        if (isShowing()) {
            hide()
            show(c)
        }
    }
}
