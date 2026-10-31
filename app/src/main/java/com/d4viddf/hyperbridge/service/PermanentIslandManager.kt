package com.d4viddf.hyperbridge.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Connects the permanent-island settings to Ryx Isla's own Android overlay. */
class PermanentIslandManager(
    context: Context,
    scope: CoroutineScope,
    preferences: AppPreferences,
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
                preferences.permanentIslandWidthFlow,
                preferences.hidePermanentIslandLandscapeFlow
            ) { isEnabled, width, hideInLandscape ->
                Triple(isEnabled, width, hideInLandscape)
            }.collectLatest { (isEnabled, width, hideInLandscape) ->
                enabled = isEnabled
                overlay.setPermanentIsland(isEnabled, width, hideInLandscape)
            }
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
