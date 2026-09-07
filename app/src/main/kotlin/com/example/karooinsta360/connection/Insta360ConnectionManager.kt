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
import com.example.karooinsta360.RecordingReason
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

    /** See [statusPoll]. Ten seconds bounds how stale the recording indicator can get. */
    private const val STATUS_POLL_INTERVAL_MS = 10_000L
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
        fun onRecordingChanged(address: String, recording: Boolean, reason: RecordingReason) {}
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
    fun startAllCameras(
        context: Context,
        manualSource: RecordingReason.Manual.Source = RecordingReason.Manual.Source.KAROO_FIELD,
    ) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isConnected(cfg.address) && !isRecording(cfg.address)) {
                startCapture(
                    cfg.address,
                    RecordingOwner.MANUAL,
                    reason = RecordingReason.Manual(manualSource),
                )
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
    fun stopAllCameras(
        context: Context,
        manualSource: RecordingReason.Manual.Source = RecordingReason.Manual.Source.KAROO_FIELD,
    ) {
        CameraStore.getCameras(context).forEach { cfg ->
            if (isRecording(cfg.address)) {
                pauseAutomationBriefly(cfg.address)
                stopCapture(cfg.address, reason = RecordingReason.Manual(manualSource))
            }
        }
    }

    /** Stops everything if anything's recording, otherwise starts everything. See [startAllCameras]. */
    fun toggleAllCameras(
        context: Context,
        manualSource: RecordingReason.Manual.Source = RecordingReason.Manual.Source.KAROO_FIELD,
    ) {
        if (isAnyCameraRecording(context)) {
            stopAllCameras(context, manualSource)
        } else {
            startAllCameras(context, manualSource)
        }
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
    fun startCapture(
        address: String,
        owner: RecordingOwner,
        reason: RecordingReason = RecordingReason.Unspecified,
    ) {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "startCapture($address) IGNORED — not connected (reason: ${reason.logText})")
            return
        }
        client.startCapture()
        setRecording(address, true, owner)
        Log.i(TAG, "startCapture($address) SENT — owner=$owner reason=${reason.logText}")
        onGenuineRecordingAction(address, recording = true, reason = reason)
    }

    /** See [startCapture]'s doc comment for what [reason] is and why it's here. */
    fun stopCapture(address: String, reason: RecordingReason = RecordingReason.Unspecified) {
        val client = clients[address]
        if (client == null || !isConnected(address)) {
            Log.w(TAG, "stopCapture($address) IGNORED — not connected (reason: ${reason.logText})")
            return
        }
        client.stopCapture()
        setRecording(address, false)
        Log.i(TAG, "stopCapture($address) SENT — reason=${reason.logText}")
        onGenuineRecordingAction(address, recording = false, reason = reason)
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

    /**
     * **Added (2026-09-07, fix)** — polls every connected camera's capture status on a
     * timer, independently of notifications.
     *
     * Notifications should make this redundant, and when they work it is: a poll that
     * agrees with what we already believe changes nothing and announces nothing (see
     * [applyExternalRecordingState]). But camera-side detection failing silently is
     * exactly the failure this whole feature exists to prevent, and a 10-second poll
     * bounds how long a mismatch can persist even if a notification is missed, malformed,
     * or never sent by this particular model. One small BLE write per camera per ten
     * seconds is a cheap insurance premium against the indicator lying.
     */
    private val statusPoll = object : Runnable {
        override fun run() {
            val connected = clients.keys.filter { isConnected(it) }
            connected.forEach { clients[it]?.queryCaptureStatus() }
            if (connected.isNotEmpty()) {
                handler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
            } else {
                statusPollRunning = false
            }
        }
    }

    @Volatile private var statusPollRunning = false

    private fun ensureStatusPollRunning() {
        if (statusPollRunning) return
        statusPollRunning = true
        handler.postDelayed(statusPoll, STATUS_POLL_INTERVAL_MS)
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
    private fun onGenuineRecordingAction(address: String, recording: Boolean, reason: RecordingReason) {
        notifyRecordingChanged(address, recording)
        listeners.forEach { it.onRecordingChanged(address, recording, reason) }
    }

    /**
     * **Added (2026-09-07)** — the camera told us something changed without us asking.
     *
     * Everything in this app used to assume it was the only thing that could ever start or
     * stop a recording, so a camera started by its own shutter button, or by a paired
     * Insta360 remote, or one that stopped itself on a full card, left every field and
     * every latch in this app believing the opposite of the truth for the rest of the
     * ride. These notifications are the camera's own account of what it is doing, and they
     * take precedence over anything we inferred.
     *
     * Ownership is deliberately left at [RecordingOwner.NONE] for camera-side starts: the
     * automatic monitor in [com.example.karooinsta360.extension.Insta360Extension] only
     * ever stops recordings it owns, so a recording the rider started on the camera itself
     * will not be auto-stopped out from under them by a latch that happens to disagree.
     */
    private fun handleCameraNotification(address: String, code: Int, payload: ByteArray) {
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        when (code) {
            Insta360BleClient.NOTIFY_CURRENT_CAPTURE_STATUS -> {
                Log.i(TAG, "[$address] capture-status notification: $hex")
                handleCaptureStatusPayload(address, payload, source = "notification")
            }

            // The physical shutter button, and the equivalent press relayed from a paired
            // remote. Neither payload says what the camera is now doing, only that
            // something was pressed — so ask, rather than guess by inverting our own
            // possibly-stale belief.
            Insta360BleClient.NOTIFY_KEY_PRESSED,
            Insta360BleClient.NOTIFY_SYNC_CAPTURE_BUTTON_TRIGGER,
            -> {
                Log.i(TAG, "[$address] camera-side button (code=0x${code.toString(16)}): $hex — querying status")
                clients[address]?.queryCaptureStatus()
            }

            Insta360BleClient.NOTIFY_CAPTURE_STOPPED -> {
                Log.i(TAG, "[$address] capture stopped by camera: $hex")
                applyExternalRecordingState(address, recording = false, reason = RecordingReason.CameraSide)
            }

            Insta360BleClient.NOTIFY_STORAGE_FULL ->
                applyExternalRecordingState(
                    address,
                    recording = false,
                    reason = RecordingReason.CameraFault(RecordingReason.CameraFault.Fault.STORAGE_FULL),
                )

            Insta360BleClient.NOTIFY_BATTERY_LOW ->
                Log.w(TAG, "[$address] camera battery low: $hex")

            Insta360BleClient.NOTIFY_SHUTDOWN ->
                applyExternalRecordingState(
                    address,
                    recording = false,
                    reason = RecordingReason.CameraFault(RecordingReason.CameraFault.Fault.SHUTDOWN),
                )

            // Fires mid-recording when the camera rolls over to a new file. It is NOT a
            // stop, and treating it as one would end a perfectly healthy recording every
            // few minutes.
            Insta360BleClient.NOTIFY_CAPTURE_AUTO_SPLIT ->
                Log.i(TAG, "[$address] capture auto-split (still recording): $hex")

            else -> Log.d(TAG, "[$address] Notification code=0x${code.toString(16)} len=${payload.size} raw=$hex")
        }
    }

    /**
     * Best-effort read of a capture-status payload, from either the 0x2010 notification or
     * the response to [Insta360BleClient.CMD_GET_CURRENT_CAPTURE_STATUS].
     *
     * **The schema is still unconfirmed for the Ace Pro 2 and this deliberately fails
     * closed.** insta360ctl parses 0x2010 for *storage* fields on the GO 3 despite the
     * code being named for capture status, so the payload carries several things and the
     * field numbering may differ by model.
     *
     * **(2026-09-07, fix)** The first version of this only accepted a payload beginning
     * with the exact tag byte for "field 1, varint" and bailed on anything else, which
     * meant a payload with any other field first was discarded without ever being looked
     * at. It now walks the whole message with [parseVarintFields] and logs every varint
     * field it finds, so one ride's logcat is enough to identify which field actually
     * carries capture state. Reading field 1 remains the assumption; the log line is what
     * makes that assumption cheap to correct.
     */
    private fun handleCaptureStatusPayload(address: String, payload: ByteArray, source: String) {
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        if (payload.isEmpty()) {
            Log.i(TAG, "[$address] capture status ($source): empty payload, ignoring")
            return
        }

        val fields = parseVarintFields(payload)
        Log.i(TAG, "[$address] capture status ($source): raw=$hex varintFields=$fields")

        // Field 1 is the conventional slot for a state enum, and is what this reads. If
        // the Ace Pro 2 turns out to put capture state somewhere else, the log line above
        // now shows every varint field in the payload, so the fix is a one-line change to
        // the key looked up here rather than another round of guessing.
        val state = fields[1]
        if (state == null) {
            Log.i(TAG, "[$address] capture status ($source): no field 1 — belief unchanged")
            return
        }
        val recording = state != 0L
        Log.i(TAG, "[$address] capture status ($source): field1=$state -> recording=$recording")
        applyExternalRecordingState(address, recording, RecordingReason.CameraSide)
    }

    /**
     * Walks a protobuf payload and returns every varint field it can read, keyed by field
     * number. Length-delimited and fixed-width fields are skipped over rather than
     * decoded — capture state is a varint, and anything else in the message is noise for
     * this purpose.
     *
     * Returns whatever it managed to read before hitting something malformed, so a
     * partially-understood payload still yields its leading fields.
     */
    private fun parseVarintFields(payload: ByteArray): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF
            if (tag == 0) break
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07
            i++
            when (wireType) {
                0 -> {
                    var value = 0L
                    var shift = 0
                    while (i < payload.size) {
                        val b = payload[i].toInt() and 0xFF
                        value = value or ((b and 0x7F).toLong() shl shift)
                        i++
                        if (b and 0x80 == 0) break
                        shift += 7
                        if (shift > 63) return out
                    }
                    out[fieldNumber] = value
                }
                1 -> i += 8
                2 -> {
                    if (i >= payload.size) return out
                    val len = payload[i].toInt() and 0xFF
                    i += 1 + len
                }
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }

    /**
     * Applies camera-reported state, but only when it actually contradicts what we already
     * believe — otherwise every status query would re-announce a recording that has been
     * running happily for twenty minutes.
     */
    private fun applyExternalRecordingState(address: String, recording: Boolean, reason: RecordingReason) {
        if (isRecording(address) == recording) return
        Log.i(TAG, "[$address] external recording state -> $recording (${reason.logText})")
        setRecording(address, recording, RecordingOwner.NONE)
        onGenuineRecordingAction(address, recording, reason)
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
                    // Assume nothing: this used to hard-reset the flag to false, which is
                    // wrong whenever the camera was already rolling before we connected
                    // (its own shutter button, a paired remote, or simply this app
                    // restarting mid-recording). Seed from a real query instead — the
                    // response lands in onCommandResponse below. The false here is only a
                    // placeholder until it does.
                    setRecording(address, false)
                    // clients[address] rather than the local `client`, which isn't
                    // initialised yet from inside its own listener.
                    clients[address]?.queryCaptureStatus()
                    ensureStatusPollRunning()
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
                    if (commandCode == Insta360BleClient.CMD_GET_CURRENT_CAPTURE_STATUS) {
                        handleCaptureStatusPayload(address, payload, source = "status query")
                    }
                }

                override fun onNotification(notificationCode: Int, payload: ByteArray) {
                    handleCameraNotification(address, notificationCode, payload)
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
