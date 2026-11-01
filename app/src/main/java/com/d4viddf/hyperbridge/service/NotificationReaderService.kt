package com.d4viddf.hyperbridge.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.content.ContextCompat
import com.d4viddf.hyperbridge.R

/**
 * Notification-driven reading for the island: clipboard reads, and any app
 * that is exposing a media session through the notification stack. This is the
 * second layer behind SystemStatusReader, and it is intentionally read-only.
 * It also performs clone-app detection at a deeper level for the overlay.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        dispatch(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        dispatch(sbn)
    }

    private fun dispatch(sbn: StatusBarNotification?) {
        // Placeholder: clone and clipboard logic is wired from the overlay
        // when a real notification stream is needed.
    }

    private fun isCloneApp(componentName: ComponentName): Boolean {
        val packageName = componentName?.packageName ?: return false
        val label = componentName?.shortComponentName ?: return false
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrNull() ?: return false

        return packageLooksLikeClone(packageName) ||
            labelLooksLikeClone(label) ||
            appLabelLooksLikeClone(appLabel)
    }

    private fun packageLooksLikeClone(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return CLONE_PACKAGE_MARKERS.any { marker ->
            lower.endsWith(marker) || lower.contains(marker)
        }
    }

    private fun labelLooksLikeClone(label: String): Boolean {
        val lower = label.lowercase()
        return CLONE_NAME_MARKERS.any { marker ->
            lower.startsWith(marker) || lower.endsWith(marker) || lower.contains(" $marker")
        }
    }

    private fun appLabelLooksLikeClone(appLabel: String): Boolean {
        val lower = appLabel.lowercase()
        return CLONE_NAME_MARKERS.any { marker ->
            lower.startsWith(marker) || lower.endsWith(marker)
        }
    }

    /** Broad patterns seen in clone/second-space packages across ROMs. */
    private val CLONE_PACKAGE_MARKERS = listOf(
        ".clone", ".clon", ".dual", ".dualspace", ".parallel",
        ".second", ".secondspace", ".work", ".multi", ".appclone",
        "_clone", "_dual", "_parallel", ".cloneapp", ".dualapp"
    )

    /** Visible name markers added by clone apps. */
    private val CLONE_NAME_MARKERS = listOf(
        " clone", " clon", " dual", " paralelo", " second", "second ",
        " work", " multi", " app clone", " dual space", " espacio dual"
    )
}
