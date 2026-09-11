package com.example.karooinsta360.camera

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Named, user-managed "configuration profiles" — **the sole owner of trigger
 * configuration** (2026-08-29, build 0.1.9; superseding build 0.1.8's design per explicit
 * feedback that triggers belonged in the profile, not under each camera's own settings).
 *
 * A [Profile] has two parts:
 *  - [Profile.activeCameraAddresses] — *which* of the app's saved cameras this profile
 *    actually considers at all. A camera not in this set is completely outside this
 *    profile's automation while it's the active profile — [com.example.karooinsta360.extension.Insta360Extension]
 *    doesn't even run a monitor for it (see `resyncCameraMonitors`).
 *  - [Profile.cameraSettings] — the full trigger configuration (heart rate/power/speed/
 *    radar thresholds, durations, units, power's spike tolerance) for each camera address
 *    this profile has ever included. Deliberately **not** pruned when a camera is switched
 *    off ([setCameraIncluded] with `included = false`) — switching it back on later
 *    restores its previous numbers instead of resetting to defaults, the same way muting a
 *    track in a DAW doesn't erase its settings.
 *
 * The point is switching the *whole fleet's* trigger behavior at once — e.g. a "Road" and
 * a "Gravel race" profile with different speed/radar thresholds, or a profile that only
 * considers a subset of cameras — without re-editing anything by hand each time.
 *
 * "Active profile" ([getActiveProfileId]/[setActiveProfileId]) is which profile
 * [Insta360Extension][com.example.karooinsta360.extension.Insta360Extension] is currently
 * driving automation from — this one really is continuously enforced (unlike 0.1.8's
 * "active" label, which was bookkeeping only): every trigger the extension runs comes
 * from the active profile's [Profile.activeCameraAddresses]/[Profile.cameraSettings],
 * re-read every time either changes (see [registerChangeListener]).
 *
 * Stored the same way [CameraStore] is — a hand-rolled JSON blob in its own
 * SharedPreferences file, no extra dependency.
 */
object ProfileStore {
    private const val PREFS_NAME = "insta360_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    /**
     * Everything a profile tracks for one camera it includes — heart rate/power/speed/
     * radar trigger configuration. Nothing about camera identity (address/name) lives
     * here; that stays in [CameraStore.CameraConfig].
     */
    data class ProfileCameraSettings(
        val heartRate: CameraStore.MetricTrigger,
        val power: CameraStore.MetricTrigger,
        val powerStopAllowedSpikes: Int,
        val speed: CameraStore.MetricTrigger,
        val speedUnit: CameraStore.SpeedUnit,
        val radar: CameraStore.MetricTrigger,
        val radarUnit: CameraStore.DistanceUnit,
        /**
         * **Added (2026-09-11)** — when true, the heart rate, power and speed triggers
         * stop *starting* recordings once this camera's battery falls to
         * [batteryFloorPercent] or below, so a nearly flat camera saves what's left for
         * the moments you choose deliberately.
         *
         * Radar is exempt on purpose. It's the one trigger whose job is to catch a vehicle
         * you may need evidence of, and a rider who set it up would rather it spend the
         * last 8% than conserve it.
         *
         * Manual starts are never blocked either — the tile, the controller button and the
         * camera's own shutter all still work at any level. This only silences automation.
         */
        val batteryFloorEnabled: Boolean = false,
        /** Percentage at or below which the triggers above are ignored. */
        val batteryFloorPercent: Int = DEFAULT_BATTERY_FLOOR_PERCENT,
    ) {
        companion object {
            /**
             * Every trigger off, otherwise matching the same defaults a brand new camera
             * used to get back when it carried its own settings (Radar's 100-unit/0s
             * start/15s stop preset in particular — see the old `CameraConfig`'s history —
             * kept so a freshly-included camera's numbers look sane the moment Radar gets
             * switched on, rather than 0/0/0).
             */
            val DEFAULT = ProfileCameraSettings(
                heartRate = CameraStore.MetricTrigger(),
                power = CameraStore.MetricTrigger(),
                powerStopAllowedSpikes = 0,
                speed = CameraStore.MetricTrigger(),
                speedUnit = CameraStore.SpeedUnit.MPH,
                radar = CameraStore.MetricTrigger(startThreshold = 100f, stopThreshold = 100f, startSeconds = 0, stopSeconds = 15),
                radarUnit = CameraStore.DistanceUnit.FEET,
                batteryFloorEnabled = false,
                batteryFloorPercent = DEFAULT_BATTERY_FLOOR_PERCENT,
            )

            /**
             * Low enough that it only bites when a camera genuinely can't finish the ride,
             * rather than second-guessing a rider who started out at 40%.
             */
            const val DEFAULT_BATTERY_FLOOR_PERCENT = 10
        }
    }

    /**
     * [id] is a stable identifier separate from [name] specifically so renaming a profile
     * (or two profiles sharing a name) never breaks [getActiveProfileId]'s reference to it.
     */
    data class Profile(
        val id: String,
        val name: String,
        val activeCameraAddresses: Set<String> = emptySet(),
        val cameraSettings: Map<String, ProfileCameraSettings> = emptyMap(),
        /**
         * **Added (2026-09-08)** — the name of the Karoo ride profile (Road, Gravel,
         * MTB, ...) this profile should become active for. Free text rather than a
         * picker: karoo-ext has no API to list a rider's configured ride profiles, only
         * [io.hammerhead.karooext.models.ActiveRideProfile] naming whichever one is
         * currently selected — see [findProfileForKarooProfileName] for the matching
         * side. Null/blank means this profile isn't linked to any Karoo profile.
         */
        val karooProfileName: String? = null,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProfiles(context: Context): List<Profile> {
        val json = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> profileFromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getProfile(context: Context, id: String): Profile? =
        getProfiles(context).find { it.id == id }

    /** Adds a new profile, or overwrites the existing one with the same [Profile.id]. */
    fun saveProfile(context: Context, profile: Profile) {
        val profiles = getProfiles(context).filterNot { it.id == profile.id } + profile
        saveProfiles(context, profiles)
    }

    fun renameProfile(context: Context, id: String, newName: String) {
        val profile = getProfile(context, id) ?: return
        saveProfile(context, profile.copy(name = newName))
    }

    fun deleteProfile(context: Context, id: String) {
        saveProfiles(context, getProfiles(context).filterNot { it.id == id })
        if (getActiveProfileId(context) == id) setActiveProfileId(context, null)
    }

    fun getActiveProfileId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)

    /** Which profile [Insta360Extension][com.example.karooinsta360.extension.Insta360Extension] currently drives automation from. */
    fun setActiveProfileId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    /** MainActivity's "Apply" button — just a friendlier name for [setActiveProfileId]. */
    fun activateProfile(context: Context, id: String) {
        setActiveProfileId(context, id)
    }

    /** Builds a new [Profile.id] — callers generate one when creating a brand new profile. */
    fun newProfileId(): String = UUID.randomUUID().toString()

    /**
     * Creates a brand new profile covering every currently-saved camera (all switched on
     * by default — nothing useful happens with zero active cameras, and it's one tap to
     * switch one back off in [ProfileActivity][com.example.karooinsta360.camera.ProfileActivity]
     * if that's not wanted). Each camera's starting settings come from [seedFromProfileId]
     * (typically the currently-active profile, so a new profile starts from a known,
     * already-tuned baseline instead of zero) where that profile covers the same address,
     * falling back to [ProfileCameraSettings.DEFAULT] for a camera it doesn't.
     */
    fun createProfile(context: Context, name: String, seedFromProfileId: String?): Profile {
        val addresses = CameraStore.getCameras(context).map { it.address }.toSet()
        val seedProfile = seedFromProfileId?.let { getProfile(context, it) }
        val settings = addresses.associateWith { address ->
            seedProfile?.cameraSettings?.get(address) ?: ProfileCameraSettings.DEFAULT
        }
        val profile = Profile(
            id = newProfileId(),
            name = name,
            activeCameraAddresses = addresses,
            cameraSettings = settings,
        )
        saveProfile(context, profile)
        return profile
    }

    /**
     * Switches whether [profileId] considers [address] at all. Turning a camera on for the
     * first time (no prior [ProfileCameraSettings] for it in this profile) seeds it with
     * [ProfileCameraSettings.DEFAULT]; turning it off leaves whatever settings it already
     * had untouched in [Profile.cameraSettings] — see the class doc for why.
     */
    fun setCameraIncluded(context: Context, profileId: String, address: String, included: Boolean) {
        val profile = getProfile(context, profileId) ?: return
        val newAddresses = if (included) profile.activeCameraAddresses + address else profile.activeCameraAddresses - address
        val newSettings = if (included && address !in profile.cameraSettings) {
            profile.cameraSettings + (address to ProfileCameraSettings.DEFAULT)
        } else {
            profile.cameraSettings
        }
        saveProfile(context, profile.copy(activeCameraAddresses = newAddresses, cameraSettings = newSettings))
    }

    /** [ProfileCameraConfigActivity][com.example.karooinsta360.camera.ProfileCameraConfigActivity]'s Save button. */
    fun updateCameraSettings(context: Context, profileId: String, address: String, settings: ProfileCameraSettings) {
        val profile = getProfile(context, profileId) ?: return
        saveProfile(context, profile.copy(cameraSettings = profile.cameraSettings + (address to settings)))
    }

    /**
     * Sets (or, with a blank/null [name], clears) [profileId]'s linked Karoo ride profile
     * name. Rejects — returns `false`, saves nothing — if [name] (trimmed) already matches
     * another profile's [Profile.karooProfileName] case-insensitively, keeping the mapping
     * one-to-one so [findProfileForKarooProfileName] never has two candidates to choose
     * between. Clearing (blank/null) is always allowed since any number of profiles can be
     * simultaneously unlinked.
     */
    fun setKarooProfileName(context: Context, profileId: String, name: String?): Boolean {
        val profile = getProfile(context, profileId) ?: return false
        val trimmed = name?.trim().takeUnless { it.isNullOrEmpty() }
        if (trimmed != null) {
            val conflict = getProfiles(context).any {
                it.id != profileId && it.karooProfileName?.trim()?.equals(trimmed, ignoreCase = true) == true
            }
            if (conflict) return false
        }
        saveProfile(context, profile.copy(karooProfileName = trimmed))
        return true
    }

    /**
     * Matches an [io.hammerhead.karooext.models.ActiveRideProfile] name against every
     * profile's [Profile.karooProfileName], trimmed and case-insensitive so "Road",
     * "road", and " Road " all resolve to the same mapping. Null if nothing matches —
     * callers should leave whichever profile is already active untouched in that case
     * (see [Insta360Extension][com.example.karooinsta360.extension.Insta360Extension]),
     * rather than treat a typo'd/unmapped Karoo profile as "no profile".
     */
    fun findProfileForKarooProfileName(context: Context, karooProfileName: String): Profile? {
        val target = karooProfileName.trim()
        if (target.isEmpty()) return null
        return getProfiles(context).find { it.karooProfileName?.trim()?.equals(target, ignoreCase = true) == true }
    }

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun saveProfiles(context: Context, profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(profileToJson(it)) }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun triggerToJson(t: CameraStore.MetricTrigger) = JSONObject().apply {
        put("enabled", t.enabled)
        put("startThreshold", t.startThreshold.toDouble())
        put("stopThreshold", t.stopThreshold.toDouble())
        put("startSeconds", t.startSeconds)
        put("stopSeconds", t.stopSeconds)
    }

    private fun triggerFromJson(o: JSONObject?): CameraStore.MetricTrigger = if (o == null) {
        CameraStore.MetricTrigger()
    } else {
        CameraStore.MetricTrigger(
            enabled = o.optBoolean("enabled", false),
            startThreshold = o.optDouble("startThreshold", 0.0).toFloat(),
            stopThreshold = o.optDouble("stopThreshold", 0.0).toFloat(),
            startSeconds = o.optInt("startSeconds", 5),
            stopSeconds = o.optInt("stopSeconds", 30),
        )
    }

    private fun settingsToJson(s: ProfileCameraSettings) = JSONObject().apply {
        put("heartRate", triggerToJson(s.heartRate))
        put("power", triggerToJson(s.power))
        put("powerStopAllowedSpikes", s.powerStopAllowedSpikes)
        put("speed", triggerToJson(s.speed))
        put("speedUnit", s.speedUnit.name)
        put("radar", triggerToJson(s.radar))
        put("radarUnit", s.radarUnit.name)
        put("batteryFloorEnabled", s.batteryFloorEnabled)
        put("batteryFloorPercent", s.batteryFloorPercent)
    }

    private fun settingsFromJson(o: JSONObject) = ProfileCameraSettings(
        heartRate = triggerFromJson(o.optJSONObject("heartRate")),
        power = triggerFromJson(o.optJSONObject("power")),
        powerStopAllowedSpikes = o.optInt("powerStopAllowedSpikes", 0).coerceIn(0, 5),
        speed = triggerFromJson(o.optJSONObject("speed")),
        speedUnit = runCatching { CameraStore.SpeedUnit.valueOf(o.optString("speedUnit")) }
            .getOrDefault(CameraStore.SpeedUnit.MPH),
        radar = triggerFromJson(o.optJSONObject("radar")),
        // Absent in profiles written before 0.1.55; opt(...) with these defaults leaves
        // an existing profile behaving exactly as it did rather than silently acquiring
        // a floor nobody asked for.
        batteryFloorEnabled = o.optBoolean("batteryFloorEnabled", false),
        batteryFloorPercent = o.optInt("batteryFloorPercent", ProfileCameraSettings.DEFAULT_BATTERY_FLOOR_PERCENT),
        radarUnit = runCatching { CameraStore.DistanceUnit.valueOf(o.optString("radarUnit")) }
            .getOrDefault(CameraStore.DistanceUnit.FEET),
    )

    private fun profileToJson(p: Profile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        val addresses = JSONArray()
        p.activeCameraAddresses.forEach { addresses.put(it) }
        put("activeCameraAddresses", addresses)
        val settings = JSONObject()
        p.cameraSettings.forEach { (address, s) -> settings.put(address, settingsToJson(s)) }
        put("cameraSettings", settings)
        // JSONObject silently drops a put(key, null), so an absent key on read already
        // means "no mapping" — optString below then defaults it to "", matching that.
        p.karooProfileName?.let { put("karooProfileName", it) }
    }

    private fun profileFromJson(o: JSONObject): Profile {
        val settingsObj = o.optJSONObject("cameraSettings") ?: JSONObject()
        val settings = settingsObj.keys().asSequence().associateWith { address ->
            settingsFromJson(settingsObj.getJSONObject(address))
        }
        // A profile saved before "which cameras are active" existed as its own concept
        // (build 0.1.8) implicitly covered every camera present in cameraSettings — keep
        // that meaning on load so upgrading doesn't silently switch off every camera an
        // existing profile already had.
        val activeAddresses = if (o.has("activeCameraAddresses")) {
            val arr = o.optJSONArray("activeCameraAddresses") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } else {
            settings.keys
        }
        return Profile(
            id = o.optString("id").ifBlank { newProfileId() },
            name = o.optString("name").ifBlank { "Unnamed profile" },
            activeCameraAddresses = activeAddresses,
            cameraSettings = settings,
            karooProfileName = o.optString("karooProfileName").ifBlank { null },
        )
    }
}
