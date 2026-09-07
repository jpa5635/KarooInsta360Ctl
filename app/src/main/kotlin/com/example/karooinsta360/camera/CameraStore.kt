package com.example.karooinsta360.camera

import android.content.Context
import android.content.SharedPreferences
import io.hammerhead.karooext.models.DataType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted list of saved cameras — **identity only** (address + display name).
 *
 * **Changed (2026-08-29, build 0.1.9):** every camera used to carry its own independent
 * heart-rate/power/speed/radar trigger configuration directly on [CameraConfig]. Per
 * explicit request ("all of the triggers should be configured in the profile, not under
 * the camera settings"), that configuration has moved entirely to [ProfileStore] — a
 * camera is now just a Bluetooth address and a name; which of its triggers are active, and
 * with what values, is entirely a property of whichever profile currently has that camera
 * switched on (see [ProfileStore.Profile.activeCameraAddresses]/
 * [ProfileStore.Profile.cameraSettings]). See [migrateLegacyTriggersToProfileIfNeeded] for
 * how a camera's settings from before this change are preserved rather than silently lost.
 *
 * Stored as a small JSON blob in SharedPreferences (hand-rolled with [org.json], which
 * ships with Android — no extra dependency, and simpler than wiring up the Kotlin
 * serialization compiler plugin for a config this small). Read from
 * [com.example.karooinsta360.extension.Insta360Extension] (drives the auto-trigger) and
 * from [MainActivity][com.example.karooinsta360.MainActivity]/[CameraConfigActivity]/
 * [ProfileActivity]/[ProfileCameraConfigActivity] (list + edit UI); both processes...
 * well, same process, same as [com.example.karooinsta360.connection.Insta360ConnectionManager]
 * — see its doc comment for why that matters here too.
 */
object CameraStore {
    private const val PREFS_NAME = "insta360_cameras"
    private const val KEY_CAMERAS = "cameras_json"
    private const val KEY_MIGRATED_LEGACY_TRIGGERS = "migrated_legacy_triggers_to_profile_v1"

    enum class Metric(val label: String, val unit: String, val dataTypeId: String) {
        HEART_RATE("Heart Rate", "bpm", DataType.Type.HEART_RATE),
        POWER("Power", "W", DataType.Type.POWER),
        SPEED("Speed", "mph or km/h", DataType.Type.SPEED),
        RADAR("Radar (vehicle approaching)", "ft or m", DataType.Type.RADAR),
    }

    /**
     * Unit a profile's Speed trigger threshold is entered/displayed in (see
     * [ProfileStore.ProfileCameraSettings.speedUnit]). [metersPerSecondPerUnit] converts a
     * threshold value in this unit to the Karoo's raw m/s speed reading, the same way
     * [DistanceUnit.metersPerUnit] does for Radar.
     */
    enum class SpeedUnit(val label: String, val metersPerSecondPerUnit: Double) {
        MPH("mph", 0.44704),
        KMH("km/h", 0.277778),
    }

    /**
     * Unit a profile's Radar trigger distance is entered/displayed in (see
     * [ProfileStore.ProfileCameraSettings.radarUnit]). [metersPerUnit] converts a distance
     * in this unit to meters, matching the units karoo-ext's RADAR data type reports
     * target range in.
     */
    enum class DistanceUnit(val label: String, val metersPerUnit: Double) {
        FEET("ft", 0.3048),
        METERS("m", 1.0),
    }

    data class MetricTrigger(
        val enabled: Boolean = false,
        // Separate start/stop values on purpose (2026-08-27) — e.g. start recording once
        // power reaches 300W, but only stop once it's fallen under some other number
        // entirely, rather than being forced to use the same 300 for both. For Heart
        // Rate/Power/Speed: start fires once the live value rises to/above
        // [startThreshold] (sustained [startSeconds]); stop fires once it falls below
        // [stopThreshold] (sustained [stopSeconds]). For Radar, [startThreshold] is the
        // trigger distance and [stopThreshold] is unused — see
        // [com.example.karooinsta360.extension.Insta360Extension] for why its stop side
        // isn't a threshold at all.
        val startThreshold: Float = 0f,
        val stopThreshold: Float = 0f,
        val startSeconds: Int = 5,
        val stopSeconds: Int = 30,
    )

    /** A saved camera's identity — nothing about its trigger behavior lives here any more. */
    data class CameraConfig(
        val address: String,
        val name: String,
    ) {
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

    private fun cameraToJson(c: CameraConfig) = JSONObject().apply {
        put("address", c.address)
        put("name", c.name)
    }

    private fun cameraFromJson(o: JSONObject) = CameraConfig(
        address = o.getString("address"),
        name = o.optString("name").ifBlank { o.getString("address") },
    )

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * One-time migration (2026-08-29, build 0.1.9). Before this version, [cameraToJson]
     * wrote each camera's trigger settings (heartRate/power/speed/radar/etc.) right into
     * this same JSON blob, alongside address/name. [cameraFromJson] above no longer reads
     * those keys — they're simply invisible to [getCameras] now — but they're still
     * sitting in the raw stored string for anyone who saved a camera before this build,
     * until something next calls [saveCameras] and rewrites it without them.
     *
     * Runs once (guarded by [KEY_MIGRATED_LEGACY_TRIGGERS]): parses that raw JSON directly
     * — bypassing [cameraFromJson] specifically so it can still see the old fields — and,
     * if there's any actually-enabled trigger data in there AND no [ProfileStore] profile
     * exists yet (an upgrade from before profiles existed at all, build 0.1.7 or earlier;
     * an 0.1.8 user already has their own real profiles and this deliberately leaves those
     * alone), packages it into one new "Migrated Settings" profile — covering exactly the
     * cameras that had real settings, switched on — and makes it the active profile. Without
     * this, upgrading straight to 0.1.9 would silently discard real, ride-tested trigger
     * numbers the moment CameraConfig stopped carrying them.
     *
     * Called from both [com.example.karooinsta360.MainActivity.onCreate] and
     * [com.example.karooinsta360.extension.Insta360Extension.onCreate] — whichever the
     * Karoo happens to start first — so migration has definitely already happened by the
     * time either one needs profile data, regardless of launch order.
     */
    fun migrateLegacyTriggersToProfileIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED_LEGACY_TRIGGERS, false)) return
        p.edit().putBoolean(KEY_MIGRATED_LEGACY_TRIGGERS, true).apply()

        // An 0.1.8 user already has real profiles of their own — don't second-guess them
        // by conjuring up an extra one from whatever old per-camera fields happen to still
        // be sitting in the raw JSON underneath.
        if (ProfileStore.getProfiles(context).isNotEmpty()) return

        val json = p.getString(KEY_CAMERAS, null) ?: return
        val settings: Map<String, ProfileStore.ProfileCameraSettings> = try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val address = o.optString("address").ifBlank { null } ?: return@mapNotNull null
                val hr = o.optJSONObject("heartRate")
                val power = o.optJSONObject("power")
                val speed = o.optJSONObject("speed")
                val radar = o.optJSONObject("radar")
                val hasAnyEnabled = listOf(hr, power, speed, radar).any { it?.optBoolean("enabled", false) == true }
                if (!hasAnyEnabled) return@mapNotNull null
                address to ProfileStore.ProfileCameraSettings(
                    heartRate = legacyTriggerFromJson(hr),
                    power = legacyTriggerFromJson(power),
                    powerStopAllowedSpikes = o.optInt("powerStopAllowedSpikes", 0).coerceIn(0, 5),
                    speed = legacyTriggerFromJson(speed),
                    speedUnit = runCatching { SpeedUnit.valueOf(o.optString("speedUnit")) }.getOrDefault(SpeedUnit.MPH),
                    radar = legacyTriggerFromJson(radar),
                    radarUnit = runCatching { DistanceUnit.valueOf(o.optString("radarUnit")) }.getOrDefault(DistanceUnit.FEET),
                )
            }.toMap()
        } catch (e: Exception) {
            return
        }
        if (settings.isEmpty()) return

        val profile = ProfileStore.Profile(
            id = ProfileStore.newProfileId(),
            name = "Migrated Settings",
            activeCameraAddresses = settings.keys,
            cameraSettings = settings,
        )
        ProfileStore.saveProfile(context, profile)
        ProfileStore.setActiveProfileId(context, profile.id)
    }

    private fun legacyTriggerFromJson(o: JSONObject?): MetricTrigger = if (o == null) {
        MetricTrigger()
    } else {
        // Cameras saved before 2026-08-27 have a single "threshold" key, back when the
        // same number was used for both start and stop.
        val legacy = if (o.has("threshold")) o.optDouble("threshold", 0.0) else null
        MetricTrigger(
            enabled = o.optBoolean("enabled", false),
            startThreshold = o.optDouble("startThreshold", legacy ?: 0.0).toFloat(),
            stopThreshold = o.optDouble("stopThreshold", legacy ?: 0.0).toFloat(),
            startSeconds = o.optInt("startSeconds", 5),
            stopSeconds = o.optInt("stopSeconds", 30),
        )
    }
}
