package com.d4viddf.hyperbridge.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.d4viddf.hyperbridge.models.NotificationCloneDetector

/**
 * Notification-driven reading for the island: clipboard reads, and any app
 * that is exposing a media session through the notification stack. This is the
 * second layer behind SystemStatusReader, and it is intentionally read-only.
 * It also performs clone-app detection at a deeper level for the overlay.
 */
class NotificationReaderService : NotificationListenerService() {

    companion object {
        const val ACTION_RELOAD_THEME = "com.d4viddf.hyperbridge.action.RELOAD_THEME"
    }

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
        val packageName = componentName.packageName
        if (NotificationCloneDetector.isCloneApp(packageName)) return true
        if (NotificationCloneDetector.isCloneLabel(componentName.shortClassName)) return true
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrNull() ?: return false
        return NotificationCloneDetector.isCloneLabel(appLabel)
    }
}
