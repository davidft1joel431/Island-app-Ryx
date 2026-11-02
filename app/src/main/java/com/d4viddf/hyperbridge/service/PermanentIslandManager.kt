package com.d4viddf.hyperbridge.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Connects the permanent-island settings to Ryx Isla's own Android overlay. */
class PermanentIslandManager(
    context: Context,
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val overlay: GenericIslandOverlay
) {
    companion object {
        const val PERMANENT_BRIDGE_ID = 9999
    }

    private val appContext = context.applicationContext
    @Volatile private var enabled = false

    init {
        NotificationManagerCompat.from(appContext).cancel(PERMANENT_BRIDGE_ID)
        scope.launch {
            combine(
                preferences.isPermanentIslandEnabledFlow,
                preferences.hidePermanentIslandLandscapeFlow
            ) { isEnabled, hideInLandscape ->
                isEnabled to hideInLandscape
            }.collectLatest { (isEnabled, hideInLandscape) ->
                enabled = isEnabled
                overlay.setPermanentIsland(isEnabled, hideInLandscape)
            }
        }
    }

    /**
     * Re-reads the settings and re-applies them. Used when the app returns to
     * the foreground, so granting the overlay permission or enabling the
     * permanent island immediately brings the capsule back.
     */
    fun refresh() {
        scope.launch {
            val isEnabled = preferences.isPermanentIslandEnabledFlow.first()
            val hideInLandscape = preferences.hidePermanentIslandLandscapeFlow.first()
            enabled = isEnabled
            overlay.setPermanentIsland(isEnabled, hideInLandscape)
        }
    }

    fun isIslandActive(): Boolean = enabled && overlay.isPermanentIslandVisible()

    fun onActiveNotificationsChanged(count: Int, hasNative: Boolean = false) {
        // Notification overlays replace the empty capsule temporarily.
    }

    fun onOrientationChanged() {
        overlay.onOrientationChanged()
    }

    fun reconcile(count: Int, hasNative: Boolean, isIslandPresent: Boolean, refresh: Boolean) {
        overlay.onOrientationChanged()
    }
}
