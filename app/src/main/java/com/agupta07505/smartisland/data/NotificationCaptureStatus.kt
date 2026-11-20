/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationCaptureSnapshot(
    val listenerConnected: Boolean = false,
    val lastEventAtMillis: Long = 0L
)

/** In-process listener health plus a durable timestamp for the last observed notification. */
object NotificationCaptureStatus {
    private const val PREFS = "notification_capture_status"
    private const val KEY_LAST_EVENT = "last_event_at_ms"

    private val mutableStatus = MutableStateFlow(NotificationCaptureSnapshot())
    val status: StateFlow<NotificationCaptureSnapshot> = mutableStatus.asStateFlow()

    fun onListenerConnected(context: Context) {
        mutableStatus.value = NotificationCaptureSnapshot(
            listenerConnected = true,
            lastEventAtMillis = preferences(context).getLong(KEY_LAST_EVENT, 0L)
        )
    }

    fun onNotificationObserved(context: Context, timestampMillis: Long = System.currentTimeMillis()) {
        preferences(context).edit().putLong(KEY_LAST_EVENT, timestampMillis).apply()
        mutableStatus.value = mutableStatus.value.copy(lastEventAtMillis = timestampMillis)
    }

    fun onListenerDisconnected() {
        mutableStatus.value = mutableStatus.value.copy(listenerConnected = false)
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
