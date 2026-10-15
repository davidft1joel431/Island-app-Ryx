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
    fun setLastLog(c: Context, s: String) = sp(c).edit().putString("last_log", s.takeLast(1500)).apply()
}
