package com.d4viddf.hyperbridge.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.d4viddf.hyperbridge.R

/**
 * Reads the live state of the system functions HyperOS shows in its island:
 * flashlight, hotspot, Wi-Fi, mobile data, internet, Bluetooth, recorder and
 * music. Everything is read-only and guarded, so a denied permission simply
 * hides that indicator instead of breaking the island.
 *
 * Updates are event driven (broadcasts, network/audio/media callbacks and the
 * torch callback) instead of polling, so the indicators react instantly.
 */
class SystemStatusReader(
    context: Context,
    private val onChanged: (List<Function>) -> Unit
) {
    data class Function(val id: String, val iconRes: Int, val label: String)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    private var torchOn = false

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

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleEmit()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleEmit()
        override fun onLost(network: Network) = scheduleEmit()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = scheduleEmit()
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

        runCatching { cameraManager?.registerTorchCallback(torchCallback, mainHandler) }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WIFI_AP_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
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
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback, mainHandler)
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
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
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

        if (torchOn) {
            active += Function("flashlight", R.drawable.ic_system_flashlight, "Linterna")
        }

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiOn = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

        val hotspotOn = runCatching {
            val method = wifiManager?.javaClass?.getMethod("isWifiApEnabled") ?: return@runCatching false
            method.invoke(wifiManager) as? Boolean ?: false
        }.getOrDefault(false)
        if (hotspotOn) {
            active += Function("hotspot", R.drawable.ic_system_hotspot, "Zona Wi-Fi")
        }

        if (wifiOn) {
            active += Function("wifi", R.drawable.ic_system_wifi, "Wi-Fi")
        }

        val capabilities = runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()

        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            active += Function("data", R.drawable.ic_system_data, "Datos móviles")
        }

        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            active += Function("internet", R.drawable.ic_system_wifi, "Internet")
        }

        val bluetoothOn = runCatching {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        }.getOrDefault(false)
        if (bluetoothOn) {
            active += Function("bluetooth", R.drawable.ic_system_bluetooth, "Bluetooth")
        }

        val recording = runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.activeRecordingConfigurations?.isNotEmpty() == true
        }.getOrDefault(false)
        if (recording) {
            active += Function("recorder", R.drawable.ic_system_recorder, "Grabadora")
        }

        val musicPlaying = mediaCallbacks.values.any { (controller, _) ->
            runCatching {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }.getOrDefault(false)
        }
        if (musicPlaying) {
            active += Function("music", R.drawable.ic_system_music, "Música")
        }

        return active
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
                override fun onSessionDestroyed() = scheduleEmit()
            }
            mediaCallbacks[key] = controller to callback
            runCatching { controller.registerCallback(callback, mainHandler) }
        }
    }

    private companion object {
        const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EMIT_DEBOUNCE_MS = 120L
    }
}
