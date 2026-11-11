/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Conversation identity and visible name exposed by the sender's notification. */
data class NotificationConversation(val key: String, val packageName: String, val title: String)

object NotificationConversationRules {
    private const val KEY_SEPARATOR = "\u001f"

    fun fromNotification(
        notification: Notification,
        packageName: String,
        channelConversationId: String? = null
    ): NotificationConversation? {
        val extras = notification.extras ?: return null
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        val isMessaging = style != null ||
            extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MessagingStyle") == true ||
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.isNotEmpty() == true ||
            notification.category == Notification.CATEGORY_MESSAGE
        if (!isMessaging) return null

        val conversationTitle = style?.conversationTitle?.toString()?.trim().orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty() }
        val notificationTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val sender = style?.messages?.lastOrNull()?.person?.name?.toString()?.trim().orEmpty()
        val displayTitle = conversationTitle.ifBlank { notificationTitle.ifBlank { sender } }
            .take(120)
            .ifBlank { return null }

        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) notification.shortcutId else null
        val stablePart = channelConversationId?.takeIf { it.isNotBlank() }
            ?: shortcutId?.takeIf { it.isNotBlank() }
            ?: conversationTitle.takeIf { it.isNotBlank() }
            ?: displayTitle
        if (packageName.length >= 254) return null
        val safePartLength = (255 - packageName.length - KEY_SEPARATOR.length).coerceAtLeast(1)
        val key = packageName + KEY_SEPARATOR + stablePart.trim().lowercase(Locale.ROOT).take(safePartLength)
        return NotificationConversation(key, packageName, displayTitle)
    }

    fun packageFromKey(key: String): String? = key.substringBefore(KEY_SEPARATOR).takeIf { KEY_SEPARATOR in key }
}
