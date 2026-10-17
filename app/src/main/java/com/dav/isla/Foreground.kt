package com.dav.isla

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process

/** Detecta qué app está al frente y si es un juego. Necesita «Acceso de uso» (permiso especial, sin root). */
object Foreground {
    private var cur: String? = null
    private var since = 0L
    private val gameCache = HashMap<String, Boolean>()

    fun hasAccess(c: Context): Boolean = try {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    /** Paquete de la app al frente, o null si no se puede saber. */
    @Synchronized
    fun pkg(c: Context): String? {
        if (!hasAccess(c)) return null
        return try {
            val now = System.currentTimeMillis()
            val from = if (since == 0L) now - 12 * 3600_000L else since
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> cur = e.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == cur) cur = null
                }
            }
            since = now - 500
            cur
        } catch (e: Throwable) { null }
    }

    @Suppress("DEPRECATION")
    fun isGame(c: Context, pkg: String): Boolean = gameCache.getOrPut(pkg) {
        try {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (e: Throwable) { false }
    }
}
