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

        val dotId = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> R.id.recordingDistanceDotEnd
            ViewConfig.Alignment.CENTER, ViewConfig.Alignment.RIGHT -> R.id.recordingDistanceDotStart
        }
        val otherDotId = when (dotId) {
            R.id.recordingDistanceDotEnd -> R.id.recordingDistanceDotStart
            else -> R.id.recordingDistanceDotEnd
        }

        var renderJob: Job? = null
        renderJob = CoroutineScope(Dispatchers.Default).launch {
            var dotOn = true
            while (isActive) {
                val recording = config.preview || Insta360ConnectionManager.isAnyCameraRecording(context)
                val dark = AppSettings.isFieldThemeDark(context)
                val textColor = if (dark) R.color.field_dark_text else R.color.field_light_text
                val dotDrawable = if (dark) R.drawable.ic_rec_dot_on_dark else R.drawable.ic_rec_dot_on_light

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
                    setImageViewResource(dotId, dotDrawable)
                    // INVISIBLE rather than GONE on the active side so the dot blinks in
                    // place without the value shifting under it.
                    setViewVisibility(
                        dotId,
                        if (recording && (dotOn || config.preview)) View.VISIBLE else View.INVISIBLE,
                    )
                    setViewVisibility(otherDotId, View.GONE)
                }
                emitter.updateView(views)

                delay(BLINK_PERIOD_MS)
                dotOn = !dotOn
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
    private fun formatDistance(meters: Double?, imperial: Boolean): String {
        if (meters == null) return "--"
        val value = if (imperial) meters / METERS_PER_MILE else meters / 1000.0
        return "%.2f".format(value)
    }

    companion object {
        private const val TAG = "RecordingDistance"
        const val TYPE_ID = "recording_distance"
        const val BLINK_PERIOD_MS = 1_000L
        private const val METERS_PER_MILE = 1609.344
        private const val LABEL_SIZE_RATIO = 0.30f

        /** See where this is used: compensates for the label row our layout adds. */
        private const val VALUE_SIZE_RATIO = 0.88f
        private const val MIN_LABEL_SP = 10f
    }
}
