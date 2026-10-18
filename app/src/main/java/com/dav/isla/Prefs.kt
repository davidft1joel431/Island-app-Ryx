package com.dav.isla

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("isla", Context.MODE_PRIVATE)

    fun durationSeconds(c: Context): Int = sp(c).getInt("duration_s", 5)
    fun durationMs(c: Context): Long = durationSeconds(c) * 1000L
    fun setDurationSeconds(c: Context, s: Int) = sp(c).edit().putInt("duration_s", s).apply()

    fun freeform(c: Context): Boolean = sp(c).getBoolean("freeform", true)
    fun setFreeform(c: Context, v: Boolean) = sp(c).edit().putBoolean("freeform", v).apply()

    fun forceNew(c: Context): Boolean = sp(c).getBoolean("force_new", true)
    fun setForceNew(c: Context, v: Boolean) = sp(c).edit().putBoolean("force_new", v).apply()

    fun permanent(c: Context): Boolean = sp(c).getBoolean("permanent", false)
    fun setPermanent(c: Context, v: Boolean) = sp(c).edit().putBoolean("permanent", v).apply()

    fun hideHeadsUp(c: Context): Boolean = sp(c).getBoolean("hide_headsup", false)
    fun setHideHeadsUp(c: Context, v: Boolean) = sp(c).edit().putBoolean("hide_headsup", v).apply()

    fun style(c: Context): String = sp(c).getString("style", "hyperos") ?: "hyperos"
    fun setStyle(c: Context, v: String) = sp(c).edit().putString("style", v).apply()

    fun yOffsetDp(c: Context): Int = sp(c).getInt("y_dp", 8)
    fun setYOffsetDp(c: Context, v: Int) = sp(c).edit().putInt("y_dp", v).apply()

    fun idleWidthDp(c: Context): Int = sp(c).getInt("idle_w", 96)
    fun setIdleWidthDp(c: Context, v: Int) = sp(c).edit().putInt("idle_w", v).apply()

    fun lastLog(c: Context): String = sp(c).getString("last_log", "") ?: ""
    fun setLastLog(c: Context, s: String) = sp(c).edit().putString("last_log", s.takeLast(8000)).apply()

    // ---- Eventos ----
    private fun land(c: Context) =
        c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    fun eventOn(c: Context, id: String): Boolean = eventOnFor(c, id, land(c))
    fun eventOnFor(c: Context, id: String, landscape: Boolean): Boolean =
        sp(c).getBoolean((if (landscape) "evl_" else "ev_") + id, true)
    fun setEventOnFor(c: Context, id: String, landscape: Boolean, v: Boolean) =
        sp(c).edit().putBoolean((if (landscape) "evl_" else "ev_") + id, v).apply()
    fun eventExpand(c: Context, id: String): Boolean = sp(c).getBoolean("ex_$id", true)
    fun setEventExpand(c: Context, id: String, v: Boolean) = sp(c).edit().putBoolean("ex_$id", v).apply()

    // ---- General ----
    fun islandOn(c: Context): Boolean = sp(c).getBoolean("island_on", true)
    fun setIslandOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("island_on", v).apply()
    fun demoExpanded(c: Context): Boolean = sp(c).getBoolean("demo_exp", true)
    fun setDemoExpanded(c: Context, v: Boolean) = sp(c).edit().putBoolean("demo_exp", v).apply()
    fun speed(c: Context): Int = sp(c).getInt("speed", 1)   // 0 relajada, 1 normal, 2 ágil
    fun setSpeed(c: Context, v: Int) = sp(c).edit().putInt("speed", v).apply()
    fun heightDp(c: Context): Int = sp(c).getInt("idle_h", 32)
    fun setHeightDp(c: Context, v: Int) = sp(c).edit().putInt("idle_h", v).apply()
    fun xOffsetDp(c: Context): Int = sp(c).getInt("x_dp", 0)
    fun setXOffsetDp(c: Context, v: Int) = sp(c).edit().putInt("x_dp", v).apply()
    fun cornerDp(c: Context): Int = sp(c).getInt("corner_dp", -1)   // -1 = el del estilo
    fun setCornerDp(c: Context, v: Int) = sp(c).edit().putInt("corner_dp", v).apply()
    fun opacity(c: Context): Int = sp(c).getInt("opacity", 100)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()
    fun shadow(c: Context): Boolean = sp(c).getBoolean("shadow", false)
    fun setShadow(c: Context, v: Boolean) = sp(c).edit().putBoolean("shadow", v).apply()
    fun tapOpens(c: Context): Boolean = sp(c).getBoolean("tap_opens", true)
    fun setTapOpens(c: Context, v: Boolean) = sp(c).edit().putBoolean("tap_opens", v).apply()
    fun vibrate(c: Context): Boolean = sp(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibrate", v).apply()
    fun lockScreen(c: Context): Boolean = sp(c).getBoolean("lock", true)
    fun setLockScreen(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock", v).apply()
    fun keepHistory(c: Context): Boolean = sp(c).getBoolean("hist_on", true)
    fun setKeepHistory(c: Context, v: Boolean) = sp(c).edit().putBoolean("hist_on", v).apply()
    fun history(c: Context): String = sp(c).getString("hist", "") ?: ""
    fun addHistory(c: Context, line: String) =
        sp(c).edit().putString("hist", (line + "\n" + history(c)).lines().take(50).joinToString("\n")).apply()
    fun clearHistory(c: Context) = sp(c).edit().remove("hist").apply()
    fun exempt(c: Context): Set<String> = sp(c).getStringSet("exempt", emptySet()) ?: emptySet()
    fun setExempt(c: Context, v: Set<String>) = sp(c).edit().putStringSet("exempt", v).apply()
    fun pinned(c: Context): List<String> =
        (sp(c).getString("pinned", "") ?: "").split(",").filter { it.isNotBlank() }
    fun setPinned(c: Context, v: List<String>) = sp(c).edit().putString("pinned", v.joinToString(",")).apply()
    fun allowedApps(c: Context): Set<String>? = sp(c).getStringSet("blocked", null)
    fun blocked(c: Context): Set<String> = sp(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun setBlocked(c: Context, v: Set<String>) = sp(c).edit().putStringSet("blocked", v).apply()
    fun fontBold(c: Context): Boolean = sp(c).getBoolean("font_bold", true)
    fun setFontBold(c: Context, v: Boolean) = sp(c).edit().putBoolean("font_bold", v).apply()

    // ---- Respaldo ----
    fun exportAll(c: Context): String =
        org.json.JSONObject().also { o -> sp(c).all.forEach { (k, v) ->
            if (k != "hist" && k != "hist2" && k != "last_log" && !k.startsWith("cloud_") && !k.startsWith("upd_")) o.put(k, if (v is Set<*>) org.json.JSONArray(v.toList()) else v) } }.toString()
    fun importAll(c: Context, json: String): Boolean = try {
        val o = org.json.JSONObject(json); val e = sp(c).edit()
        o.keys().forEach { k -> when (val v = o.get(k)) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is String -> e.putString(k, v)
            is org.json.JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
        } }
        e.apply(); true
    } catch (e: Exception) { false }

    fun expandedWidthDp(c: Context): Int = sp(c).getInt("exp_w", -1)
    fun setExpandedWidthDp(c: Context, v: Int) = sp(c).edit().putInt("exp_w", v).apply()
    fun sideMarginDp(c: Context): Int = sp(c).getInt("side_m", 12)
    fun setSideMarginDp(c: Context, v: Int) = sp(c).edit().putInt("side_m", v).apply()

    // ---- Puente de notificaciones ----
    fun blockSystem(c: Context): Boolean = sp(c).getBoolean("block_sys", true)
    fun setBlockSystem(c: Context, v: Boolean) = sp(c).edit().putBoolean("block_sys", v).apply()
    fun iconMode(c: Context): Int = sp(c).getInt("icon_mode", 0)       // 0 sistema, 1 notificación, 2 monocromo
    fun setIconMode(c: Context, v: Int) = sp(c).edit().putInt("icon_mode", v).apply()
    fun iconShape(c: Context): Int = sp(c).getInt("icon_shape", 1)     // 0 círculo, 1 squircle, 2 flor, 3 hexágono
    fun setIconShape(c: Context, v: Int) = sp(c).edit().putInt("icon_shape", v).apply()
    fun smartColor(c: Context): Boolean = sp(c).getBoolean("smart_color", true)
    fun setSmartColor(c: Context, v: Boolean) = sp(c).edit().putBoolean("smart_color", v).apply()
    fun hiddenWordsRaw(c: Context): String = sp(c).getString("hidden_words", "") ?: ""
    fun setHiddenWords(c: Context, v: String) = sp(c).edit().putString("hidden_words", v).apply()
    fun hiddenWords(c: Context): List<String> =
        hiddenWordsRaw(c).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ---- Ventana flotante ----
    fun fwWidthPct(c: Context): Int = sp(c).getInt("fw_w", 72)
    fun setFwWidthPct(c: Context, v: Int) = sp(c).edit().putInt("fw_w", v).apply()
    fun fwHeightPct(c: Context): Int = sp(c).getInt("fw_h", 58)
    fun setFwHeightPct(c: Context, v: Int) = sp(c).edit().putInt("fw_h", v).apply()
    fun fwPos(c: Context): Int = sp(c).getInt("fw_pos", 0)             // 0 centro, 1 arriba, 2 abajo
    fun setFwPos(c: Context, v: Int) = sp(c).edit().putInt("fw_pos", v).apply()
    fun fwAnim(c: Context): Int = sp(c).getInt("fw_anim", 0)           // 0 resorte, 1 desvanecer, 2 deslizar
    fun setFwAnim(c: Context, v: Int) = sp(c).edit().putInt("fw_anim", v).apply()
    fun fwTestPkg(c: Context): String = sp(c).getString("fw_test", "") ?: ""
    fun setFwTestPkg(c: Context, v: String) = sp(c).edit().putString("fw_test", v).apply()

    // ---- Historial por app (JSON) con imágenes ----
    data class HistItem(val pkg: String, val title: String, val text: String, val ts: Long,
                        val img: String = "", val kind: String = "")

    fun histDir(c: Context): java.io.File = java.io.File(c.filesDir, "hist").also { it.mkdirs() }

    fun addHist(c: Context, pkg: String, title: String, text: String, img: String = "", kind: String = "") {
        val old = try { org.json.JSONArray(sp(c).getString("hist2", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val first = old.optJSONObject(0)
        val now = System.currentTimeMillis()
        if (first != null && first.optString("p") == pkg && first.optString("t") == title &&
            first.optString("x") == text && now - first.optLong("ts") < 3000) {
            if (img.isNotEmpty()) java.io.File(histDir(c), img).delete()
            return
        }
        val out = org.json.JSONArray()
        out.put(org.json.JSONObject().put("p", pkg).put("t", title).put("x", text).put("ts", now)
            .put("i", img).put("k", kind))
        for (i in 0 until minOf(old.length(), 599)) out.put(old.get(i))
        // las entradas que se caen de la lista se llevan su imagen
        for (i in 599 until old.length()) {
            val n = old.optJSONObject(i)?.optString("i") ?: ""
            if (n.isNotEmpty()) java.io.File(histDir(c), n).delete()
        }
        sp(c).edit().putString("hist2", out.toString()).apply()
    }
    fun histAll(c: Context): List<HistItem> = try {
        val a = org.json.JSONArray(sp(c).getString("hist2", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            HistItem(o.optString("p"), o.optString("t"), o.optString("x"), o.optLong("ts"), o.optString("i"), o.optString("k")) }
    } catch (e: Exception) { emptyList() }
    fun clearHist(c: Context) {
        sp(c).edit().remove("hist2").apply()
        histDir(c).listFiles()?.forEach { it.delete() }
    }

    // ---- Método con privilegios ----
    fun shellMethod(c: Context): Int = sp(c).getInt("shell_m", 0)      // 0 auto, 1 Shizuku, 2 root
    fun setShellMethod(c: Context, v: Int) = sp(c).edit().putInt("shell_m", v).apply()

    // ---- Gestos: 0 nada, 1 abrir app, 2 ventana flotante, 3 descartar, 4 siguiente, 5 anterior, 6 contraer ----
    fun gTap(c: Context): Int = sp(c).getInt("g_tap", 7)
    fun setGTap(c: Context, v: Int) = sp(c).edit().putInt("g_tap", v).apply()
    fun gDown(c: Context): Int = sp(c).getInt("g_down", 2)
    fun setGDown(c: Context, v: Int) = sp(c).edit().putInt("g_down", v).apply()
    fun gUp(c: Context): Int = sp(c).getInt("g_up", 3)
    fun setGUp(c: Context, v: Int) = sp(c).edit().putInt("g_up", v).apply()
    fun gLeft(c: Context): Int = sp(c).getInt("g_left", 4)
    fun setGLeft(c: Context, v: Int) = sp(c).edit().putInt("g_left", v).apply()
    fun gRight(c: Context): Int = sp(c).getInt("g_right", 5)
    fun setGRight(c: Context, v: Int) = sp(c).edit().putInt("g_right", v).apply()
    fun gLong(c: Context): Int = sp(c).getInt("g_long", 6)
    fun setGLong(c: Context, v: Int) = sp(c).edit().putInt("g_long", v).apply()
    fun swipeDp(c: Context): Int = sp(c).getInt("swipe_dp", 24)
    fun setSwipeDp(c: Context, v: Int) = sp(c).edit().putInt("swipe_dp", v).apply()
    fun autoHideAfterTouch(c: Context): Boolean = true
    fun appTheme(c: Context): String = sp(c).getString("app_theme", "ryx") ?: "ryx"
    fun setAppTheme(c: Context, v: String) = sp(c).edit().putString("app_theme", v).apply()

    // ---- Aspecto: isla en reposo y tarjeta (cristal / color / transparencia) ----
    fun idleMode(c: Context): Int = sp(c).getInt("idle_mode", 0)       // 0 negro, 1 cristal, 2 color, 3 acento
    fun setIdleMode(c: Context, v: Int) = sp(c).edit().putInt("idle_mode", v).apply()
    fun idleColor(c: Context): Int = sp(c).getInt("idle_color", 0)     // 0 = automático
    fun setIdleColor(c: Context, v: Int) = sp(c).edit().putInt("idle_color", v).apply()
    fun idleOpacity(c: Context): Int = sp(c).getInt("idle_op", 100)
    fun setIdleOpacity(c: Context, v: Int) = sp(c).edit().putInt("idle_op", v).apply()
    fun idleBorder(c: Context): Int = sp(c).getInt("idle_border", -1)   // -1 = según el modo
    fun setIdleBorder(c: Context, v: Int) = sp(c).edit().putInt("idle_border", v).apply()
    fun idleBlur(c: Context): Boolean = sp(c).getBoolean("idle_blur", false)
    fun setIdleBlur(c: Context, v: Boolean) = sp(c).edit().putBoolean("idle_blur", v).apply()
    fun cardGlass(c: Context): Boolean = sp(c).getBoolean("card_glass", false)
    fun setCardGlass(c: Context, v: Boolean) = sp(c).edit().putBoolean("card_glass", v).apply()
    fun cardColor(c: Context): Int = sp(c).getInt("card_color", 0)     // 0 = el del estilo
    fun setCardColor(c: Context, v: Int) = sp(c).edit().putInt("card_color", v).apply()

    // ---- Visibilidad de la isla ----
    fun hideInGames(c: Context): Boolean = sp(c).getBoolean("hide_games", false)
    fun setHideInGames(c: Context, v: Boolean) = sp(c).edit().putBoolean("hide_games", v).apply()
    fun hideApps(c: Context): Set<String> = sp(c).getStringSet("hide_apps", emptySet()) ?: emptySet()
    fun setHideApps(c: Context, v: Set<String>) = sp(c).edit().putStringSet("hide_apps", v).apply()

    // ---- Ventana flotante: cómo se abre ----
    fun fwMode(c: Context): Int = sp(c).getInt("fw_mode", 2)            // 0 directo, 1 con animación de Ryx, 2 «Formato libre» del launcher (accesibilidad)
    fun setFwMode(c: Context, v: Int) = sp(c).edit().putInt("fw_mode", v).apply()
    fun fwOwnBounds(c: Context): Boolean = sp(c).getBoolean("fw_own_bounds", true)
    fun setFwOwnBounds(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_own_bounds", v).apply()
    fun fwFrame(c: Context): Boolean = sp(c).getBoolean("fw_frame", true)
    fun setFwFrame(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_frame", v).apply()

    // ---- Copia en la nube y actualizaciones ----
    fun cloudUri(c: Context): String = sp(c).getString("cloud_uri", "") ?: ""
    fun setCloudUri(c: Context, v: String) = sp(c).edit().putString("cloud_uri", v).apply()
    fun cloudLast(c: Context): Long = sp(c).getLong("cloud_last", 0L)
    fun setCloudLast(c: Context, v: Long) = sp(c).edit().putLong("cloud_last", v).apply()
    fun updLast(c: Context): Long = sp(c).getLong("upd_last", 0L)
    fun setUpdLast(c: Context, v: Long) = sp(c).edit().putLong("upd_last", v).apply()
    /** true si se activó el modo escritorio del teléfono (barra real del sistema): entonces Ryx no dibuja su propia barra. */
    fun fwDesktop(c: Context): Boolean = sp(c).getBoolean("fw_desktop", false)
    fun setFwDesktop(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_desktop", v).apply()
    /** Método de «solo accesibilidad»: 0 = Formato libre REAL del sistema (con cortina, lento), 1 = directo estilo Smart Island (instantáneo). */
    fun fwSoloMode(c: Context): Int = sp(c).getInt("fw_solo_mode", 1)
    fun setFwSoloMode(c: Context, v: Int) = sp(c).edit().putInt("fw_solo_mode", v).apply()
    /** Camino del launcher que funcionó en esta ROM: 0 sin calibrar, 1 Recientes, 2 pantalla de inicio. */
    fun fwRoute(c: Context): Int = sp(c).getInt("fw_route", 0)
    fun setFwRoute(c: Context, v: Int) = sp(c).edit().putInt("fw_route", v).apply()
    /** Sección «Ventana flotante · solo accesibilidad»: usa únicamente el método de Smart Island, sin otros caminos. */
    fun fwSolo(c: Context): Boolean = sp(c).getBoolean("fw_solo", true)
    fun setFwSolo(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_solo", v).apply()
    /** Variante de apertura directa que funcionó en esta ROM (-1 = sin calibrar). */
    fun fwVariant(c: Context): Int = sp(c).getInt("fw_variant", -1)
    fun setFwVariant(c: Context, v: Int) = sp(c).edit().putInt("fw_variant", v).apply()
    fun fwKeepApp(c: Context): Boolean = sp(c).getBoolean("fw_keep_app", true)
    fun setFwKeepApp(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_keep_app", v).apply()
    fun a11yAsked(c: Context): Boolean = sp(c).getBoolean("a11y_asked", false)
    fun setA11yAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("a11y_asked", v).apply()
    fun restoreAsked(c: Context): Boolean = sp(c).getBoolean("restore_asked", false)
    fun setRestoreAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("restore_asked", v).apply()
    fun histRaw(c: Context): String = sp(c).getString("hist2", "[]") ?: "[]"
    fun setHistRaw(c: Context, v: String) = sp(c).edit().putString("hist2", v).apply()
}
