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
    // "_hi" because this used to be "recording_state" at IMPORTANCE_LOW — see
    // ensureNotificationChannel's doc comment for why the ID had to change, not just
    // the importance value, to actually fix anything for an existing install.
    private const val NOTIFICATION_CHANNEL_ID = "recording_state_hi"

    @Volatile private var notificationChannelCreated = false

    interface Listener {
        /** Fired whenever a camera's connected/recording state changes, or the saved list changes. */
        fun onCameraStateChanged(address: String) {}

        /**
         * Fired only for a genuine start/stop action — same event, same call sites
         * ([startCapture]/[stopCapture]), as the status-bar notification posted by
         * [notifyRecordingChanged] below; NOT fired from connect/disconnect resetting the
         * flag to a known state. Added (2026-08-29) so [Insta360Extension] can also raise
         * a karoo-ext [io.hammerhead.karooext.models.InRideAlert] for this — unlike a
         * plain Android notification, that's a native Karoo UI element guaranteed to
         * actually render on screen, addressing "the status-bar notification doesn't
         * seem to end up anywhere I can find it" independently of whatever Karoo does or
         * doesn't do with regular Android notifications for a sideloaded app.
         * [Insta360ConnectionManager] itself has no [io.hammerhead.karooext.KarooSystemService]
         * to dispatch through — only the extension does — hence this being a listener
         * callback rather than something done directly here.
         */
        fun onRecordingChanged(address: String, recording: Boolean) {}
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

    /** [CameraConfigActivity] calls this in `onPause()` to hand a camera back to automation when its screen closes. */
    fun resumeAutomation(address: String) {
        automationPaused.remove(address)
    }

    /**
     * Like [pauseAutomation], but clears itself after [durationMs] instead of waiting for
     * an explicit [resumeAutomation] call — used by [stopAllCameras], which (unlike
     * [CameraConfigActivity]) has no "screen closes" moment of its own to resume from.
     *
     * **Fixed (2026-08-29):** [startAllCameras]/[stopAllCameras] both used to call the
     * indefinite [pauseAutomation] instead, on the theory that they needed the same
     * protection `CameraConfigActivity` gives itself. That was true before recording
     * ownership tracking existed, but became actively harmful once it landed: any single
     * ride-page-tile/BonusAction tap left that camera's automation paused
     * *forever*, silently, since nothing was left to ever call [resumeAutomation] for it —
     * the automatic heart-rate/power/speed/radar triggers would just stop responding to
     * that camera until someone happened to open and close its Configure screen. That's
     * what "triggers stopped working after I started and stopped a recording" was.
     *
     * With [RecordingOwner] now the thing permanently protecting a manually-started
     * recording from being auto-stopped, [startAllCameras] doesn't need to pause at all
     * (there's nothing for it to protect against — see its doc comment). [stopAllCameras]
     * still needs *something*: right after a manual Stop, if the automatic trigger
     * condition is still independently true, the monitor's very next ~1s tick would
     * otherwise see "should be recording, isn't" and immediately start it right back up.
     * That only needs to survive one poll tick, though, not last indefinitely — hence a
     * short, self-clearing pause instead of a permanent one.
     */
    fun pauseAutomationBriefly(address: String, durationMs: Long = 3_000L) {
        automationPaused[address] = true
        handler.postDelayed({ automationPaused.remove(address) }, durationMs)
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
     * "act on everything at once" manual overrides — the tappable ride-page tile and the
     * controller-button BonusAction — neither of which has a way to pick out one specific
     * camera from a single tap/press. (The Karoo Control Center notification used to be a
     * third such surface — removed 2026-08-29 since Control Center is hidden for the
     * whole duration of any ride, making it useless for the in-ride case this app cares
     * about; see the README.)
     *
     * Doesn't pause automation at all — doesn't need to. [startCapture] here passes
     * [RecordingOwner.MANUAL], and [Insta360Extension]'s monitor only ever stops a
     * recording it owns itself ([RecordingOwner.AUTOMATIC]), so there's no tick on which
     * it would try to reverse this regardless of pause state. (There used to be a
     * [pauseAutomation] call here, before ownership tracking existed — see
     * [pauseAutomationBriefly]'s doc comment for why that turned into a bug.)
     */
    fun startAllCameras(context: Context) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isConnected(cfg.address) && !isRecording(cfg.address)) {
                startCapture(cfg.address, RecordingOwner.MANUAL, reason = "manual start-all (ride-page tile / BonusAction)")
            }
        }
    }

    /**
     * Stops every saved camera currently recording. Unlike [startAllCameras], this still
     * needs a brief pause (see [pauseAutomationBriefly]): stopping clears ownership back
     * to [RecordingOwner.NONE], so if the automatic trigger condition is still
     * independently true, the monitor's very next ~1s tick would otherwise read that as
     * "should be recording, isn't" and immediately start it right back up — "tapped Stop
     * and it just started recording again."
     */
    fun stopAllCameras(context: Context) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isRecording(cfg.address)) {
                pauseAutomationBriefly(cfg.address)
                stopCapture(cfg.address, reason = "manual stop-all (ride-page tile / BonusAction)")
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
     * Forces a fresh connection attempt for a saved camera — the "Reconnect" button on
     * each camera's row in `MainActivity`'s camera list, added (2026-08-29) for "sometimes
     * the timing of the power between the computer and camera don't match." (Originally
     * placed on `CameraConfigActivity`'s screen instead; moved to the main camera list the
     * same day so it's reachable without opening Configure first.)
     *
     * That timing mismatch (e.g. the Karoo powers on and starts trying to connect before
     * the camera has finished booting, or vice versa) can leave a [Insta360BleClient] in
     * [clients] that never resolves to either `onConnected()` or `onDisconnected()` — a
     * GATT connect call that just never calls back. [connectToSaved]'s guard,
     * `if (isConnected(address) || clients.containsKey(address)) return`, means that once
     * a camera is in that stuck state, nothing will ever retry it: it's not connected (so
     * the UI correctly shows "Disconnected"), but it *is* still in [clients], so every
     * automatic retry from [retryLater] just sees the stale entry and gives up again
     * without lifting a finger. Previously the only fix was force-closing the app so
     * process death cleared [clients] from scratch.
     *
     * [disconnect] tears down and removes whatever's there (a stuck client, a genuinely
     * connected one, or nothing at all — safe either way), then [connectToSaved] starts
     * a genuinely new attempt now that [clients] no longer blocks it.
     */
    fun reconnect(context: Context, address: String) {
        appContext = context.applicationContext
        disconnect(address)
        notifyChanged(address)
        connectToSaved(address)
    }

    /**
     * [owner] records who's responsible for the recording this starts, so
     * [Insta360Extension]'s monitor knows later whether it's allowed to stop it again —
     * see [RecordingOwner]. Callers: the monitor itself passes [RecordingOwner.AUTOMATIC];
     * everything else — `CameraConfigActivity`'s Start button, [startAllCameras] (ride-page
     * tile/BonusAction) — passes [RecordingOwner.MANUAL].
     *
     * [reason] (added 2026-08-29) is a short human-readable description of *why* this call
     * was made — e.g. the specific latch/values that crossed threshold, or "manual start
     * button" — logged on both the success and the "ignored — not connected" path so the
     * extension-layer "why we decided to start" and this layer's "what actually happened"
     * can be correlated from logcat alone, without cross-referencing timestamps between two
     * unrelated-looking log lines.
     */
    fun startCapture(address: String, owner: RecordingOwner, reason: String = "unspecified") {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "startCapture($address) IGNORED — not connected (reason: $reason)")
            return
        }
        client.startCapture()
        setRecording(address, true, owner)
        Log.i(TAG, "startCapture($address) SENT — owner=$owner reason=$reason")
        onGenuineRecordingAction(address, recording = true)
    }

    /** See [startCapture]'s doc comment for what [reason] is and why it's here. */
    fun stopCapture(address: String, reason: String = "unspecified") {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "stopCapture($address) IGNORED — not connected (reason: $reason)")
            return
        }
        client.stopCapture()
        setRecording(address, false)
        Log.i(TAG, "stopCapture($address) SENT — reason=$reason")
        onGenuineRecordingAction(address, recording = false)
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
     * The single call site for "a genuine start/stop action just happened" — called only
     * from [startCapture]/[stopCapture] themselves, deliberately NOT from [setRecording]
     * in general, since that's also called from connect/disconnect handling below just to
     * reset the flag to a known state (not an actual "recording changed" event; firing
     * from there would announce a false "stopped" every time a camera merely reconnects).
     * Fans out to both the status-bar notification and [Listener.onRecordingChanged].
     */
    private fun onGenuineRecordingAction(address: String, recording: Boolean) {
        notifyRecordingChanged(address, recording)
        listeners.forEach { it.onRecordingChanged(address, recording) }
    }

    /**
     * Posts a status-bar notification for a genuine start/stop action. Silently does
     * nothing if the user hasn't turned this on, or (Android 13+) hasn't granted
     * notification permission — this is a nice-to-have, never worth crashing or logging
     * an error over.
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        // One notification ID per camera address, so a second camera's start/stop doesn't
        // replace the first camera's still-relevant notification in the drawer.
        notificationManager.notify(address.hashCode(), notification)
    }

    /**
     * **Fixed (2026-08-29) — the recording start/stop notification wasn't showing up
     * during a ride.** This channel used to be `IMPORTANCE_LOW`/`PRIORITY_LOW`, which on
     * Android means "sits silently in the notification shade, no heads-up banner." That's
     * invisible by design behind whatever fullscreen ride page is on screen — there's no
     * shade to pull down mid-ride the way there is on a phone. Bumped to
     * `IMPORTANCE_HIGH`/`PRIORITY_HIGH` so it's a heads-up notification that actually
     * banners on top of the current screen instead.
     *
     * Critically, a `NotificationChannel`'s importance is fixed at creation — Android
     * ignores it on every later `createNotificationChannel()` call for the same channel
     * ID, silently keeping whatever importance the channel had the first time it was ever
     * created on that device. Just changing `IMPORTANCE_LOW` to `IMPORTANCE_HIGH` in code
     * would have done nothing for anyone who'd already run an earlier build — the old
     * `"recording_state"` channel would still be sitting there at LOW. Changing the
     * channel ID (see [NOTIFICATION_CHANNEL_ID]) instead makes this a genuinely new
     * channel that picks up the new importance from scratch. The old channel is simply
     * abandoned — Android has no "rename a channel" operation, and there's nothing
     * meaningful to migrate (no per-channel settings this app sets that would be worth
     * carrying over, and the user can delete the old "Recording status" channel by hand
     * from the app's system notification settings if they want it gone, though leaving it
     * is harmless).
     */
    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (notificationChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording status",
                NotificationManager.IMPORTANCE_HIGH,
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
