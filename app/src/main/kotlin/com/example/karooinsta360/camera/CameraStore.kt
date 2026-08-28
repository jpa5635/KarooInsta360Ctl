package com.example.karooinsta360.camera

import android.content.Context
import android.content.SharedPreferences
import io.hammerhead.karooext.models.DataType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted list of saved cameras, each with its own independent per-metric trigger
 * configuration (heart rate / power / speed / radar — each can be independently enabled,
 * with its own threshold and start/stop sustain durations, with no effect on whether any
 * of the others are enabled).
 *
 * Stored as a small JSON blob in SharedPreferences (hand-rolled with [org.json], which
 * ships with Android — no extra dependency, and simpler than wiring up the Kotlin
 * serialization compiler plugin for a config this small). Read from
 * [com.example.karooinsta360.extension.Insta360Extension] (drives the auto-trigger) and
 * from [MainActivity]/[CameraConfigActivity] (list + edit UI); both processes... well,
 * same process, same as [com.example.karooinsta360.connection.Insta360ConnectionManager]
 * — see its doc comment for why that matters here too.
 */
object CameraStore {
    private const val PREFS_NAME = "insta360_cameras"
    private const val KEY_CAMERAS = "cameras_json"

    enum class Metric(val label: String, val unit: String, val dataTypeId: String) {
        HEART_RATE("Heart Rate", "bpm", DataType.Type.HEART_RATE),
        POWER("Power", "W", DataType.Type.POWER),
        SPEED("Speed", "m/s", DataType.Type.SPEED),
        RADAR("Radar (vehicle approaching)", "ft", DataType.Type.RADAR),
    }

    data class MetricTrigger(
        val enabled: Boolean = false,
        // Separate start/stop values on purpose (2026-08-27) — e.g. start recording once
        // power reaches 300W, but only stop once it's fallen under some other number
        // entirely, rather than being forced to use the same 300 for both. For Heart
        // Rate/Power/Speed: start fires once the live value rises to/above
        // [startThreshold] (sustained [startSeconds]); stop fires once it falls below
        // [stopThreshold] (sustained [stopSeconds]). For Radar, [startThreshold] is the
        // trigger distance in feet and [stopThreshold] is unused — see
        // [CameraConfig.radar] and [com.example.karooinsta360.extension.Insta360Extension]
        // for why its stop side isn't a threshold at all.
        val startThreshold: Float = 0f,
        val stopThreshold: Float = 0f,
        val startSeconds: Int = 5,
        val stopSeconds: Int = 30,
    )

    data class CameraConfig(
        val address: String,
        val name: String,
        val heartRate: MetricTrigger = MetricTrigger(),
        val power: MetricTrigger = MetricTrigger(),
        val speed: MetricTrigger = MetricTrigger(),
        // Radar is an event, not a gradually-changing reading like the other three — a
        // car is either behind you or it isn't, and it isn't behind you for long. Threshold
        // is a distance in FEET to the nearest tracked target (100 ft default — start
        // recording once something's actually close, not merely somewhere in the radar's
        // full detection range). Defaults otherwise reflect the same "it's a brief event"
        // reasoning: start immediately (no sustained-duration debounce — by the time you
        // waited a few seconds the car could already be past you), and a 15s stop grace
        // period so a brief gap between cars in a stream of traffic doesn't chop one
        // recording into several (see runCameraMonitor's radar latch for how the stop
        // side works — it's "no vehicle on radar at all," not "moved back past 100 ft").
        val radar: MetricTrigger = MetricTrigger(startThreshold = 100f, stopThreshold = 100f, startSeconds = 0, stopSeconds = 15),
    ) {
        fun trigger(metric: Metric): MetricTrigger = when (metric) {
            Metric.HEART_RATE -> heartRate
            Metric.POWER -> power
            Metric.SPEED -> speed
            Metric.RADAR -> radar
        }

        fun withTrigger(metric: Metric, trigger: MetricTrigger): CameraConfig = when (metric) {
            Metric.HEART_RATE -> copy(heartRate = trigger)
            Metric.POWER -> copy(power = trigger)
            Metric.SPEED -> copy(speed = trigger)
            Metric.RADAR -> copy(radar = trigger)
        }

        /** Short "Name (AA:BB:CC:DD:EE:FF)" label used throughout the UI and logs. */
        val displayLabel: String get() = "$name ($address)"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCameras(context: Context): List<CameraConfig> {
        val json = prefs(context).getString(KEY_CAMERAS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> cameraFromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCamera(context: Context, address: String): CameraConfig? =
        getCameras(context).find { it.address == address }

    fun addOrUpdateCamera(context: Context, camera: CameraConfig) {
        val cameras = getCameras(context).filterNot { it.address == camera.address } + camera
        saveCameras(context, cameras)
    }

    fun removeCamera(context: Context, address: String) {
        saveCameras(context, getCameras(context).filterNot { it.address == address })
    }

    private fun saveCameras(context: Context, cameras: List<CameraConfig>) {
        val array = JSONArray()
        cameras.forEach { array.put(cameraToJson(it)) }
        prefs(context).edit().putString(KEY_CAMERAS, array.toString()).apply()
    }

    private fun triggerToJson(t: MetricTrigger) = JSONObject().apply {
        put("enabled", t.enabled)
        put("startThreshold", t.startThreshold.toDouble())
        put("stopThreshold", t.stopThreshold.toDouble())
        put("startSeconds", t.startSeconds)
        put("stopSeconds", t.stopSeconds)
    }

    private fun triggerFromJson(o: JSONObject?): MetricTrigger = if (o == null) {
        MetricTrigger()
    } else {
        // Cameras saved before 2026-08-27 have a single "threshold" key, back when the
        // same number was used for both start and stop. Migrate it to both new fields so
        // an old config keeps behaving exactly as it did (falls back to 0 — same as
        // MetricTrigger()'s own default — if even that key is missing).
        val legacy = if (o.has("threshold")) o.optDouble("threshold", 0.0) else null
        MetricTrigger(
            enabled = o.optBoolean("enabled", false),
            startThreshold = o.optDouble("startThreshold", legacy ?: 0.0).toFloat(),
            stopThreshold = o.optDouble("stopThreshold", legacy ?: 0.0).toFloat(),
            startSeconds = o.optInt("startSeconds", 5),
            stopSeconds = o.optInt("stopSeconds", 30),
        )
    }

    private fun cameraToJson(c: CameraConfig) = JSONObject().apply {
        put("address", c.address)
        put("name", c.name)
        put("heartRate", triggerToJson(c.heartRate))
        put("power", triggerToJson(c.power))
        put("speed", triggerToJson(c.speed))
        put("radar", triggerToJson(c.radar))
    }

    private fun cameraFromJson(o: JSONObject) = CameraConfig(
        address = o.getString("address"),
        name = o.optString("name").ifBlank { o.getString("address") },
        heartRate = triggerFromJson(o.optJSONObject("heartRate")),
        power = triggerFromJson(o.optJSONObject("power")),
        speed = triggerFromJson(o.optJSONObject("speed")),
        // No "radar" key means an older saved camera from before this trigger existed —
        // triggerFromJson(null) falls back to MetricTrigger()'s generic defaults (enabled
        // = false, startSeconds = 5, stopSeconds = 30) rather than CameraConfig's radar-
        // specific ones above, but since it's disabled either way that's harmless; only
        // matters if the checkbox is ever turned on without also touching the numbers.
        radar = triggerFromJson(o.optJSONObject("radar")),
    )

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
