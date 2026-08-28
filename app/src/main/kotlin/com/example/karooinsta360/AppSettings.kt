package com.example.karooinsta360

import android.content.Context
import android.content.SharedPreferences

/**
 * Small app-wide (not per-camera) preferences — kept separate from
 * [com.example.karooinsta360.camera.CameraStore] since none of this is part of any
 * camera's own config and shouldn't be duplicated/reset per camera.
 */
object AppSettings {
    private const val PREFS_NAME = "insta360_app_settings"
    private const val KEY_NOTIFY_ON_RECORDING_CHANGE = "notify_on_recording_change"
    private const val KEY_CONTROL_CENTER_CONTROL = "control_center_recording_control"

    /** Post a status-bar notification whenever any saved camera starts or stops recording. */
    fun isRecordingNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ON_RECORDING_CHANGE, false)

    fun setRecordingNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ON_RECORDING_CHANGE, enabled).apply()
    }

    /**
     * Keep a persistent Start/Stop control visible in the Karoo's own Control Center
     * (via [io.hammerhead.karooext.models.SystemNotification], not a regular Android
     * notification — see [com.example.karooinsta360.extension.Insta360Extension]). Off
     * by default since, unlike the momentary start/stop notification above, this one
     * sits in Control Center the whole time any camera is saved.
     */
    fun isControlCenterControlEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTROL_CENTER_CONTROL, false)

    fun setControlCenterControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTROL_CENTER_CONTROL, enabled).apply()
    }

    /**
     * Lets [com.example.karooinsta360.extension.Insta360Extension] react immediately
     * when [isControlCenterControlEnabled] is flipped in [MainActivity] — without this,
     * turning that setting on wouldn't post the Control Center notification until the
     * next unrelated recording-state change happened to fire.
     */
    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
