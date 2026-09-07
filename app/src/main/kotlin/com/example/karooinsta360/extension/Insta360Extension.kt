package com.example.karooinsta360.extension

import android.content.SharedPreferences
import android.util.Log
import com.example.karooinsta360.AppSettings
import com.example.karooinsta360.R
import com.example.karooinsta360.camera.CameraStore
import com.example.karooinsta360.camera.ProfileStore
import com.example.karooinsta360.connection.Insta360ConnectionManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.StreamState
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
 *  - runs one independent monitor coroutine per camera the active profile currently has
 *    switched on (see [ProfileStore]), each applying that profile's thresholds/durations
 *    for that specific camera, and
 *  - mirrors the fleet's recording state (true if ANY saved camera is recording) into
 *    [RecordingStateDataType], and
 *  - **(2026-08-30)** guards against a data source (heart rate strap, power meter, speed,
 *    radar) that's stopped updating entirely rather than just crossing a threshold — see
 *    [runCameraMonitor]'s "Data source loss safety net" section and
 *    [com.example.karooinsta360.AppSettings.getDataSourceLossTimeoutMinutes] (configured
 *    on the main screen), which otherwise would leave a recording an actual sensor loss
 *    started going forever, since a frozen last-known-value can never cross a stop
 *    threshold on its own.
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
        // Karoo's own settings, same "act on every saved camera" toggle as the ride-page
        // tile.
        private const val BONUS_ACTION_TOGGLE_RECORDING = "toggle_recording"

        // Power's *stop* condition (2026-08-29) evaluates a rolling average of the last
        // this-many one-second ticks of power — a 3-second average at CHECK_INTERVAL_MS's
        // 1-tick-per-second rate — rather than the instantaneous reading, so a single
        // low-power tick can't restart (or wrongly satisfy) the stop countdown on its own.
        // See runCameraMonitor's power-stop handling below and
        // ProfileStore.ProfileCameraSettings.powerStopAllowedSpikes.
        private const val POWER_STOP_AVERAGE_SAMPLES = 3

        // A power spike (the rolling average listed above rising back to/above the stop
        // threshold while a stop countdown is already running) is only ever tolerated for
        // up to this long — matching the 3-second average window itself, on the theory
        // that a spike the average can't "absorb" within its own window is a real, sustained
        // return to effort, not a brief blip.
        private const val POWER_STOP_MAX_SPIKE_MS = 3_000L

        // Karoo's radar DataPoint reports target range in meters, and its speed DataPoint
        // is in m/s — both natural SI units, not a raw sensor scale factor. The Speed and
        // Radar trigger thresholds, though, are entered/displayed per profile-camera-pair
        // in whichever unit that pair picks (mph or km/h; feet or meters — see
        // [CameraStore.SpeedUnit]/[CameraStore.DistanceUnit], added 2026-08-29, now stored
        // on [ProfileStore.ProfileCameraSettings]) rather than a single unit fixed for the
        // whole app, since converting to the Karoo's raw SI reading always means
        // multiplying by that unit's own conversion factor before comparing against
        // [latestSpeed]/[latestRadarNearestTargetMeters] — see runCameraMonitor's speed and
        // radar latches below.

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

    // Timestamp (System.currentTimeMillis()) each metric last saw an actual Streaming
    // state, updated in lockstep with latestHr/latestPower/latestSpeed above but serving
    // a different purpose: those hold the last known VALUE indefinitely through a
    // dropout (see their doc comment above) so a flaky sensor can't reset a running
    // threshold timer; these track how STALE that held-over value actually is, so
    // AppSettings.getDataSourceLossTimeoutMinutes's safety net (see runCameraMonitor's
    // "Data source loss safety net" section) can tell "still fine, just hasn't crossed
    // the threshold yet" apart from "this sensor has been gone for 20 minutes and we're
    // still sitting on its last reading."
    @Volatile private var lastHrStreamingAt: Long? = null
    @Volatile private var lastPowerStreamingAt: Long? = null
    @Volatile private var lastSpeedStreamingAt: Long? = null

    // Same idea, but for the RADAR stream's own connectivity — distinct from
    // latestRadarNearestTargetMeters going null, which means "stream is fine, just no
    // target in range right now" (a legitimate, immediate stop condition already handled
    // in runCameraMonitor), not a data-source problem at all.
    @Volatile private var lastRadarStreamingAt: Long? = null

    private var hrJob: Job? = null
    private var powerJob: Job? = null
    private var speedJob: Job? = null
    private var radarJob: Job? = null
    private val cameraMonitors = ConcurrentHashMap<String, Job>()

    private val connectionListener = object : Insta360ConnectionManager.Listener {
        override fun onCameraStateChanged(address: String) {
            publishAggregateRecordingState()
        }

        override fun onRecordingChanged(address: String, recording: Boolean) {
            raiseRecordingAlert(address, recording)
        }
    }

    // Fires for ANY saved-camera-identity change (add/remove/rename) — trigger
    // configuration itself no longer lives here at all (see CameraStore/ProfileStore's
    // 2026-08-29 doc comments), but resyncing still matters: a removed camera needs its
    // monitor torn down, and a newly-added one needs one started if the active profile
    // already covers its address.
    private val cameraStoreListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            Log.i(TAG, "Camera list changed, resyncing monitors")
            resyncCameraMonitors()
        }

    // Fires for ANY profile-store change — a profile's active-camera selection or trigger
    // settings edited, a profile renamed/deleted, or which profile is active switching —
    // since every one of those can change what runCameraMonitor should be doing for some
    // camera right now.
    private val profileStoreListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            Log.i(TAG, "Profile changed, resyncing monitors")
            resyncCameraMonitors()
        }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Extension service created")
        // Must run before anything below reads ProfileStore — see its doc comment for what
        // this preserves for anyone upgrading from before profiles existed at all.
        CameraStore.migrateLegacyTriggersToProfileIfNeeded(this)
        Insta360ConnectionManager.addListener(connectionListener)
        Insta360ConnectionManager.ensureStarted(this)
        CameraStore.registerChangeListener(this, cameraStoreListener)
        ProfileStore.registerChangeListener(this, profileStoreListener)
        karooSystem.connect { connected ->
            Log.i(TAG, "KarooSystem connected=$connected")
            if (connected) {
                startMetricCollectors()
                resyncCameraMonitors()
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
        ProfileStore.unregisterChangeListener(this, profileStoreListener)
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
    }

    /**
     * Fired from a controller button assigned to this extension's "Toggle Camera
     * Recording" BonusAction in the Karoo's own button-mapping settings (see
     * extension_info.xml) — a manual override alongside the automatic triggers, acting
     * on every saved camera at once for the same reason
     * [com.example.karooinsta360.RecordingToggleReceiver] does (a single button press
     * can't pick out one specific camera).
     */
    override fun onBonusAction(actionId: String) {
        when (actionId) {
            BONUS_ACTION_TOGGLE_RECORDING -> Insta360ConnectionManager.toggleAllCameras(this)
            else -> Log.w(TAG, "Unknown bonus action: $actionId")
        }
    }

    /**
     * **Added (2026-08-29)** alongside (not instead of) the plain Android status-bar
     * notification in [Insta360ConnectionManager.notifyRecordingChanged] — that
     * notification's actual on-device behavior is uncertain: it's reportedly been seen
     * as a heads-up banner but never found afterward in any drawer, and Karoo's own
     * documentation confirms karoo-ext's own `SystemNotification`/Control Center
     * mechanism is deliberately *hidden while riding* ("Karoo OS - System Notifications"
     * support article — this app used to have a Control Center Start/Stop control for
     * exactly this reason; removed 2026-08-29 once that turned out to make it useless
     * in-ride, see the README), so a start/stop alert can't rely on it either. [InRideAlert]
     * is karoo-ext's own native mechanism for exactly this — "critical messaging related
     * to the current ride," per its own doc comment — so unlike either of those, it's
     * guaranteed to actually render on Karoo's screen regardless of what's true about the
     * Android notification drawer or Control Center's ride-hiding behavior. Kept the
     * status-bar notification in place too rather than replacing it — it's still
     * potentially useful after a ride ends (an [InRideAlert] auto-dismisses in seconds;
     * a status-bar notification history persists on a phone, and may or may not on Karoo,
     * but there's no reason not to still try). One alert ID per camera address so two
     * cameras changing state close together don't cut off each other's alert.
     */
    private fun raiseRecordingAlert(address: String, recording: Boolean) {
        val name = CameraStore.getCamera(this, address)?.name ?: address
        karooSystem.dispatch(
            InRideAlert(
                id = "insta360_recording_$address",
                icon = R.drawable.ic_extension,
                title = if (recording) "Recording started" else "Recording stopped",
                detail = name,
                autoDismissMs = 4_000,
                backgroundColor = if (recording) R.color.recording_started_bg else R.color.recording_stopped_bg,
                textColor = R.color.recording_alert_text,
            ),
        )
    }

    private fun startMetricCollectors() {
        hrJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect { state ->
                if (state is StreamState.Streaming) {
                    latestHr = state.dataPoint.singleValue
                    lastHrStreamingAt = System.currentTimeMillis()
                }
            }
        }
        powerJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect { state ->
                if (state is StreamState.Streaming) {
                    latestPower = state.dataPoint.singleValue
                    lastPowerStreamingAt = System.currentTimeMillis()
                }
            }
        }
        speedJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.SPEED).collect { state ->
                if (state is StreamState.Streaming) {
                    latestSpeed = state.dataPoint.singleValue
                    lastSpeedStreamingAt = System.currentTimeMillis()
                }
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
                    lastRadarStreamingAt = System.currentTimeMillis()
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

    /**
     * Starts/stops/restarts one monitor coroutine per camera that's both currently saved
     * AND switched on in the active profile — see [ProfileStore]'s doc comment. A camera
     * that's saved but not part of the active profile (or there being no active profile at
     * all) simply gets no monitor: no trigger configuration exists for it to run, so there
     * would be nothing for one to do anyway.
     */
    private fun resyncCameraMonitors() {
        val cameras = CameraStore.getCameras(this).associateBy { it.address }
        val activeProfile = ProfileStore.getActiveProfileId(this)?.let { ProfileStore.getProfile(this, it) }
        val eligibleAddresses = activeProfile?.activeCameraAddresses.orEmpty().filter { it in cameras.keys }.toSet()

        // Drop monitors for cameras that no longer exist, or are no longer eligible (taken
        // out of the active profile, the active profile changed to one that doesn't cover
        // them, or the active profile was cleared/deleted entirely).
        cameraMonitors.keys.filter { it !in eligibleAddresses }.forEach { address ->
            cameraMonitors.remove(address)?.cancel()
        }

        // Restart every eligible camera's monitor so a settings edit takes effect immediately.
        eligibleAddresses.forEach { address ->
            val camera = cameras.getValue(address)
            val settings = activeProfile?.cameraSettings?.get(address) ?: ProfileStore.ProfileCameraSettings.DEFAULT
            cameraMonitors.remove(address)?.cancel()
            cameraMonitors[address] = runCameraMonitor(camera, settings)
        }
    }

    private fun runCameraMonitor(camera: CameraStore.CameraConfig, settings: ProfileStore.ProfileCameraSettings): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            var hrAboveSince: Long? = null
            var hrBelowSince: Long? = null
            var powerAboveSince: Long? = null
            var powerBelowSince: Long? = null
            var speedAboveSince: Long? = null
            var speedBelowSince: Long? = null
            var radarAboveSince: Long? = null
            var radarBelowSince: Long? = null

            // Power-stop-specific state (2026-08-29) — see POWER_STOP_AVERAGE_SAMPLES/
            // POWER_STOP_MAX_SPIKE_MS above. powerStopSamples holds the last few ticks'
            // readings (oldest first) for the rolling average; powerSpikeActive/
            // powerSpikeStartTime track a single in-progress tolerated spike;
            // powerSpikesUsed counts how many spikes this stop attempt has already used
            // against settings.powerStopAllowedSpikes. All reset together whenever a stop
            // attempt ends, one way or another (see the reset points below).
            val powerStopSamples = ArrayDeque<Double>()
            var powerSpikeActive = false
            var powerSpikeStartTime: Long? = null
            var powerSpikesUsed = 0

            // Three independent latches. "effort" = heart rate OR power (either can flip
            // it either way); "speed" and "radar" never interact with effort, or with each
            // other, in either direction.
            var effortWantsRecording = false
            var speedWantsRecording = false
            var radarWantsRecording = false

            // Human-readable description of whichever latch most recently flipped, and
            // in what direction — e.g. "effort start: heart rate (165bpm ≥ 160bpm for 5s)"
            // or "speed stop: rawSpeed=1.8m/s < 4.0m/s (9.0mph)". Fed into the combine
            // step's own logging below and into startCapture/stopCapture's `reason` param,
            // so a log line reading "already recording — start suppressed" or
            // "IGNORED — not connected" can always be traced back to the specific trigger
            // that caused it, without having to line up timestamps across separate logs.
            var lastLatchEvent = "none yet"

            // Debounces the combine step's own logging below: only emitted when the actual
            // outcome (started / stopped / suppressed-because-X / paused / idle) changes
            // from the previous tick, so a sustained recording doesn't log the same
            // "already recording" line once a second for its entire duration — see the
            // combine step's comment for the full set of outcomes this can take.
            var lastCombineOutcome: String? = null

            while (isActive) {
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val address = camera.address

                // Set below by the "Data source loss safety net" section, once it forces
                // a latch open due to a stale metric rather than an ordinary threshold
                // crossing — non-null here is what the combine step uses to log and tag
                // an automatic stop as data-source-loss-caused rather than the usual
                // "no latch wants recording" reason.
                var dataSourceLossReason: String? = null

                // --- Effort latch: heart rate OR power, "any starts / any drop stops" ---
                if (!effortWantsRecording) {
                    hrBelowSince = null
                    powerBelowSince = null
                    powerStopSamples.clear()
                    powerSpikeActive = false
                    powerSpikeStartTime = null
                    powerSpikesUsed = 0
                    var started = false
                    // Which metric(s) actually caused `started` to flip true this tick —
                    // either can do it alone ("any", not "all"), and both can cross in the
                    // same tick, hence a list rather than a single cause.
                    val startCauses = mutableListOf<String>()

                    if (settings.heartRate.enabled) {
                        val v = latestHr
                        if (v != null && v >= settings.heartRate.startThreshold) {
                            val since = hrAboveSince ?: now.also { hrAboveSince = it }
                            if (now - since >= settings.heartRate.startSeconds * 1000L) {
                                started = true
                                startCauses.add(
                                    "heart rate (${v}bpm ≥ ${settings.heartRate.startThreshold}bpm for ${settings.heartRate.startSeconds}s)",
                                )
                            }
                        } else {
                            hrAboveSince = null
                        }
                    }
                    if (settings.power.enabled) {
                        val v = latestPower
                        if (v != null && v >= settings.power.startThreshold) {
                            val since = powerAboveSince ?: now.also { powerAboveSince = it }
                            if (now - since >= settings.power.startSeconds * 1000L) {
                                started = true
                                startCauses.add(
                                    "power (${v}W ≥ ${settings.power.startThreshold}W for ${settings.power.startSeconds}s)",
                                )
                            }
                        } else {
                            powerAboveSince = null
                        }
                    }
                    if (started) {
                        val detail = "effort start: ${startCauses.joinToString(" and ")}"
                        Log.i(TAG, "[${camera.displayLabel}] $detail — wants recording")
                        effortWantsRecording = true
                        lastLatchEvent = detail
                        hrAboveSince = null
                        powerAboveSince = null
                    }
                } else {
                    hrAboveSince = null
                    powerAboveSince = null
                    var stopped = false
                    // Same "any one metric can cause it, both can cross the same tick"
                    // shape as startCauses above.
                    val stopCauses = mutableListOf<String>()

                    if (settings.heartRate.enabled) {
                        val v = latestHr
                        if (v != null && v < settings.heartRate.stopThreshold) {
                            val since = hrBelowSince ?: now.also { hrBelowSince = it }
                            if (now - since >= settings.heartRate.stopSeconds * 1000L) {
                                stopped = true
                                stopCauses.add(
                                    "heart rate (${v}bpm < ${settings.heartRate.stopThreshold}bpm for ${settings.heartRate.stopSeconds}s)",
                                )
                            }
                        } else {
                            hrBelowSince = null
                        }
                    }
                    // Power's stop side (2026-08-29): compares a rolling average of the
                    // last POWER_STOP_AVERAGE_SAMPLES ticks against stopThreshold, rather
                    // than the instantaneous reading, and tolerates up to
                    // settings.powerStopAllowedSpikes brief returns to/above that threshold
                    // (each capped at POWER_STOP_MAX_SPIKE_MS) without cancelling an
                    // in-progress stop countdown — see the constants' doc comments above.
                    if (settings.power.enabled) {
                        val v = latestPower
                        if (v != null) {
                            powerStopSamples.addLast(v)
                            if (powerStopSamples.size > POWER_STOP_AVERAGE_SAMPLES) powerStopSamples.removeFirst()
                        }
                        val avg = if (powerStopSamples.isEmpty()) null else powerStopSamples.average()

                        if (avg == null) {
                            // No readings yet at all — nothing to evaluate.
                            powerBelowSince = null
                            powerSpikeActive = false
                            powerSpikeStartTime = null
                        } else if (avg < settings.power.stopThreshold) {
                            // Below threshold — start the countdown, or continue one
                            // already running (including one that survived a tolerated
                            // spike, since that spike never touched powerBelowSince).
                            val since = powerBelowSince ?: now.also { powerBelowSince = it }
                            powerSpikeActive = false
                            powerSpikeStartTime = null
                            if (now - since >= settings.power.stopSeconds * 1000L) {
                                stopped = true
                                stopCauses.add(
                                    "power (${POWER_STOP_AVERAGE_SAMPLES}s avg ${"%.1f".format(avg)}W < " +
                                        "${settings.power.stopThreshold}W for ${settings.power.stopSeconds}s, " +
                                        "spikes used ${powerSpikesUsed}/${settings.powerStopAllowedSpikes})",
                                )
                            }
                        } else if (powerBelowSince == null) {
                            // At/above threshold with no countdown running — ordinary
                            // "still working hard" state, nothing to do.
                        } else if (powerSpikeActive) {
                            // Already mid-spike — only a spike that outlasts the tolerance
                            // window counts as a genuine recovery.
                            if (now - (powerSpikeStartTime ?: now) > POWER_STOP_MAX_SPIKE_MS) {
                                Log.i(
                                    TAG,
                                    "[${camera.displayLabel}] power spike ran past " +
                                        "${POWER_STOP_MAX_SPIKE_MS / 1000}s — stop attempt cancelled",
                                )
                                powerBelowSince = null
                                powerSpikeActive = false
                                powerSpikeStartTime = null
                                powerSpikesUsed = 0
                            }
                        } else if (powerSpikesUsed < settings.powerStopAllowedSpikes) {
                            // A new spike above threshold mid-countdown, with an allowance
                            // left for it — tolerate it, leaving powerBelowSince untouched
                            // so the countdown keeps running through it.
                            powerSpikesUsed++
                            powerSpikeActive = true
                            powerSpikeStartTime = now
                            Log.i(
                                TAG,
                                "[${camera.displayLabel}] power spike $powerSpikesUsed/" +
                                    "${settings.powerStopAllowedSpikes} tolerated during stop countdown",
                            )
                        } else {
                            // No spike allowance left — a genuine recovery above threshold.
                            powerBelowSince = null
                        }
                    }
                    if (stopped) {
                        val detail = "effort stop: ${stopCauses.joinToString(" and ")}"
                        Log.i(TAG, "[${camera.displayLabel}] $detail — no longer wants recording")
                        effortWantsRecording = false
                        lastLatchEvent = detail
                        hrBelowSince = null
                        powerBelowSince = null
                        powerStopSamples.clear()
                        powerSpikeActive = false
                        powerSpikeStartTime = null
                        powerSpikesUsed = 0
                    }
                }

                // --- Speed latch: fully independent of heart rate/power ---
                // settings.speed.startThreshold/stopThreshold are entered in settings.speedUnit
                // (mph or km/h — chosen per camera); latestSpeed is m/s straight from the
                // Karoo — convert before comparing.
                if (!speedWantsRecording) {
                    speedBelowSince = null
                    if (settings.speed.enabled) {
                        val v = latestSpeed
                        val startThresholdMps = settings.speed.startThreshold * settings.speedUnit.metersPerSecondPerUnit
                        if (v != null && v >= startThresholdMps) {
                            val since = speedAboveSince ?: now.also { speedAboveSince = it }
                            if (now - since >= settings.speed.startSeconds * 1000L) {
                                // Logs the raw Karoo reading alongside the converted threshold
                                // specifically so a "fired at the wrong speed" report can be
                                // checked against logcat: compare rawSpeed to your actual known
                                // speed at that moment to confirm what unit the Karoo is really
                                // reporting SPEED in (this code assumes m/s, per ANT+/FIT
                                // convention and karoo-ext's own docs implying unit conversion
                                // only happens in display formatting, not in the raw stream).
                                val detail = "speed start: rawSpeed=${v}m/s ≥ ${startThresholdMps}m/s " +
                                    "(${settings.speed.startThreshold}${settings.speedUnit.label})"
                                Log.i(TAG, "[${camera.displayLabel}] $detail — wants recording")
                                speedWantsRecording = true
                                lastLatchEvent = detail
                                speedAboveSince = null
                            }
                        } else {
                            speedAboveSince = null
                        }
                    }
                } else {
                    speedAboveSince = null
                    if (settings.speed.enabled) {
                        val v = latestSpeed
                        val stopThresholdMps = settings.speed.stopThreshold * settings.speedUnit.metersPerSecondPerUnit
                        if (v != null && v < stopThresholdMps) {
                            val since = speedBelowSince ?: now.also { speedBelowSince = it }
                            if (now - since >= settings.speed.stopSeconds * 1000L) {
                                val detail = "speed stop: rawSpeed=${v}m/s < ${stopThresholdMps}m/s " +
                                    "(${settings.speed.stopThreshold}${settings.speedUnit.label})"
                                Log.i(TAG, "[${camera.displayLabel}] $detail — no longer wants recording")
                                speedWantsRecording = false
                                lastLatchEvent = detail
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
                    if (settings.radar.enabled) {
                        val v = latestRadarNearestTargetMeters
                        val thresholdMeters = settings.radar.startThreshold * settings.radarUnit.metersPerUnit
                        if (v != null && v <= thresholdMeters) {
                            val since = radarAboveSince ?: now.also { radarAboveSince = it }
                            if (now - since >= settings.radar.startSeconds * 1000L) {
                                val detail = "radar start: vehicle at ${"%.1f".format(v / settings.radarUnit.metersPerUnit)}" +
                                    "${settings.radarUnit.label} (within ${settings.radar.startThreshold}${settings.radarUnit.label} " +
                                    "for ${settings.radar.startSeconds}s)"
                                Log.i(TAG, "[${camera.displayLabel}] $detail — wants recording")
                                radarWantsRecording = true
                                lastLatchEvent = detail
                                radarAboveSince = null
                            }
                        } else {
                            radarAboveSince = null
                        }
                    }
                } else {
                    radarAboveSince = null
                    if (settings.radar.enabled) {
                        val v = latestRadarNearestTargetMeters
                        if (v == null) {
                            val since = radarBelowSince ?: now.also { radarBelowSince = it }
                            if (now - since >= settings.radar.stopSeconds * 1000L) {
                                val detail = "radar stop: no vehicle detected for ${settings.radar.stopSeconds}s"
                                Log.i(TAG, "[${camera.displayLabel}] $detail — no longer wants recording")
                                radarWantsRecording = false
                                lastLatchEvent = detail
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

                // --- Data source loss safety net ---
                //
                // Independent of everything above: the start/stop logic for each latch
                // only ever runs when its metric IS producing readings (a mere dropout
                // holds the last known value in place on purpose — see latestHr's doc
                // comment). That means a data source that's gone for good — a strap left
                // at home, a dead sensor, a camera's radar losing its own connection —
                // never crosses a stop threshold either, since the frozen last reading
                // just sits there forever. This asks a different question: not "what was
                // the metric last showing" but "how long has it actually been since we
                // heard from it at all," and forces a latch open due to THAT, one latch at
                // a time, so a latch still getting live data (e.g. speed, while heart
                // rate's strap died) is unaffected and can keep the recording going on its
                // own. AppSettings.getDataSourceLossTimeoutMinutes==0 disables this
                // entirely, restoring the original "hold forever" behavior.
                val lossTimeoutMs = AppSettings.getDataSourceLossTimeoutMinutes(this@Insta360Extension)
                    .takeIf { it > 0 }
                    ?.let { it * 60_000L }

                fun isStale(lastStreamingAt: Long?): Boolean =
                    lossTimeoutMs != null && (lastStreamingAt == null || now - lastStreamingAt >= lossTimeoutMs)

                // null = this metric isn't enabled for this camera at all, so it shouldn't
                // count for or against "effort" having lost its data source either way —
                // only the metrics actually in play matter, same as the OR logic the
                // ordinary effort start/stop side already uses above.
                val hrLost = settings.heartRate.enabled.takeIf { it }?.let { isStale(lastHrStreamingAt) }
                val powerLost = settings.power.enabled.takeIf { it }?.let { isStale(lastPowerStreamingAt) }
                val effortInputs = listOfNotNull(hrLost, powerLost)
                // "Lost" only once EVERY enabled effort metric has independently gone
                // stale — one still-live metric is enough to keep deciding effort from.
                if (effortWantsRecording && effortInputs.isNotEmpty() && effortInputs.all { it }) {
                    val staleNames = buildList {
                        if (hrLost == true) add("heart rate")
                        if (powerLost == true) add("power")
                    }.joinToString(" and ")
                    val minutes = lossTimeoutMs!! / 60_000L
                    val detail = "effort data source lost: no $staleNames reading for ≥${minutes}min"
                    Log.w(TAG, "[${camera.displayLabel}] $detail — releasing effort latch")
                    effortWantsRecording = false
                    lastLatchEvent = detail
                    dataSourceLossReason = detail
                    hrAboveSince = null
                    hrBelowSince = null
                    powerAboveSince = null
                    powerBelowSince = null
                    powerStopSamples.clear()
                    powerSpikeActive = false
                    powerSpikeStartTime = null
                    powerSpikesUsed = 0
                }

                if (speedWantsRecording && settings.speed.enabled && isStale(lastSpeedStreamingAt)) {
                    val minutes = lossTimeoutMs!! / 60_000L
                    val detail = "speed data source lost: no speed reading for ≥${minutes}min"
                    Log.w(TAG, "[${camera.displayLabel}] $detail — releasing speed latch")
                    speedWantsRecording = false
                    lastLatchEvent = detail
                    dataSourceLossReason = detail
                    speedAboveSince = null
                    speedBelowSince = null
                }

                if (radarWantsRecording && settings.radar.enabled && isStale(lastRadarStreamingAt)) {
                    val minutes = lossTimeoutMs!! / 60_000L
                    val detail = "radar data source lost: no radar stream update for ≥${minutes}min"
                    Log.w(TAG, "[${camera.displayLabel}] $detail — releasing radar latch")
                    radarWantsRecording = false
                    lastLatchEvent = detail
                    dataSourceLossReason = detail
                    radarAboveSince = null
                    radarBelowSince = null
                }

                // --- Combine: recording whenever ANY latch wants it ---
                //
                // Every possible outcome here is logged — including every way an action
                // can be a deliberate no-op — via `lastCombineOutcome` below. That variable
                // is edge-triggered (compared against the previous tick's outcome) purely
                // to avoid logging the exact same line once a second for the entire
                // duration of a long steady-state recording; every genuine *change* in
                // outcome — a fresh suppression, a fresh action, paused/unpaused, a switch
                // from one suppression reason to another — always gets its own line.
                val desired = effortWantsRecording || speedWantsRecording || radarWantsRecording
                val actual = Insta360ConnectionManager.isRecording(address)
                val owner = Insta360ConnectionManager.recordingOwner(address)
                val paused = Insta360ConnectionManager.isAutomationPaused(address)
                val wantingLatches = buildList {
                    if (effortWantsRecording) add("effort")
                    if (speedWantsRecording) add("speed")
                    if (radarWantsRecording) add("radar")
                }.let { if (it.isEmpty()) "none" else it.joinToString("+") }
                val label = camera.displayLabel

                val outcome: String
                // Latches above still update every tick even while paused, so state is
                // caught up and won't immediately fire a stale action the moment
                // CameraConfigActivity's manual test screen closes and hands control back.
                if (paused) {
                    outcome = "paused|desired=$desired|actual=$actual"
                    if (outcome != lastCombineOutcome) {
                        Log.i(
                            TAG,
                            "[$label] wanting=$wantingLatches (last: $lastLatchEvent) actual=$actual — " +
                                "automation paused for this camera, no action taken",
                        )
                    }
                } else if (desired && !actual) {
                    outcome = "start:$wantingLatches"
                    if (outcome != lastCombineOutcome) {
                        Log.i(TAG, "[$label] START triggered by $wantingLatches ($lastLatchEvent) — sending start command")
                    }
                    Insta360ConnectionManager.startCapture(
                        address,
                        Insta360ConnectionManager.RecordingOwner.AUTOMATIC,
                        reason = "$wantingLatches ($lastLatchEvent)",
                    )
                } else if (desired && actual) {
                    outcome = "start-suppressed|owner=$owner"
                    if (outcome != lastCombineOutcome) {
                        Log.i(
                            TAG,
                            "[$label] $wantingLatches wants recording ($lastLatchEvent) but camera is ALREADY " +
                                "RECORDING (owner=$owner) — start IGNORED, no action taken",
                        )
                    }
                } else if (!desired && actual && owner == Insta360ConnectionManager.RecordingOwner.AUTOMATIC) {
                    // Only ever stop a recording this monitor itself started. If it's
                    // recording and we didn't start it — a manual test button, the
                    // ride-page tile, the controller-button BonusAction, or even the
                    // camera's own physical shutter button (which shows up as owner
                    // NONE, since we never saw it start) — it's not this monitor's
                    // call to stop, no matter what the latches above compute (see the
                    // next branch below for that case, logged rather than silent).
                    //
                    // dataSourceLossReason (set above, in the "Data source loss safety
                    // net" section) distinguishes this from an ordinary threshold-based
                    // stop with its own outcome tag and a Log.w rather than Log.i, so
                    // "why did this stop" is answerable straight from logcat — per
                    // explicit request that a data-source-loss stop be logged as such,
                    // not folded silently into the generic "no latch wants recording" line.
                    val lossReason = dataSourceLossReason
                    outcome = if (lossReason != null) "stop:data_source_lost" else "stop:auto"
                    if (outcome != lastCombineOutcome) {
                        if (lossReason != null) {
                            Log.w(TAG, "[$label] STOP — DATA SOURCE LOST ($lossReason) — sending stop command")
                        } else {
                            Log.i(TAG, "[$label] STOP — no latch wants recording ($lastLatchEvent) — sending stop command")
                        }
                    }
                    val stopReason = lossReason?.let { "data source lost — $it" } ?: "no latch wants recording ($lastLatchEvent)"
                    Insta360ConnectionManager.stopCapture(address, reason = stopReason)
                } else if (!desired && actual) {
                    outcome = "stop-suppressed|owner=$owner"
                    if (outcome != lastCombineOutcome) {
                        Log.i(
                            TAG,
                            "[$label] no latch wants recording ($lastLatchEvent), but camera is recording under " +
                                "owner=$owner (not this automation) — automatic stop IGNORED, no action taken",
                        )
                    }
                } else {
                    outcome = "idle"
                    if (outcome != lastCombineOutcome) {
                        Log.i(TAG, "[$label] idle — no latch wants recording, camera not recording")
                    }
                }
                lastCombineOutcome = outcome
            }
        }
    }
}
