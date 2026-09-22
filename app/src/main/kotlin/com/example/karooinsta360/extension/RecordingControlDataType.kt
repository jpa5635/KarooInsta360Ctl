package com.example.karooinsta360.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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

        // (2026-09-22) Follow the page's field alignment, as native fields and the Distance
        // field do. The holder places the box; the TextView's own gravity lines up the two
        // lines of "Tap to / Start" and "REC / @ 87%" within it the same way.
        val horizontal = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        val holderGravity = horizontal or Gravity.CENTER_VERTICAL

        val fit = TextFit(context, config)
        var lastLogged: String? = null

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

            // Each state offers its text on one line and broken over two, and the one that
            // can be drawn larger in this cell wins (one line on a tie). See [TextFit].
            val candidates = when {
                recording && suffix.isEmpty() -> listOf("\u25CF REC")
                recording -> listOf("\u25CF REC $suffix", "\u25CF REC\n$suffix")
                else -> listOf("Tap to Start", "Tap to\nStart")
            }

            // Upper bounds, not fixed sizes: the sizes this tile has always used, which are
            // what it still gets wherever they fit. Only a cell too small for them shrinks.
            val maxSp = when {
                fullWidth && tall -> 22f
                fullWidth -> 18f
                tall -> 16f
                else -> 14f
            }
            val (text, textSizeSp) = fit.best(candidates, maxSp)

            val logLine = "fit: viewSize=${config.viewSize} textSize=${config.textSize} " +
                "grid=${config.gridSize} -> ${textSizeSp}sp ${text.replace('\n', '|')}"
            if (logLine != lastLogged) {
                lastLogged = logLine
                Log.i(TAG, logLine)
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
                setInt(R.id.recordingControlTextHolder, "setGravity", holderGravity)
                setInt(R.id.recordingControlText, "setGravity", holderGravity)

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

    /**
     * Picks a text size for the tile's box so the text fits the cell instead of wrapping
     * out of the bottom of it (2026-09-22).
     *
     * The sizes used to be a fixed table by cell shape. A half-width, quarter-height cell
     * at 14sp was too small for "Tap to / Start" once Karoo's header took its share of the
     * height, and the second line ran out of the bottom of the field. Now each candidate
     * text is measured with a Paint set up like the TextView (default family, bold) and
     * sized to the largest that fits both across and down, capped at the old table's size.
     *
     * Available space is the cell ([ViewConfig.viewSize]) less:
     *  - the holder's 4dp side padding and the box's own 10dp/6dp padding (layout XML);
     *  - Karoo's header, which this field keeps (showHeader is left at its default). Its
     *    height is estimated from the Distance field's measurement of where native labels
     *    sit: capitals start ~0.22 of textSize down and the label is ~0.34 of textSize, so
     *    the header ends at roughly [HEADER_RATIO] of textSize plus [HEADER_BASE_DP].
     *
     * If Karoo turns out to hand us viewSize *below* its header rather than for the whole
     * cell, this undersizes the text a little rather than overflowing — the safe side to be
     * wrong on. The "fit:" log line, set against the Distance field's "header:" line for
     * a cell of the same size, shows which it is.
     *
     * Line height uses the font's top/bottom extents because the tile's TextView keeps
     * includeFontPadding at its default (true), which reserves exactly that.
     */
    private class TextFit(context: Context, config: ViewConfig) {
        private val dm = context.resources.displayMetrics
        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
        private val pxPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 100f
        }
        private val firstLinePerPx: Float
        private val extraLinePerPx: Float
        init {
            val fm = paint.fontMetrics
            firstLinePerPx = (fm.bottom - fm.top) / 100f
            extraLinePerPx = (fm.descent - fm.ascent + fm.leading) / 100f
        }

        private val availW = (config.viewSize.first - dp(2 * 4f) - dp(2 * 10f)) * (1f - SAFETY)
        private val availH = (
            config.viewSize.second -
                dp(HEADER_BASE_DP) - HEADER_RATIO * config.textSize * pxPerSp -
                dp(2 * 6f)
            ) * (1f - SAFETY)

        /** Largest size (sp) at which [text] fits, never above [maxSp] or below [MIN_SP]. */
        fun sizeFor(text: String, maxSp: Float): Float {
            val lines = text.split('\n')
            val widestPerPx = lines.maxOf { paint.measureText(it) } / 100f
            val heightPerPx = firstLinePerPx + (lines.size - 1) * extraLinePerPx
            val byWidth = availW / widestPerPx / pxPerSp
            val byHeight = availH / heightPerPx / pxPerSp
            return minOf(maxSp, byWidth, byHeight).coerceAtLeast(MIN_SP)
        }

        /** The candidate that can be drawn largest, and its size. First wins a tie. */
        fun best(candidates: List<String>, maxSp: Float): Pair<String, Float> =
            candidates.map { it to sizeFor(it, maxSp) }.maxBy { it.second }

        companion object {
            private const val HEADER_BASE_DP = 3f
            private const val HEADER_RATIO = 0.55f
            private const val SAFETY = 0.06f
            private const val MIN_SP = 9f
        }
    }

    companion object {
        private const val TAG = "RecordingControl"
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
