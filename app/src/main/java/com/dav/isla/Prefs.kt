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
    fun setLastLog(c: Context, s: String) = sp(c).edit().putString("last_log", s.takeLast(4000)).apply()

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
            if (k != "hist" && k != "last_log") o.put(k, if (v is Set<*>) org.json.JSONArray(v.toList()) else v) } }.toString()
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
}
