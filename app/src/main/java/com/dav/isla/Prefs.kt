package com.dav.isla

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("isla", Context.MODE_PRIVATE)

    fun durationSeconds(c: Context): Int = sp(c).getInt("duration_s", 5)
    fun durationMs(c: Context): Long = durationSeconds(c) * 1000L
    fun setDurationSeconds(c: Context, s: Int) = sp(c).edit().putInt("duration_s", s).apply()

    fun freeform(c: Context): Boolean = sp(c).getBoolean("freeform", true)
    fun setFreeform(c: Context, v: Boolean) = sp(c).edit().putBoolean("freeform", v).apply()
}
