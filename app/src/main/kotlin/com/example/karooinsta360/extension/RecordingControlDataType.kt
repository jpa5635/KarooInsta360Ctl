package com.example.karooinsta360.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.karooinsta360.AppSettings
import com.example.karooinsta360.R
import com.example.karooinsta360.RecordingActions
import com.example.karooinsta360.RecordingToggleReceiver
import com.example.karooinsta360.connection.Insta360ConnectionManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig

/**
 * A graphical, *tappable* ride-page data field — drop it on any page and it shows
 * whether any saved camera is currently recording; tapping it fires
 * [Insta360ConnectionManager.toggleAllCameras] (start every connected-but-idle camera, or
 * stop every currently-recording one). This is a manual override that's actually visible
 * on-screen during a ride, unlike a controller-button BonusAction or the Control Center
 * notification (both act on all cameras the same all-or-nothing way, for the same
 * reason: none of these tap surfaces can offer a per-camera choice).
 *
 * No `startStream` override — this field is purely graphical (no numeric value to
 * stream), so the default no-op from [DataTypeImpl] is fine.
 *
 * **(2026-09-07)** Three additions:
 *
 *  - **Red background while recording.** The whole cell fills with
 *    [R.color.field_recording_bg], so recording state reads from peripheral vision without
 *    having to focus on the text.
 *  - **Full-width and half-width layouts.** Note this is *not* two declared data types.
 *    A field has no per-instance configuration and no width variants: the rider drops one
 *    field wherever they like, and [ViewConfig.gridSize] reports the cell they chose
 *    against a 60-unit grid (`Pair(60, 15)` being full width, quarter height). So the
 *    single `recording_control` typeId adapts — see [isFullWidth] — rather than cluttering
 *    the field picker with a `_half` twin that would render identically anyway if dropped
 *    into the wrong cell.
 *  - **Light and dark.** This one *does* need a setting, because karoo-ext exposes no
 *    theme signal anywhere — not in [ViewConfig], not in `RideProfile`, not in
 *    `UserProfile`. See [AppSettings.isFieldThemeDark].
 */
class RecordingControlDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val fullWidth = isFullWidth(config)
        val tall = config.gridSize.second > QUARTER_HEIGHT_ROWS

        fun render() {
            val states = Insta360ConnectionManager.getCameraStates(context)
            val recordingCameras = states.filter { it.recording }
            val recording = recordingCameras.isNotEmpty()
            val dark = AppSettings.isFieldThemeDark(context)
            val baseBackground = if (dark) R.color.field_dark_bg else R.color.field_light_bg

            // Stripes appear only for cameras that are actually recording, so the field
            // keeps its original meaning: colour in the cell means something is rolling.
            // A connected-but-idle camera contributes nothing.
            val stripeCameras = if (AppSettings.isRecordingColorFromBattery(context)) {
                recordingCameras.take(STRIPE_IDS.size)
            } else {
                emptyList()
            }

            val background = when {
                // Stripes cover the cell themselves; the root only shows through where
                // they don't reach, so it stays on the plain field background.
                stripeCameras.isNotEmpty() -> baseBackground
                recording -> R.color.field_recording_bg
                else -> baseBackground
            }

            // The text now sits in its own box (see the layout), so it no longer has to
            // survive being drawn straight onto the recording red or onto a battery band —
            // it's always on the theme background and always uses the theme text colour.
            val textColor = if (dark) R.color.field_dark_text else R.color.field_light_text
            val boxBackground =
                if (dark) R.drawable.bg_field_text_box_dark else R.drawable.bg_field_text_box_light

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RecordingToggleReceiver::class.java).setAction(RecordingActions.ACTION_TOGGLE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // While recording, the battery level replaces "Tap to Stop". The tap
            // instruction was the least useful thing the cell could say — a tile that
            // reads REC is self-evidently tappable — and the percentage is what you
            // actually want mid-ride.
            //
            // One "@ X%" per recording camera, in the same order as the stripes beneath.
            // A camera with no usable reading contributes nothing rather than an "@ --%",
            // so with no levels at all this degrades to a bare "REC".
            val levels = recordingCameras.mapNotNull { it.batteryPercent }
            val suffix = if (levels.isEmpty()) "" else levels.joinToString(" ") { "@ $it%" }

            val text = when {
                recording && suffix.isEmpty() -> "\u25CF REC"
                // Half-width can't fit the level on the same line at a legible size;
                // full-width looks sparse with it broken over two.
                recording && fullWidth -> "\u25CF REC $suffix"
                recording -> "\u25CF REC\n$suffix"
                fullWidth -> "Tap to Start"
                else -> "Tap to\nStart"
            }

            val textSizeSp = when {
                fullWidth && tall -> 22f
                fullWidth -> 18f
                tall -> 16f
                else -> 14f
            }

            val views = RemoteViews(context.packageName, R.layout.view_recording_control).apply {
                setTextViewText(R.id.recordingControlText, text)
                setTextViewTextSize(R.id.recordingControlText, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(R.id.recordingControlText, ContextCompat.getColor(context, textColor))
                setInt(
                    R.id.recordingControlRoot,
                    "setBackgroundColor",
                    ContextCompat.getColor(context, background),
                )
                setInt(R.id.recordingControlText, "setBackgroundResource", boxBackground)

                // A GONE stripe drops out of layout_weight distribution entirely, so the
                // visible ones always split the cell evenly however many there are.
                STRIPE_IDS.forEachIndexed { index, stripeId ->
                    val camera = stripeCameras.getOrNull(index)
                    if (camera == null) {
                        setViewVisibility(stripeId, View.GONE)
                    } else {
                        // No usable battery reading falls back to the plain recording red
                        // rather than to a neutral colour: this camera *is* recording, and
                        // that has to stay unmistakable even when its level is unknown.
                        val colorRes = camera.batteryBand?.fillColor ?: R.color.field_recording_bg
                        setViewVisibility(stripeId, View.VISIBLE)
                        setInt(stripeId, "setBackgroundColor", ContextCompat.getColor(context, colorRes))
                    }
                }

                setOnClickPendingIntent(R.id.recordingControlRoot, pendingIntent)
            }
            // Rate-limited to ~1Hz by ViewEmitter itself — a render() call that lands
            // less than ~900ms after the previous one is silently dropped, which is fine
            // here since the next onCameraStateChanged (or this data field's own next
            // render) will catch the view up shortly after.
            emitter.updateView(views)
        }

        render()
        val listener = object : Insta360ConnectionManager.Listener {
            override fun onCameraStateChanged(address: String) = render()
        }
        Insta360ConnectionManager.addListener(listener)

        // Theme is read fresh on every render, but a theme change on its own fires no
        // camera-state event, so without this the field would keep the old colours until
        // the next start/stop happened to repaint it.
        val themeListener = AppSettings.registerFieldThemeListener(context) { render() }

        emitter.setCancellable {
            Insta360ConnectionManager.removeListener(listener)
            AppSettings.unregisterFieldThemeListener(context, themeListener)
        }
    }

    companion object {
        const val TYPE_ID = "recording_control"

        /**
         * The declared stripe slots, in order. Cameras fill them in CameraStore order so
         * the leftmost stripe is the same physical camera every ride — and the same camera
         * as the leftmost percentage on the Distance field.
         *
         * Three is a deliberate cap. RemoteViews could build the row dynamically
         * (removeAllViews + addView), but addView is deprecated as of API 31, and a
         * half-width cell split four ways gives slivers that convey nothing anyway. A
         * fourth configured camera simply doesn't get a stripe.
         */
        private val STRIPE_IDS = intArrayOf(
            R.id.recordingStripe1,
            R.id.recordingStripe2,
            R.id.recordingStripe3,
        )

        /** A quarter-height row on Karoo's 60-unit grid. */
        const val QUARTER_HEIGHT_ROWS = 15

        /**
         * Karoo's grid is 60 columns wide, so a full-width field is 60 and a half-width
         * one is 30. Testing `>= 45` rather than `== 60` keeps this correct if a layout
         * ever offers a two-thirds cell, which should read as wide rather than narrow.
         */
        fun isFullWidth(config: ViewConfig): Boolean = config.gridSize.first >= 45
    }
}
