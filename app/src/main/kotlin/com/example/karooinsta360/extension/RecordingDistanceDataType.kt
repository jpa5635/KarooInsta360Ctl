package com.example.karooinsta360.extension

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.karooinsta360.AppSettings
import com.example.karooinsta360.R
import com.example.karooinsta360.connection.Insta360ConnectionManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Ride distance that behaves like Karoo's own Distance field, plus a flashing red dot
 * whenever any saved camera is recording.
 *
 * The point of this field is that it costs no extra page space: rather than spending a
 * cell on a dedicated recording indicator, it replaces the Distance field you were going
 * to have on the page anyway.
 *
 * ### Why this draws its own number (2026-09-07)
 *
 * The first version deliberately did not. `UpdateGraphicConfig.formatDataTypeId` is
 * documented to exist precisely for this — "overlay graphical elements on existing numeric
 * data field treatment" — so `startStream` republished the system `TYPE_DISTANCE_ID` value
 * under this field's own id and `startView` asked Karoo to render it with its stock
 * distance treatment, leaving our RemoteViews to draw only the dot. That gets native
 * units, precision and font for free, and survives future Karoo restyles.
 *
 * On device the cell rendered with no number at all. Rather than keep guessing at why,
 * this now draws the value itself from a subscription made directly in [startView]. That
 * matters beyond just "it works": the direct subscription doesn't depend on Karoo choosing
 * to start our [startStream] at all, which was one of the two candidate explanations and
 * the one we couldn't rule out from the outside.
 *
 * The cost is real and worth naming: the font won't match a stock field exactly, and unit
 * formatting is now ours to keep correct (see [formatDistance], which reads the rider's
 * configured unit system rather than assuming). [startStream] is kept anyway, so the type
 * still works as a plain numeric field and so reverting to the overlay approach later is a
 * small change if it turns out to work on a future karoo-ext.
 *
 * Width and theme follow [RecordingControlDataType]: one typeId adapting to
 * [ViewConfig.gridSize] rather than declared variants, and the theme from
 * [AppSettings.isFieldThemeDark] since karoo-ext exposes no theme signal.
 *
 * Flash rate is 1s on / 1s off — a floor, not a preference, since `ViewEmitter.updateView`
 * drops any view emitted less than ~900ms after the previous one.
 */
class RecordingDistanceDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.i(TAG, "startStream: subscribing to ${DataType.Type.DISTANCE}")
        var logged = 0
        val job = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.DISTANCE).collect { state ->
                if (logged < 5) {
                    logged++
                    Log.i(TAG, "startStream: upstream state=$state")
                }
                when (state) {
                    is StreamState.Streaming -> emitter.onNext(
                        state.copy(
                            dataPoint = state.dataPoint.copy(
                                dataTypeId = dataTypeId,
                                values = mapOf(
                                    DataType.Field.SINGLE to (state.dataPoint.singleValue ?: 0.0),
                                ),
                            ),
                        ),
                    )
                    else -> emitter.onNext(state)
                }
            }
        }
        emitter.setCancellable {
            Log.i(TAG, "startStream: cancelled")
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(TAG, "startView grid=${config.gridSize} alignment=${config.alignment} preview=${config.preview}")

        // Stock header stays off and we draw our own label instead. Not ideology: with
        // showHeader = true this field previously rendered nothing at all, and until
        // that's understood, a label we control is a label that definitely appears. The
        // trade is losing the field icon the stock header would have shown.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        // (2026-09-07) ViewConfig.textSize is documented as "font size used in standard
        // numeric view of this grid size", i.e. exactly the size Karoo's own fields render
        // at in this cell. The first version ignored it in favour of hardcoded sizes per
        // cell shape, which is why this field didn't match its neighbours. Use the value
        // the system hands us.
        // Scaled down from ViewConfig.textSize rather than used raw. That value is the
        // size Karoo renders at in this cell when *it* draws the header, but we draw our
        // own label inside the same space, so the value has one row less height than the
        // figure assumes — at full size the bottoms of the digits were being clipped by
        // the field's lower boundary.
        val valueTextSizeSp = config.textSize * VALUE_SIZE_RATIO

        // Header scaled off the same number rather than fixed, so it stays proportionate
        // across cell sizes. Floored so it doesn't vanish in a small cell.
        val labelTextSizeSp = (config.textSize * LABEL_SIZE_RATIO).coerceAtLeast(MIN_LABEL_SP)

        // Latest values, written by their own collectors and read by the render loop.
        val meters = AtomicReference<Double?>(null)
        val imperial = AtomicReference(false)

        val distanceJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.DISTANCE).collect { state ->
                meters.set((state as? StreamState.Streaming)?.dataPoint?.singleValue)
            }
        }

        // Distance is meaningless without knowing which units the rider reads in, and
        // that's a profile setting rather than something derivable from the value.
        val profileJob = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.consumerFlow<UserProfile>().collect { profile ->
                val isImperial =
                    profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                imperial.set(isImperial)
                Log.i(TAG, "startView: distance unit imperial=$isImperial")
            }
        }

        var renderJob: Job? = null
        renderJob = CoroutineScope(Dispatchers.Default).launch {
            var blinkOn = true
            while (isActive) {
                val dark = AppSettings.isFieldThemeDark(context)
                val textColor = if (dark) R.color.field_dark_text else R.color.field_light_text
                // Two reds rather than one: #FF2D2D is bright enough to read on the dark
                // field background but washes out on the light one, where the deeper
                // #C1121F holds up. Same red the Recording Control tile fills with.
                val recordingColor =
                    if (dark) R.color.recording_dot else R.color.field_recording_bg
                // Only connected cameras get a slot. A camera that isn't there shows
                // nothing at all rather than a placeholder — with none connected this
                // renders as a plain right-justified DISTANCE label, i.e. as an ordinary
                // distance field.
                val cameras = Insta360ConnectionManager.getCameraStates(context)
                    .filter { it.connected }
                    .take(CAMERA_SLOTS.size)

                val views = RemoteViews(context.packageName, R.layout.view_recording_distance).apply {
                    // Just the field name, matching how Karoo labels its own fields. The
                    // unit is deliberately not shown: the rider set it in their profile
                    // and it doesn't change mid-ride, so repeating it in every frame is
                    // noise on a cell this small.
                    setTextViewText(R.id.distanceLabel, "DISTANCE")
                    setTextViewTextSize(R.id.distanceLabel, TypedValue.COMPLEX_UNIT_SP, labelTextSizeSp)
                    setTextColor(R.id.distanceLabel, ContextCompat.getColor(context, textColor))
                    setTextViewText(R.id.distanceValue, formatDistance(meters.get(), imperial.get()))
                    setTextViewTextSize(R.id.distanceValue, TypedValue.COMPLEX_UNIT_SP, valueTextSizeSp)
                    setTextColor(R.id.distanceValue, ContextCompat.getColor(context, textColor))

                    CAMERA_SLOTS.forEachIndexed { index, slot ->
                        val camera = cameras.getOrNull(index)
                        // In the field picker's preview there are no live cameras, so show
                        // one populated slot rather than an empty row that makes the field
                        // look broken before it's been placed.
                        val preview = config.preview && index == 0 && cameras.isEmpty()

                        if (camera == null && !preview) {
                            setViewVisibility(slot.percentId, View.GONE)
                            return@forEachIndexed
                        }

                        val percent = camera?.batteryPercent
                        // "--%" rather than a collapsed slot for a camera that is present
                        // but hasn't reported a level yet: it's connected, and blanking it
                        // would make the number pop in later and shove its neighbours over.
                        setTextViewText(slot.percentId, if (percent == null) "--%" else "$percent%")
                        setTextViewTextSize(slot.percentId, TypedValue.COMPLEX_UNIT_SP, labelTextSizeSp)

                        // Red while this camera is recording, the ordinary field text
                        // colour otherwise. Colour carries the state on its own, so a
                        // camera that's rolling still reads as rolling in the half of the
                        // blink cycle where the number is on screen.
                        val recording = camera?.recording == true || preview
                        setTextColor(
                            slot.percentId,
                            ContextCompat.getColor(context, if (recording) recordingColor else textColor),
                        )

                        // The percentage itself is the recording indicator: it blinks while
                        // that camera is rolling. INVISIBLE rather than GONE on the dark
                        // half of the blink, so the slot keeps its width and the cameras
                        // beside it don't shuffle sideways twice a second.
                        val blankThisFrame = recording && !blinkOn && !config.preview
                        setViewVisibility(
                            slot.percentId,
                            if (blankThisFrame) View.INVISIBLE else View.VISIBLE,
                        )
                    }
                }
                emitter.updateView(views)

                delay(BLINK_PERIOD_MS)
                blinkOn = !blinkOn
            }
        }

        emitter.setCancellable {
            Log.i(TAG, "stopView")
            distanceJob.cancel()
            profileJob.cancel()
            renderJob?.cancel()
        }
    }

    /**
     * Karoo reports distance in meters. Two decimal places matches the stock field's
     * treatment closely enough to sit beside it without looking out of place.
     */
    /**
     * Two decimals below 100, one at or above — matching Karoo's own distance field.
     *
     * Not cosmetic. The value TextView is maxLines=1 with a fixed textSize and no
     * ellipsize or autosizing, so a string that outgrows the cell is simply clipped at the
     * edge. At a flat "%.2f" the text gains a character at 100 ("99.99" -> "100.00"), which
     * on a half-width cell is enough to cut a digit off a long ride's distance — and it
     * would happen at exactly the point in a ride where you'd least want to be recounting
     * digits. Switching precision keeps it at five characters either side of the boundary.
     *
     * The cost is a digit of precision past 100, which is what Karoo itself decided was
     * the right trade.
     */
    private fun formatDistance(meters: Double?, imperial: Boolean): String {
        if (meters == null) return "--"
        val value = if (imperial) meters / METERS_PER_MILE else meters / 1000.0
        // Guard on the *rounded* value, not the raw one: 99.999 formats as "100.00" under
        // "%.2f", so testing the raw value would let exactly the string this avoids slip
        // through in the last fraction of a mile before the boundary.
        val roundsToHundred = "%.2f".format(value).length > 5
        return if (roundsToHundred) "%.1f".format(value) else "%.2f".format(value)
    }

    companion object {
        private const val TAG = "RecordingDistance"
        const val TYPE_ID = "recording_distance"
        const val BLINK_PERIOD_MS = 1_000L
        private const val METERS_PER_MILE = 1609.344
        private const val LABEL_SIZE_RATIO = 0.30f

        /**
         * Compensates for the label row our layout adds, which Karoo's own textSize figure
         * doesn't account for.
         *
         * 0.88 -> 0.96 -> 1.0 (2026-09-11). 1.0 is not a guess: Karoo's textSize is what a
         * stock field renders its value at alongside its own header, and our label row
         * takes about the space that header would, so matching it exactly is what makes
         * our digits the same height as the ones beside them.
         *
         * The earlier reductions were compensating for something else. The value looked
         * short and the gap above it looked wide, but the cause was includeFontPadding on
         * the value TextView reserving a descender that digits don't have — shrinking the
         * text only made the number smaller while leaving the gap exactly where it was.
         *
         * 1.0 -> 1.08 (2026-09-11): at 1.0 the digits still measured slightly shorter than
         * the stock fields beside them. Nothing here measures the cell — the size comes
         * from Karoo's figure and the row then centres whatever it gets in the leftover
         * height — so 1.0 only matches in theory, and going past it is legitimate as long
         * as it fits. It does: at 1.0 there was visible slack above and below the digits.
         *
         * This is the dial that clips first. If the tops or bottoms of digits are ever cut
         * off on a short cell, lower this and change nothing else.
         */
        private const val VALUE_SIZE_RATIO = 1.08f
        private const val MIN_LABEL_SP = 10f

        /**
         * One dot + percentage pair per camera, filled in CameraStore order so the
         * leftmost percentage is the same camera as the leftmost stripe on the Recording
         * Control field.
         *
         * Three slots, matching that field's stripe cap. On a half-width cell three
         * percentages plus the label is already tight; a fourth camera is dropped rather
         * than allowed to squeeze the label into an ellipsis.
         */
        private val CAMERA_SLOTS = listOf(
            CameraSlot(R.id.batteryPct1),
            CameraSlot(R.id.batteryPct2),
            CameraSlot(R.id.batteryPct3),
        )
    }

    /** One camera's percentage in the top row. It blinks while that camera is recording. */
    private data class CameraSlot(val percentId: Int)
}
