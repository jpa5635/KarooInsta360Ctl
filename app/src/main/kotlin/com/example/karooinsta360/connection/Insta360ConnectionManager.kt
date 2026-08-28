package com.example.karooinsta360.connection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.karooinsta360.AppSettings
import com.example.karooinsta360.Insta360BleClient
import com.example.karooinsta360.R
import com.example.karooinsta360.camera.CameraStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide singleton owning the BLE connection to every *saved* camera.
 *
 * One [Insta360BleClient] per camera address, keyed in [clients]. Both
 * [com.example.karooinsta360.extension.Insta360Extension] (auto-trigger) and the app's
 * UI (camera list, per-camera manual test controls in `CameraConfigActivity`) go through
 * this rather than creating their own clients — a camera is one BLE peripheral serving
 * one local connection, and running two independent clients against the same address
 * corrupts state (this bit us once already, back when there was only one camera and the
 * app and the extension each held their own client).
 */
object Insta360ConnectionManager {
    private const val TAG = "Insta360ConnMgr"
    private const val SCAN_RETRY_DELAY_MS = 5_000L
    private const val NOTIFICATION_CHANNEL_ID = "recording_state"

    @Volatile private var notificationChannelCreated = false

    interface Listener {
        /** Fired whenever a camera's connected/recording state changes, or the saved list changes. */
        fun onCameraStateChanged(address: String) {}
    }

    data class CameraState(
        val address: String,
        val name: String,
        val connected: Boolean,
        val recording: Boolean,
    )

    /**
     * Who is responsible for a camera's *current* recording — i.e. who would need to be
     * the one to stop it. Set by [startCapture], cleared back to [NONE] by anything that
     * sets recording state to false (see [setRecording]). This is separate from, and a
     * permanent complement to, [pauseAutomation]/[isAutomationPaused]: the pause map only
     * shields a manual action from being immediately reversed within the monitor's ~1s
     * poll window, and is only ever cleared by opening+closing `CameraConfigActivity`.
     * Ownership is what [Insta360Extension]'s monitor checks every single tick, for as
     * long as the recording lasts, before it's allowed to call [stopCapture] — so a
     * recording this extension didn't start (owner [MANUAL] or [NONE], the latter meaning
     * "recording, but we never saw it start" — e.g. the camera's own shutter button) can
     * never be auto-stopped, with or without a pause in effect.
     */
    enum class RecordingOwner { NONE, AUTOMATIC, MANUAL }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private val clients = ConcurrentHashMap<String, Insta360BleClient>()
    private val connectedFlags = ConcurrentHashMap<String, Boolean>()
    private val recordingFlags = ConcurrentHashMap<String, Boolean>()
    private val recordingOwner = ConcurrentHashMap<String, RecordingOwner>()

    // Addresses currently under manual test control (see pauseAutomation doc below).
    private val automationPaused = ConcurrentHashMap<String, Boolean>()

    private var discoveryScanCallback: ScanCallback? = null

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isConnected(address: String): Boolean = connectedFlags[address] == true

    fun isRecording(address: String): Boolean = recordingFlags[address] == true

    /** Who started the camera's current recording — see the [RecordingOwner] doc above. */
    fun recordingOwner(address: String): RecordingOwner = recordingOwner[address] ?: RecordingOwner.NONE

    /**
     * While a camera is paused, [Insta360Extension]'s automatic per-camera monitor
     * still tracks its heart-rate/power/speed latches internally but will not act on
     * them — it won't call [startCapture]/[stopCapture] for this address at all. This is
     * a short-lived shield against the ~1s poll race, not the thing protecting a manual
     * recording long-term — see [RecordingOwner] for that.
     *
     * [CameraConfigActivity] calls this for its own camera address while visible, so
     * its manual Start/Stop test buttons aren't immediately fought by the automatic
     * monitor's next poll tick (see the comment on `runCameraMonitor`'s final combine
     * step for how that fight looked in practice: a manual Start followed ~1 second
     * later by an automatic Stop, from the monitor concluding "nothing crossed a
     * threshold, so this shouldn't be recording").
     */
    fun pauseAutomation(address: String) {
        automationPaused[address] = true
    }

    /**
     * [CameraConfigActivity] calls this in `onPause()` to hand a camera back to
     * automation when its screen closes. [startAllCameras]/[stopAllCameras] (Control
     * Center, the ride-page tile, the controller-button BonusAction) also pause on every
     * camera they touch, for the same reason `CameraConfigActivity` does — but unlike
     * `CameraConfigActivity`, those three have no "screen closes" moment to resume from,
     * so their pause is left in place rather than being cleared automatically. That's no
     * longer the thing keeping a manually-started recording safe, though — [RecordingOwner]
     * is: a leftover pause only blocks the monitor from *starting* a recording it thinks
     * should be running, never from *stopping* one it doesn't own, so there's no
     * correctness reason to reopen Configure just to "release" a camera anymore, only a
     * convenience one (silencing the automatic triggers again on this camera sooner).
     */
    fun resumeAutomation(address: String) {
        automationPaused.remove(address)
    }

    fun isAutomationPaused(address: String): Boolean = automationPaused[address] == true

    fun getCameraStates(context: Context): List<CameraState> =
        CameraStore.getCameras(context).map { cfg ->
            CameraState(cfg.address, cfg.name, isConnected(cfg.address), isRecording(cfg.address))
        }

    fun isAnyCameraRecording(context: Context): Boolean =
        getCameraStates(context).any { it.recording }

    /**
     * Starts every saved, connected, not-already-recording camera. Used by the
     * "act on everything at once" manual overrides — the Karoo Control Center
     * notification, the tappable ride-page tile, and the controller-button BonusAction —
     * none of which have a way to pick out one specific camera from a single tap/press.
     *
     * Calls [pauseAutomation] on every camera it actually starts, for the exact same
     * reason [CameraConfigActivity] does: without it, [Insta360Extension]'s monitor would
     * see its own trigger conditions still unmet on its very next ~1s poll tick and
     * immediately call [stopCapture] again — which is exactly what "a manual Start
     * immediately stops right after" looks like, and is only *not* an issue for
     * `CameraConfigActivity`'s own manual buttons because that screen already
     * pauses/resumes automation around its whole visible lifetime. These three surfaces
     * have no such lifetime (a single tap, not an open screen) — see [resumeAutomation]'s
     * doc comment for how a camera gets handed back to automation afterwards.
     */
    fun startAllCameras(context: Context) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isConnected(cfg.address) && !isRecording(cfg.address)) {
                pauseAutomation(cfg.address)
                startCapture(cfg.address, RecordingOwner.MANUAL)
            }
        }
    }

    /**
     * Stops every saved camera currently recording. See [startAllCameras] — pauses
     * automation on every camera it actually stops for the mirror-image reason: left
     * unpaused, a camera whose auto-trigger condition is still true would get an
     * immediate automatic [startCapture] right back on the monitor's next tick, which
     * would look like "tapped Stop and it just started recording again."
     */
    fun stopAllCameras(context: Context) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isRecording(cfg.address)) {
                pauseAutomation(cfg.address)
                stopCapture(cfg.address)
            }
        }
    }

    /** Stops everything if anything's recording, otherwise starts everything. See [startAllCameras]. */
    fun toggleAllCameras(context: Context) {
        if (isAnyCameraRecording(context)) stopAllCameras(context) else startAllCameras(context)
    }

    /** Connects to every saved camera not already connected/connecting. Safe to call repeatedly. */
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        CameraStore.getCameras(context).forEach { connectToSaved(it.address) }
    }

    /** Saves a new camera (address/name from a scan or entered manually) and connects to it. */
    fun addCamera(context: Context, address: String, name: String) {
        appContext = context.applicationContext
        CameraStore.addOrUpdateCamera(context, CameraStore.CameraConfig(address = address, name = name))
        connectToSaved(address)
        notifyChanged(address)
    }

    fun removeCamera(context: Context, address: String) {
        disconnect(address)
        CameraStore.removeCamera(context, address)
        notifyChanged(address)
    }

    /**
     * [owner] records who's responsible for the recording this starts, so
     * [Insta360Extension]'s monitor knows later whether it's allowed to stop it again —
     * see [RecordingOwner]. Callers: the monitor itself passes [RecordingOwner.AUTOMATIC];
     * everything else — `CameraConfigActivity`'s Start button, [startAllCameras] (Control
     * Center/ride-page tile/BonusAction) — passes [RecordingOwner.MANUAL].
     */
    fun startCapture(address: String, owner: RecordingOwner) {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "startCapture($address) ignored — not connected")
            return
        }
        client.startCapture()
        setRecording(address, true, owner)
        notifyRecordingChanged(address, recording = true)
    }

    fun stopCapture(address: String) {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "stopCapture($address) ignored — not connected")
            return
        }
        client.stopCapture()
        setRecording(address, false)
        notifyRecordingChanged(address, recording = false)
    }

    // No longer wired to any button — see the 2026-08-27 README entry. On the X4 Air,
    // sending CheckAuthorization (with this app's BLE address used as a made-up
    // authorization_id the camera has never actually paired against) left the camera
    // ignoring subsequent Start commands entirely, for the rest of that connection.
    // startCapture()/stopCapture() work fine without ever calling this first, so it's
    // not required — kept only in case a future camera model's protocol needs it.
    fun checkAuthorization(address: String) {
        clients[address]?.checkAuthorization()
    }

    /**
     * Scans for nearby BLE devices not yet saved as a camera. [onFound] may fire more
     * than once per address.
     *
     * Deliberately **not** filtered by name prefix. This used to only surface devices
     * starting with "Ace Pro", which silently hid any other Insta360 model (an X4 Air,
     * for instance, doesn't advertise with that prefix at all — different cameras use
     * different naming, and there's no single prefix that reliably covers all of them).
     * Showing everything nearby means more scroll, but it can never hide the camera
     * you're looking for.
     */
    @Suppress("MissingPermission")
    fun startDiscoveryScan(
        context: Context,
        durationMs: Long = 10_000L,
        onFound: (address: String, name: String) -> Unit,
        onFinished: () -> Unit,
    ) {
        appContext = context.applicationContext
        if (!hasBlePermissions(context)) {
            onFinished()
            return
        }
        val adapter = bluetoothAdapter(context)
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            onFinished()
            return
        }

        stopDiscoveryScan(context)
        val callback = object : ScanCallback() {
            @Suppress("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                onFound(result.device.address, name)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Discovery scan failed: $errorCode")
            }
        }
        discoveryScanCallback = callback
        scanner.startScan(callback)
        handler.postDelayed({
            stopDiscoveryScan(context)
            onFinished()
        }, durationMs)
    }

    @Suppress("MissingPermission")
    fun stopDiscoveryScan(context: Context) {
        val callback = discoveryScanCallback ?: return
        bluetoothAdapter(context)?.bluetoothLeScanner?.stopScan(callback)
        discoveryScanCallback = null
    }

    /**
     * [owner] is only meaningful when [recording] is true — starting a recording records
     * who owns it; stopping one (from here, or via connect/disconnect resetting the flag)
     * always clears ownership back to [RecordingOwner.NONE], regardless of what's passed,
     * since "not recording" has no owner.
     */
    private fun setRecording(address: String, recording: Boolean, owner: RecordingOwner = RecordingOwner.NONE) {
        recordingFlags[address] = recording
        recordingOwner[address] = if (recording) owner else RecordingOwner.NONE
        notifyChanged(address)
    }

    private fun notifyChanged(address: String) {
        listeners.forEach { it.onCameraStateChanged(address) }
    }

    /**
     * Posts a status-bar notification for a genuine start/stop action — called only from
     * [startCapture]/[stopCapture] themselves, deliberately NOT from [setRecording] in
     * general, since that's also called from connect/disconnect handling below just to
     * reset the flag to a known state (not an actual "recording changed" event; posting
     * from there would fire a false "stopped" notification every time a camera merely
     * reconnects). Silently does nothing if the user hasn't turned this on, or (Android
     * 13+) hasn't granted notification permission — this is a nice-to-have, never worth
     * crashing or logging an error over.
     */
    private fun notifyRecordingChanged(address: String, recording: Boolean) {
        val context = appContext ?: return
        if (!AppSettings.isRecordingNotificationEnabled(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureNotificationChannel(notificationManager)

        val name = CameraStore.getCamera(context, address)?.name ?: address
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_extension)
            .setContentTitle(if (recording) "Recording started" else "Recording stopped")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        // One notification ID per camera address, so a second camera's start/stop doesn't
        // replace the first camera's still-relevant notification in the drawer.
        notificationManager.notify(address.hashCode(), notification)
    }

    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (notificationChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notifies when a saved camera starts or stops recording"
            },
        )
        notificationChannelCreated = true
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBlePermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Suppress("MissingPermission")
    private fun connectToSaved(address: String) {
        val context = appContext ?: return
        if (isConnected(address) || clients.containsKey(address)) return
        if (!hasBlePermissions(context)) {
            Log.w(TAG, "Bluetooth permissions not granted yet — open the app once to grant them")
            retryLater(address)
            return
        }
        val adapter = bluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            retryLater(address)
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid saved address $address")
            return
        }
        connectDevice(context, device)
    }

    private fun retryLater(address: String) {
        handler.postDelayed(
            {
                // Only retry if still saved — avoids reconnect loops for a camera the
                // user just removed.
                val stillSaved = appContext?.let { CameraStore.getCamera(it, address) != null } ?: false
                if (stillSaved) connectToSaved(address)
            },
            SCAN_RETRY_DELAY_MS,
        )
    }

    private fun disconnect(address: String) {
        clients.remove(address)?.disconnect()
        connectedFlags[address] = false
        recordingFlags[address] = false
        recordingOwner[address] = RecordingOwner.NONE
    }

    @Suppress("MissingPermission")
    private fun connectDevice(context: Context, device: BluetoothDevice) {
        val address = device.address
        val client = Insta360BleClient(
            context,
            object : Insta360BleClient.Listener {
                override fun onConnected() {
                    Log.i(TAG, "Camera $address connected")
                    connectedFlags[address] = true
                    setRecording(address, false)
                }

                override fun onDisconnected() {
                    Log.i(TAG, "Camera $address disconnected")
                    connectedFlags[address] = false
                    setRecording(address, false)
                    clients.remove(address)
                    retryLater(address)
                }

                override fun onCommandResponse(commandCode: Int, sequence: Int, payload: ByteArray) {
                    Log.d(TAG, "[$address] Response cmd=$commandCode seq=$sequence len=${payload.size}")
                }

                override fun onNotification(notificationCode: Int, payload: ByteArray) {
                    Log.d(TAG, "[$address] Notification code=$notificationCode len=${payload.size}")
                }

                override fun onError(message: String) {
                    Log.e(TAG, "[$address] BLE error: $message")
                }
            },
        )
        clients[address] = client
        client.connect(device)
    }
}
