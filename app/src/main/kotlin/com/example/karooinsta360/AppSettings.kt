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
    private const val KEY_DATA_SOURCE_LOSS_TIMEOUT_MINUTES = "data_source_loss_timeout_minutes"
    private const val DEFAULT_DATA_SOURCE_LOSS_TIMEOUT_MINUTES = 10
    private const val KEY_FIELD_THEME_DARK = "field_theme_dark"

    /** Post a status-bar notification whenever any saved camera starts or stops recording. */
    fun isRecordingNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ON_RECORDING_CHANGE, false)

    fun setRecordingNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ON_RECORDING_CHANGE, enabled).apply()
    }

    /**
     * **Added (2026-08-30)** — app-wide (not per-camera/profile) safety-net setting read
     * by [com.example.karooinsta360.extension.Insta360Extension.runCameraMonitor].
     *
     * Heart rate/power/speed/radar trigger latches decide start/stop from the *last known
     * value* of their metric, held indefinitely through a momentary sensor dropout on
     * purpose (see that class's `latestHr` doc comment) — a flaky strap losing signal for
     * a couple of seconds shouldn't be able to reset a sustained-threshold timer. Taken to
     * its extreme, though, that same held-over value means a data source that's gone for
     * good (a strap left at home, a dead sensor battery, a camera's radar losing its own
     * connection) never satisfies a stop condition either — the last reading before it
     * dropped out just sits there, and a recording that latch is holding open never ends.
     *
     * This is how long (minutes) a specific trigger's data source can go without a fresh
     * reading before that trigger is treated as having lost its source entirely and
     * released — independent of whatever value it was last holding. Only the latch(es)
     * whose own metric actually went stale are affected; another trigger on the same
     * camera that's still getting live data (e.g. speed, while heart rate's strap died)
     * is unaffected and can keep the recording going on its own. 0 disables this
     * entirely, restoring the original "hold forever" behavior.
     */
    fun getDataSourceLossTimeoutMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DATA_SOURCE_LOSS_TIMEOUT_MINUTES, DEFAULT_DATA_SOURCE_LOSS_TIMEOUT_MINUTES)

    fun setDataSourceLossTimeoutMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_DATA_SOURCE_LOSS_TIMEOUT_MINUTES, minutes.coerceAtLeast(0)).apply()
    }

    /**
     * **Added (2026-09-07)** — light or dark rendering for this extension's graphical
     * ride-page data fields (the Recording Control tile and the Recording Distance field).
     *
     * This has to be our own setting because karoo-ext exposes no theme signal at all:
     * [io.hammerhead.karooext.models.ViewConfig] carries grid size, view size, text size,
     * alignment, border and preview flags, and nothing about light/dark; neither does
     * `RideProfile` or `UserProfile`. A graphical field draws its own background, so
     * without this there is no way to match whatever the rest of the page looks like.
     *
     * App-wide rather than per-field-placement deliberately: a data field has no
     * per-instance configuration, so the only way to offer per-placement choice would be
     * to declare separate `..._light`/`..._dark` typeIds, doubling this extension's
     * entries in the rider's field picker for a setting almost nobody changes twice.
     *
     * Defaults to dark, matching Karoo's own default field design.
     */
    fun isFieldThemeDark(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIELD_THEME_DARK, true)

    fun setFieldThemeDark(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIELD_THEME_DARK, dark).apply()
    }

    /**
     * Watches [isFieldThemeDark] only. Used by the graphical data fields, which are
     * rendered by a service that has no other reason to know the app's settings screen
     * exists — without this, changing the theme wouldn't repaint an on-screen field until
     * something else happened to trigger a render.
     *
     * Returned listener must be handed back to [unregisterFieldThemeListener]; hold a
     * strong reference to it in the meantime, since SharedPreferences keeps only a weak
     * one and it will otherwise be collected mid-ride.
     */
    fun registerFieldThemeListener(
        context: Context,
        onChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FIELD_THEME_DARK) onChanged()
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterFieldThemeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
