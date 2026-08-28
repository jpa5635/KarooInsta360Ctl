package com.example.karooinsta360.extension

import android.content.SharedPreferences
import android.util.Log
import com.example.karooinsta360.AppSettings
import com.example.karooinsta360.RecordingActions
import com.example.karooinsta360.camera.CameraStore
import com.example.karooinsta360.connection.Insta360ConnectionManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Karoo extension side of the auto-record feature.
 *
 * The BLE connections themselves live in [Insta360ConnectionManager] (shared with the
 * app's UI). This class:
 *  - subscribes ONCE each to the Karoo's heart rate, power, speed, and radar streams
 *    (shared across however many cameras are configured, rather than one subscription
 *    per camera per metric),
 *  - runs one independent monitor coroutine per saved camera (see [CameraStore]), each
 *    applying that camera's own thresholds/durations, and
 *  - mirrors the fleet's recording state (true if ANY saved camera is recording) into
 *    [RecordingStateDataType].
 *
 * Per-camera trigger logic (see [runCameraMonitor]): heart rate and power are combined
 * into one "effort" latch — either one, independently, can start OR stop recording (if
 * either is enabled and sustains past its own threshold/duration, that's enough). Speed
 * and radar are each their own **separate, independent** latch — neither factors into
 * the heart-rate/power decision, into each other, or vice versa. The camera actually
 * records whenever *any* latch currently wants it to (e.g. a fast descent can start a
 * recording via speed alone even if heart rate is low, a car approaching from behind can
 * start one even at a dead stop, and effort alone can start one even at walking speed) —
 * a command is only sent to the camera when that combined "should be recording" value
 * actually flips.
 */
class Insta360Extension : KarooExtension(EXTENSION_ID, "1.0") {

    companion object {
        private const val TAG = "Insta360Extension"
        const val EXTENSION_ID = "insta360cam"
        private const val CHECK_INTERVAL_MS = 1_000L

        // Matches the actionId="toggle_recording" BonusAction declared in
        // extension_info.xml — assignable to a physical controller button in the
        // Karoo's own settings, same "act on every saved camera" toggle as the Control
        // Center notification and the ride-page tile.
        private const val BONUS_ACTION_TOGGLE_RECORDING = "toggle_recording"

        // Fixed ID so re-dispatching SystemNotification updates the same Control Center
        // entry in place (per its own doc comment) rather than piling up a new one every
        // time recording state changes.
        private const val CONTROL_CENTER_NOTIFICATION_ID = "insta360_recording_control"

        // Karoo's radar DataPoint reports target range in meters (same convention as
        // Speed being m/s — natural SI units, not a raw sensor scale factor); the trigger
        // threshold is entered in feet since that's how "how close is too close" is
        // normally described for road traffic.
        private const val METERS_PER_FOOT = 0.3048

        // Up to 8 simultaneously-tracked targets; a target's field is simply absent from
        // the DataPoint when nothing occupies that slot, rather than present with some
        // sentinel value — see radarJob below for how that absence is used.
        private val RADAR_TARGET_RANGE_FIELDS = listOf(
            DataType.Field.RADAR_TARGET_1_RANGE,
            DataType.Field.RADAR_TARGET_2_RANGE,
            DataType.Field.RADAR_TARGET_3_RANGE,
            DataType.Field.RADAR_TARGET_4_RANGE,
            DataType.Field.RADAR_TARGET_5_RANGE,
            DataType.Field.RADAR_TARGET_6_RANGE,
            DataType.Field.RADAR_TARGET_7_RANGE,
            DataType.Field.RADAR_TARGET_8_RANGE,
        )
    }

    private val recordingDataType by lazy { RecordingStateDataType(extension) }
    private val recordingControlDataType by lazy { RecordingControlDataType(extension) }
    override val types by lazy { listOf(recordingDataType, recordingControlDataType) }

    private val karooSystem by lazy { KarooSystemService(this) }

    // Latest known values, shared across all per-camera monitors. Only updated on an
    // actual Streaming state — a momentary sensor dropout (Idle/Searching/NotAvailable)
    // leaves the last known value in place rather than resetting it, so a flaky strap or
    // power meter can't interrupt a sustained-threshold timer on its own.
    @Volatile private var latestHr: Double? = null
    @Volatile private var latestPower: Double? = null
    @Volatile private var latestSpeed: Double? = null

    // Distance (meters) to the nearest currently-tracked radar target, or null when the
    // radar is reporting no target at all. Unlike the three fields above this is NOT
    // "last known value held during a dropout" — null here means "no car," which is
    // exactly the signal the stop side of the radar latch below needs.
    @Volatile private var latestRadarNearestTargetMeters: Double? = null

    private var hrJob: Job? = null
    private var powerJob: Job? = null
    private var speedJob: Job? = null
    private var radarJob: Job? = null
    private val cameraMonitors = ConcurrentHashMap<String, Job>()

    private val connectionListener = object : Insta360ConnectionManager.Listener {
        override fun onCameraStateChanged(address: String) {
            publishAggregateRecordingState()
        }
    }

    private val cameraStoreListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            Log.i(TAG, "Camera list/config changed, resyncing monitors")
            resyncCameraMonitors()
            updateControlCenterNotification()
        }

    private val appSettingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateControlCenterNotification() }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Extension service created")
        Insta360ConnectionManager.addListener(connectionListener)
        Insta360ConnectionManager.ensureStarted(this)
        CameraStore.registerChangeListener(this, cameraStoreListener)
        AppSettings.registerChangeListener(this, appSettingsListener)
        karooSystem.connect { connected ->
            Log.i(TAG, "KarooSystem connected=$connected")
            if (connected) {
                startMetricCollectors()
                resyncCameraMonitors()
                updateControlCenterNotification()
            } else {
                stopMetricCollectors()
                cameraMonitors.values.forEach { it.cancel() }
                cameraMonitors.clear()
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Extension service destroyed")
        CameraStore.unregisterChangeListener(this, cameraStoreListener)
        AppSettings.unregisterChangeListener(this, appSettingsListener)
        Insta360ConnectionManager.removeListener(connectionListener)
        stopMetricCollectors()
        cameraMonitors.values.forEach { it.cancel() }
        cameraMonitors.clear()
        karooSystem.disconnect()
        super.onDestroy()
        // Deliberately NOT tearing down Insta360ConnectionManager's BLE connections here
        // — they're shared with the app's UI, and the Karoo may just be temporarily
        // unbinding this service rather than the whole app being killed.
    }

    private fun publishAggregateRecordingState() {
        val anyRecording = Insta360ConnectionManager.getCameraStates(this).any { it.recording }
        recordingDataType.publish(anyRecording)
        updateControlCenterNotification(anyRecording)
    }

    /**
     * Fired from a controller button assigned to this extension's "Toggle Camera
     * Recording" BonusAction in the Karoo's own button-mapping settings (see
     * extension_info.xml) — a manual override alongside the automatic triggers, acting
     * on every saved camera at once for the same reason [updateControlCenterNotification]
     * and [com.example.karooinsta360.RecordingToggleReceiver] do (a single button press
     * can't pick out one specific camera).
     */
    override fun onBonusAction(actionId: String) {
        when (actionId) {
            BONUS_ACTION_TOGGLE_RECORDING -> Insta360ConnectionManager.toggleAllCameras(this)
            else -> Log.w(TAG, "Unknown bonus action: $actionId")
        }
    }

    /**
     * Keeps a Start/Stop control visible in the Karoo's own Control Center via
     * [SystemNotification] — a genuinely native "add a control to Control Center" hook
     * (unlike a regular Android notification, karoo-ext dispatches this straight into the
     * Karoo system UI). Re-dispatching with the same [CONTROL_CENTER_NOTIFICATION_ID]
     * updates the existing entry in place rather than adding a new one each time.
     *
     * The notification's `actionIntent` can only name an intent action to launch an
     * activity with (no extras, no direct callback into this extension) — see
     * [com.example.karooinsta360.ControlCenterActionActivity], which exists purely to
     * receive that click and perform the real start/stop.
     *
     * Gated on [AppSettings.isControlCenterControlEnabled] (off by default — unlike the
     * momentary start/stop notification, this one sits in Control Center continuously)
     * and skipped entirely if no camera is saved yet, since there'd be nothing for it to
     * control. Note: karoo-ext has no "remove a SystemNotification" effect, so turning
     * this setting off stops it from being *updated* but won't retract one already
     * showing — the user has to dismiss it themselves, same as any other Control Center
     * notification.
     */
    private fun updateControlCenterNotification(recording: Boolean = Insta360ConnectionManager.isAnyCameraRecording(this)) {
        if (!AppSettings.isControlCenterControlEnabled(this)) return
        if (CameraStore.getCameras(this).isEmpty()) return

        karooSystem.dispatch(
            SystemNotification(
                id = CONTROL_CENTER_NOTIFICATION_ID,
                message = if (recording) "Recording — tap Stop to end it" else "Camera idle — tap Start to begin recording",
                action = if (recording) "Stop Recording" else "Start Recording",
                actionIntent = if (recording) RecordingActions.ACTION_STOP_ALL else RecordingActions.ACTION_START_ALL,
            ),
        )
    }

    private fun startMetricCollectors() {
        hrJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect { state ->
                if (state is StreamState.Streaming) latestHr = state.dataPoint.singleValue
            }
        }
        powerJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect { state ->
                if (state is StreamState.Streaming) latestPower = state.dataPoint.singleValue
            }
        }
        speedJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.SPEED).collect { state ->
                if (state is StreamState.Streaming) latestSpeed = state.dataPoint.singleValue
            }
        }
        radarJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.RADAR).collect { state ->
                // Radar is multi-field (threat level plus up to 8 per-target ranges), so
                // unlike the other three metrics above this can't use .singleValue — that
                // just returns "whichever field happens to be first in the map," which
                // isn't necessarily a range at all. Take the minimum of whichever target-
                // range fields are actually present; minOrNull() on an empty list (every
                // target slot absent — nothing detected) is null, which is exactly what
                // "no car" needs to look like below.
                if (state is StreamState.Streaming) {
                    latestRadarNearestTargetMeters = RADAR_TARGET_RANGE_FIELDS
                        .mapNotNull { field -> state.dataPoint.values[field] }
                        .minOrNull()
                }
            }
        }
    }

    private fun stopMetricCollectors() {
        hrJob?.cancel(); hrJob = null
        powerJob?.cancel(); powerJob = null
        speedJob?.cancel(); speedJob = null
        radarJob?.cancel(); radarJob = null
    }

    /** Starts/stops/restarts one monitor coroutine per currently-saved camera. */
    private fun resyncCameraMonitors() {
        val configs = CameraStore.getCameras(this).associateBy { it.address }

        // Drop monitors for cameras that no longer exist.
        cameraMonitors.keys.filter { it !in configs.keys }.forEach { address ->
            cameraMonitors.remove(address)?.cancel()
        }

        // Restart every current camera's monitor so a config edit takes effect immediately.
        configs.values.forEach { config ->
            cameraMonitors.remove(config.address)?.cancel()
            cameraMonitors[config.address] = runCameraMonitor(config)
        }
    }

    private fun runCameraMonitor(config: CameraStore.CameraConfig): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            var hrAboveSince: Long? = null
            var hrBelowSince: Long? = null
            var powerAboveSince: Long? = null
            var powerBelowSince: Long? = null
            var speedAboveSince: Long? = null
            var speedBelowSince: Long? = null
            var radarAboveSince: Long? = null
            var radarBelowSince: Long? = null

            // Three independent latches. "effort" = heart rate OR power (either can flip
            // it either way); "speed" and "radar" never interact with effort, or with each
            // other, in either direction.
            var effortWantsRecording = false
            var speedWantsRecording = false
            var radarWantsRecording = false

            while (isActive) {
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val address = config.address

                // --- Effort latch: heart rate OR power, "any starts / any drop stops" ---
                if (!effortWantsRecording) {
                    hrBelowSince = null
                    powerBelowSince = null
                    var started = false

                    if (config.heartRate.enabled) {
                        val v = latestHr
                        if (v != null && v >= config.heartRate.startThreshold) {
                            val since = hrAboveSince ?: now.also { hrAboveSince = it }
                            if (now - since >= config.heartRate.startSeconds * 1000L) started = true
                        } else {
                            hrAboveSince = null
                        }
                    }
                    if (config.power.enabled) {
                        val v = latestPower
                        if (v != null && v >= config.power.startThreshold) {
                            val since = powerAboveSince ?: now.also { powerAboveSince = it }
                            if (now - since >= config.power.startSeconds * 1000L) started = true
                        } else {
                            powerAboveSince = null
                        }
                    }
                    if (started) {
                        Log.i(TAG, "[${config.displayLabel}] effort threshold sustained — wants recording")
                        effortWantsRecording = true
                        hrAboveSince = null
                        powerAboveSince = null
                    }
                } else {
                    hrAboveSince = null
                    powerAboveSince = null
                    var stopped = false

                    if (config.heartRate.enabled) {
                        val v = latestHr
                        if (v != null && v < config.heartRate.stopThreshold) {
                            val since = hrBelowSince ?: now.also { hrBelowSince = it }
                            if (now - since >= config.heartRate.stopSeconds * 1000L) stopped = true
                        } else {
                            hrBelowSince = null
                        }
                    }
                    if (config.power.enabled) {
                        val v = latestPower
                        if (v != null && v < config.power.stopThreshold) {
                            val since = powerBelowSince ?: now.also { powerBelowSince = it }
                            if (now - since >= config.power.stopSeconds * 1000L) stopped = true
                        } else {
                            powerBelowSince = null
                        }
                    }
                    if (stopped) {
                        Log.i(TAG, "[${config.displayLabel}] effort dropped — no longer wants recording")
                        effortWantsRecording = false
                        hrBelowSince = null
                        powerBelowSince = null
                    }
                }

                // --- Speed latch: fully independent of heart rate/power ---
                if (!speedWantsRecording) {
                    speedBelowSince = null
                    if (config.speed.enabled) {
                        val v = latestSpeed
                        if (v != null && v >= config.speed.startThreshold) {
                            val since = speedAboveSince ?: now.also { speedAboveSince = it }
                            if (now - since >= config.speed.startSeconds * 1000L) {
                                Log.i(TAG, "[${config.displayLabel}] speed threshold sustained — wants recording")
                                speedWantsRecording = true
                                speedAboveSince = null
                            }
                        } else {
                            speedAboveSince = null
                        }
                    }
                } else {
                    speedAboveSince = null
                    if (config.speed.enabled) {
                        val v = latestSpeed
                        if (v != null && v < config.speed.stopThreshold) {
                            val since = speedBelowSince ?: now.also { speedBelowSince = it }
                            if (now - since >= config.speed.stopSeconds * 1000L) {
                                Log.i(TAG, "[${config.displayLabel}] speed dropped — no longer wants recording")
                                speedWantsRecording = false
                                speedBelowSince = null
                            }
                        } else {
                            speedBelowSince = null
                        }
                    } else {
                        // Speed got disabled while its latch was holding a recording open —
                        // release it so effort's latch is the sole decider again.
                        speedWantsRecording = false
                    }
                }

                // --- Radar latch: fully independent of heart rate/power AND speed. Its own
                // "enabled" checkbox turns car detection on/off with no effect on whether
                // the other three triggers are active, and vice versa.
                //
                // Unlike the other three latches, start and stop are NOT the same
                // threshold crossed in opposite directions:
                //  - START: the nearest tracked target is within the configured distance
                //    (e.g. 100 ft) — a real, close threat.
                //  - STOP: not "target moved back out past 100 ft" (a car that's just
                //    passed you and is now pulling away is still worth keeping in the
                //    recording) but "the radar has reported NO target at all" — sustained
                //    for the configured grace period, so a brief gap between cars in a
                //    stream of traffic doesn't chop one recording into several.
                if (!radarWantsRecording) {
                    radarBelowSince = null
                    if (config.radar.enabled) {
                        val v = latestRadarNearestTargetMeters
                        val thresholdMeters = config.radar.startThreshold * METERS_PER_FOOT
                        if (v != null && v <= thresholdMeters) {
                            val since = radarAboveSince ?: now.also { radarAboveSince = it }
                            if (now - since >= config.radar.startSeconds * 1000L) {
                                Log.i(TAG, "[${config.displayLabel}] vehicle within ${config.radar.startThreshold}ft — wants recording")
                                radarWantsRecording = true
                                radarAboveSince = null
                            }
                        } else {
                            radarAboveSince = null
                        }
                    }
                } else {
                    radarAboveSince = null
                    if (config.radar.enabled) {
                        val v = latestRadarNearestTargetMeters
                        if (v == null) {
                            val since = radarBelowSince ?: now.also { radarBelowSince = it }
                            if (now - since >= config.radar.stopSeconds * 1000L) {
                                Log.i(TAG, "[${config.displayLabel}] no vehicle detected for ${config.radar.stopSeconds}s — no longer wants recording")
                                radarWantsRecording = false
                                radarBelowSince = null
                            }
                        } else {
                            // A vehicle is still on radar (at any distance) — keep resetting
                            // the grace timer so recording continues uninterrupted.
                            radarBelowSince = null
                        }
                    } else {
                        // Radar got disabled while its latch was holding a recording open —
                        // release it so the other latches are the sole deciders again.
                        radarWantsRecording = false
                    }
                }

                // --- Combine: recording whenever ANY latch wants it ---
                val desired = effortWantsRecording || speedWantsRecording || radarWantsRecording
                val actual = Insta360ConnectionManager.isRecording(address)
                // Latches above still update every tick even while paused, so state is
                // caught up and won't immediately fire a stale action the moment
                // CameraConfigActivity's manual test screen closes and hands control back.
                if (!Insta360ConnectionManager.isAutomationPaused(address)) {
                    if (desired && !actual) {
                        Insta360ConnectionManager.startCapture(address, Insta360ConnectionManager.RecordingOwner.AUTOMATIC)
                    } else if (!desired && actual &&
                        Insta360ConnectionManager.recordingOwner(address) == Insta360ConnectionManager.RecordingOwner.AUTOMATIC
                    ) {
                        // Only ever stop a recording this monitor itself started. If it's
                        // recording and we didn't start it — a manual test button, the
                        // Control Center notification, the ride-page tile, the
                        // controller-button BonusAction, or even the camera's own physical
                        // shutter button (which shows up as owner NONE, since we never saw
                        // it start) — it's not this monitor's call to stop, no matter what
                        // the latches above compute.
                        Insta360ConnectionManager.stopCapture(address)
                    }
                }
            }
        }
    }
}
