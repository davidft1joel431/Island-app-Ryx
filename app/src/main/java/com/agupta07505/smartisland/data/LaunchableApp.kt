/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import com.agupta07505.smartisland.R

data class LaunchableApp(
    /** Package name for the main profile; a profile-qualified identity for a cloned app. */
    val packageName: String,
    val label: String
)

/** Encodes a profile serial in pinned-app entries without changing existing package-only entries. */
object AppIdentity {
    private const val PROFILE_SEPARATOR = "@ryx-profile:"

    data class Parsed(val packageName: String, val userSerial: Long?)

    fun parse(identity: String): Parsed {
        val separator = identity.lastIndexOf(PROFILE_SEPARATOR)
        if (separator <= 0) return Parsed(identity, null)
        val serial = identity.substring(separator + PROFILE_SEPARATOR.length).toLongOrNull()
            ?: return Parsed(identity, null)
        return Parsed(identity.substring(0, separator), serial)
    }

    fun forProfile(packageName: String, user: UserHandle, context: Context): String {
        if (user == Process.myUserHandle()) return packageName
        val serial = context.getSystemService(UserManager::class.java)
            ?.getSerialNumberForUser(user)
            ?.takeIf { it >= 0 }
            ?: return packageName
        return "$packageName$PROFILE_SEPARATOR$serial"
    }
}

object AppShortcutProvider {
    /** The pinned entries whose apps are still installed, in the order they were pinned. */
    fun selectedApps(context: Context, packages: List<String>): List<LaunchableApp> {
        val installed = installedApps(context, includeClones = true).associateBy { it.packageName }
        return packages.mapNotNull { entry ->
            if (PinnedApps.isFolder(entry)) {
                val members = PinnedApps.members(entry).mapNotNull(installed::get)
                if (members.isEmpty()) null
                else LaunchableApp(entry, members.joinToString(", ") { it.label })
            } else installed[entry]
        }
    }

    /** Lists launcher apps from the current Android user and its clone/work profiles. */
    fun installedApps(context: Context, includeClones: Boolean = false): List<LaunchableApp> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val profiles = runCatching { launcherApps?.profiles.orEmpty() }.getOrDefault(emptyList())
            .filter { includeClones || it == Process.myUserHandle() }
        val fromProfiles = profiles.flatMap { user ->
            runCatching { launcherApps?.getActivityList(null, user).orEmpty() }
                .getOrDefault(emptyList())
                .mapNotNull { activity ->
                    val pkg = activity.applicationInfo.packageName
                    if (pkg == context.packageName) return@mapNotNull null
                    val identity = AppIdentity.forProfile(pkg, user, context)
                    val label = activity.label?.toString().orEmpty().ifBlank { pkg }
                    val displayLabel = if (user == Process.myUserHandle()) label
                    else "$label ${context.getString(R.string.app_clone_suffix)}"
                    LaunchableApp(identity, displayLabel)
                }
        }
        val fallback = if (fromProfiles.isNotEmpty()) emptyList() else {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(launcherIntent, 0).map { info ->
                LaunchableApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(context.packageManager).toString()
                )
            }
        }
        return (fromProfiles + fallback)
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun icon(context: Context, identity: String): Drawable? {
        val parsed = AppIdentity.parse(identity)
        if (parsed.userSerial != null) {
            val user = context.getSystemService(UserManager::class.java)
                ?.getUserForSerialNumber(parsed.userSerial)
            val density = context.resources.displayMetrics.densityDpi
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            if (user != null && launcherApps != null) {
                return runCatching {
                    launcherApps.getActivityList(parsed.packageName, user)
                        .firstOrNull()?.getBadgedIcon(density)
                }.getOrNull()
            }
        }
        return runCatching { context.packageManager.getApplicationIcon(parsed.packageName) }.getOrNull()
    }

    fun openDetails(context: Context, identity: String): Boolean {
        val parsed = AppIdentity.parse(identity)
        val serial = parsed.userSerial ?: return false
        val user = context.getSystemService(UserManager::class.java)
            ?.getUserForSerialNumber(serial) ?: return false
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val activity = runCatching {
            launcherApps.getActivityList(parsed.packageName, user).firstOrNull()
        }.getOrNull() ?: return false
        return runCatching {
            launcherApps.startAppDetailsActivity(activity.componentName, user, null, null)
            true
        }.getOrDefault(false)
    }

    /** Launches the selected app in its own Android profile. */
    fun launch(context: Context, identity: String, options: Bundle? = null): Boolean {
        val parsed = AppIdentity.parse(identity)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (parsed.userSerial != null && launcherApps != null) {
            val user = context.getSystemService(UserManager::class.java)
                ?.getUserForSerialNumber(parsed.userSerial)
                ?: return false
            val activity = runCatching {
                launcherApps.getActivityList(parsed.packageName, user).firstOrNull()
            }.getOrNull() ?: return false
            return runCatching {
                launcherApps.startMainActivity(activity.componentName, user, null, options)
                true
            }.getOrDefault(false)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(parsed.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
    }

    fun hasUsageAccess(context: Context): Boolean {
        return runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun shortcuts(
        context: Context,
        selectedPackages: List<String>,
        includeRecent: Boolean,
        limit: Int = PinnedApps.MAX * 2
    ): List<LaunchableApp> {
        val installed = installedApps(context, includeClones = true)
        val byPackage = installed.associateBy { it.packageName }
        val selected = selectedPackages.mapNotNull { entry ->
            if (PinnedApps.isFolder(entry)) {
                val members = PinnedApps.members(entry).mapNotNull(byPackage::get)
                if (members.isEmpty()) null else LaunchableApp(entry, members.joinToString(", ") { it.label })
            } else byPackage[entry]
        }
        val pinnedPackages = PinnedApps.flatPackages(selectedPackages).toSet()
        if (!includeRecent || !hasUsageAccess(context)) return selected.take(limit)

        val end = System.currentTimeMillis()
        val start = end - RECENT_WINDOW_MS
        val usage = runCatching {
            val usageManager = context.getSystemService(UsageStatsManager::class.java)
            usageManager?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        }.getOrDefault(emptyList())
            .sortedByDescending { it.lastTimeUsed }
            .mapNotNull { stat ->
                byPackage[stat.packageName] ?: byPackage[AppIdentity.parse(stat.packageName).packageName]
            }
            .filter { it.packageName !in pinnedPackages }
            .distinctBy { it.packageName }

        return (selected + usage).distinctBy { it.packageName }.take(limit)
    }

    private const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
}
