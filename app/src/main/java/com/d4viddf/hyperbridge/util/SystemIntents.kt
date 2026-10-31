package com.d4viddf.hyperbridge.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Opens the OEM autostart / background-app management screen when the device
 * exposes one. Android 15+ does not define a public API for this, so we try the
 * well-known components for each manufacturer and fall back to the app details
 * screen. No manufacturer is required for Ryx Isla to work.
 */
fun openAutoStartSettings(context: Context) {
    val candidates = buildList {
        add(Intent().setComponent(ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.permission.startup.StartupAppListActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.coloros.oppoguardelf",
            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.oplus.battery",
            "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.samsung.android.sm",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.meizu.safe",
            "com.meizu.safe.permission.SmartBGActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity"
        )))
        add(Intent().setComponent(ComponentName(
            "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity"
        )))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        })
    }

    if (!launchFirstAvailable(context, candidates)) {
        Toast.makeText(context, "No se encontró el ajuste de inicio automático", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the battery optimization screen for this app. Falls back to the generic
 * battery settings and finally to the app details screen.
 */
@SuppressLint("BatteryLife")
fun openBatterySettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        },
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        }
    )

    if (!launchFirstAvailable(context, candidates)) {
        Toast.makeText(context, "No se encontró el ajuste de batería", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Tries each intent until one resolves. Starting an activity that does not
 * exist throws ActivityNotFoundException, which we swallow while continuing
 * with the next candidate.
 */
private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
    for (intent in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) == null) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // Try the next candidate.
        }
    }
    return false
}

/**
 * Checks if Notification Listener permission is granted.
 */
fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

/**
 * Checks if Post Notification permission (Android 13+) is granted.
 */
fun isPostNotificationsEnabled(context: Context): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * Opens the system notification settings for Ryx Isla.
 */
fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: Exception) {
            Toast.makeText(context, "No se encontraron los ajustes", Toast.LENGTH_SHORT).show()
        }
    }
}
