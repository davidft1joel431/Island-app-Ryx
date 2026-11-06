package com.d4viddf.hyperbridge.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.d4viddf.hyperbridge.R

/**
 * Reads the live state of the system functions HyperOS shows in its island:
 * flashlight, hotspot, Bluetooth, recorder and music. Everything is read-only
 * and guarded, so a denied permission simply hides that indicator instead of
 * breaking the island. Connectivity indicators (Wi-Fi, mobile data, internet)
 * are intentionally not included: the status bar already shows them.
 *
 * Updates are event driven (broadcasts, network/audio/media callbacks and the
 * torch callback) instead of polling, so the indicators react instantly.
 */
class SystemStatusReader(
    context: Context,
    private val onChanged: (List<Function>) -> Unit
) {
    data class Function(
        val id: String,
        val iconRes: Int,
        val label: String,
        val badge: Int = 0,
        val detail: String = "",
        val packageName: String? = null,
        val mediaController: MediaController? = null,
        val isPlaying: Boolean = false
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    private var torchOn = false

    @Volatile
    private var hotspotOn: Boolean? = null

    private var started = false
    private var lastSnapshot: List<Function> = emptyList()

    // Coalesces bursts of broadcasts (e.g. Wi-Fi + connectivity together).
    private val emitTask = Runnable {
        val snapshot = snapshot()
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            onChanged(snapshot)
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
            scheduleEmit()
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            torchOn = false
            scheduleEmit()
        }
    }

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WIFI_AP_STATE_CHANGED) {
                hotspotOn = intent.getIntExtra(WIFI_AP_STATE_EXTRA, WIFI_AP_STATE_DISABLED) == WIFI_AP_STATE_ENABLED
            }
            scheduleEmit()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            scheduleEmit()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener {
            bindMediaControllers(it)
            scheduleEmit()
        }

    private val mediaCallbacks =
        mutableMapOf<String, Pair<MediaController, MediaController.Callback>>()

    fun start() {
        if (started) return
        started = true

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        hotspotOn = isHotspotEnabled(wifiManager)

        runCatching { cameraManager?.registerTorchCallback(torchCallback, mainHandler) }

        val filter = IntentFilter().apply {
            addAction(WIFI_AP_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioRecordingCallback(recordingCallback, mainHandler)
        }

        runCatching {
            val mediaSessionManager =
                appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val listener = ComponentName(appContext, NotificationReaderService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsListener,
                listener,
                mainHandler
            )
        }

        bindMediaControllers()
        scheduleEmit()
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(emitTask)

        runCatching { cameraManager?.unregisterTorchCallback(torchCallback) }
        runCatching { appContext.unregisterReceiver(stateReceiver) }
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        }
        runCatching {
            val mediaSessionManager =
                appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        }

        mediaCallbacks.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        mediaCallbacks.clear()
    }

    /** Active system functions, in the order HyperOS usually displays them. */
    fun snapshot(): List<Function> {
        val active = mutableListOf<Function>()

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (hotspotOn ?: isHotspotEnabled(wifiManager) ?: false) {
            val hotspotName = readHotspotName(wifiManager)?.takeIf { it.isNotBlank() }
            active += Function(
                "hotspot",
                R.drawable.ic_system_hotspot,
                hotspotName ?: "Zona Wi-Fi"
            )
        }

        if (torchOn) {
            active += Function("flashlight", R.drawable.ic_system_flashlight, "Linterna")
        }

        val bluetoothOn = runCatching {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            manager?.adapter?.isEnabled == true
        }.getOrDefault(false)
        if (bluetoothOn) {
            active += Function("bluetooth", R.drawable.ic_system_bluetooth, "Bluetooth")
        }

        val musicPlaying = mediaCallbacks.values.any { (controller, _) ->
            runCatching {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }.getOrDefault(false)
        }
        if (musicPlaying) {
            val playing = mediaCallbacks.values.firstNotNullOfOrNull { (controller, _) ->
                runCatching {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller else null
                }.getOrNull()
            }
            val metadata = playing?.metadata
            val track = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            active += Function(
                "music", R.drawable.ic_system_music, "Música",
                detail = listOf(track, artist).filter { it.isNotBlank() }.joinToString(" · "),
                packageName = playing?.packageName,
                mediaController = playing,
                isPlaying = true
            )
        }

        val recording = runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.activeRecordingConfigurations?.isNotEmpty() == true
        }.getOrDefault(false)
        if (recording) {
            active += Function("recorder", R.drawable.ic_system_recorder, "Grabadora")
        }

        return active
    }

    /**
     * Hidden framework APIs still expose the hotspot state on most ROMs. Both
     * entry points are guarded because Android may block either one.
     */
    private fun isHotspotEnabled(wifiManager: WifiManager?): Boolean? {
        val manager = wifiManager ?: return null
        val enabled = runCatching {
            val enabled = manager.javaClass.getMethod("isWifiApEnabled").invoke(manager) as? Boolean
            enabled
        }.getOrNull()
        if (enabled != null) return enabled
        return runCatching {
            val state = manager.javaClass.getMethod("getWifiApState").invoke(manager) as? Int
            state?.let { it == WIFI_AP_STATE_ENABLED }
        }.getOrNull()
    }

    /** Read the configured Soft AP name (SSID) across Android and MIUI APIs. */
    private fun readHotspotName(wifiManager: WifiManager?): String? {
        val manager = wifiManager ?: return null
        return runCatching {
            val configuration = manager.javaClass.getMethod("getSoftApConfiguration").invoke(manager)
            configuration?.javaClass?.getMethod("getSsid")?.invoke(configuration) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val configuration = manager.javaClass.getMethod("getWifiApConfiguration").invoke(manager)
                configuration?.javaClass?.getField("SSID")?.get(configuration) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun scheduleEmit() {
        mainHandler.removeCallbacks(emitTask)
        mainHandler.postDelayed(emitTask, EMIT_DEBOUNCE_MS)
    }

    private fun bindMediaControllers(
        controllers: List<MediaController>? = null
    ) {
        val manager =
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return
        val listener = ComponentName(appContext, NotificationReaderService::class.java)
        val current = controllers
            ?: runCatching { manager.getActiveSessions(listener) }.getOrNull()
            ?: return

        val currentKeys = current.map { it.sessionToken.toString() }.toSet()
        mediaCallbacks.keys.filter { it !in currentKeys }.forEach { key ->
            mediaCallbacks.remove(key)?.let { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }

        for (controller in current) {
            val key = controller.sessionToken.toString()
            if (mediaCallbacks.containsKey(key)) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleEmit()
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = scheduleEmit()
                override fun onSessionDestroyed() = scheduleEmit()
            }
            mediaCallbacks[key] = controller to callback
            runCatching { controller.registerCallback(callback, mainHandler) }
        }
    }

    private companion object {
        const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val WIFI_AP_STATE_EXTRA = "wifi_state"
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLED = 13
        const val EMIT_DEBOUNCE_MS = 120L
    }
}
