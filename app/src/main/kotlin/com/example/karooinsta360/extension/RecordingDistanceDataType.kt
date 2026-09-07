package com.example.karooinsta360.extension

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
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
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **Added (2026-09-07)** — ride distance that behaves exactly like Karoo's own Distance
 * field, plus a flashing red dot whenever any saved camera is recording.
 *
 * The point of this one is that it costs no extra page real estate: rather than spending
 * a cell on a dedicated recording indicator, it replaces the Distance field you were
 * already going to have on the page.
 *
 * ### Why this doesn't reimplement distance rendering
 *
 * It would be a mistake to draw the number ourselves. Matching Karoo's own typography,
 * unit handling (metric/imperial from the user profile), precision, and rescaling across
 * every cell size is a lot of fiddly work that then silently drifts out of match the next
 * time Hammerhead restyles their fields — leaving this as the one odd-looking field on the
 * page.
 *
 * karoo-ext has a purpose-built way around this, and it is exactly what
 * [UpdateGraphicConfig.formatDataTypeId] is for — per its own doc comment, it exists to
 * "overlay graphical elements on existing numeric data field treatment". So:
 *
 *  - [startStream] republishes the system's `TYPE_DISTANCE_ID` value under this field's
 *    own dataTypeId (the same transform karoo-ext's sample app does for its custom speed
 *    field), and
 *  - [startView] sends `formatDataTypeId = TYPE_DISTANCE_ID`, which tells Karoo OS to
 *    render that value using its stock distance treatment — right units, right precision,
 *    right font, header and all.
 *
 * Our own RemoteViews then only ever draw the dot. Everything that makes it look like a
 * native field is native.
 *
 * ### Flash rate
 *
 * One second on, one second off, and that is the floor rather than a design choice:
 * `ViewEmitter.updateView` drops any view emitted less than ~900ms after the previous one,
 * so a faster blink driven from here would simply be discarded. A genuinely fast flash
 * would mean hand-building a `ViewFlipper` with `autoStart`/`flipInterval` so the
 * animation runs inside the Karoo process without further emissions — worth doing only if
 * this proves too sedate in practice.
 *
 * Width and theme work the same way as [RecordingControlDataType]: one typeId adapting to
 * [ViewConfig.gridSize] rather than declared width variants, and the theme coming from
 * [AppSettings.isFieldThemeDark] since karoo-ext exposes no theme signal. Here the theme
 * only picks the dot's outline — light ring on dark, dark ring on light — because unlike
 * the control tile this field must not paint its own background: the numeric treatment
 * underneath is Karoo's, and covering it is the one thing that would break the illusion.
 */
class RecordingDistanceDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        // (2026-09-07) Logged because this is the load-bearing half of the field and its
        // failure mode is silent: if Karoo never subscribes here, or the system distance
        // stream never produces a value, formatDataTypeId below has nothing to format and
        // the field renders as a bare dot on an empty cell with no error anywhere.
        Log.i(TAG, "startStream: subscribing to ${DataType.Type.DISTANCE}")
        var logged = 0
        val job = CoroutineScope(Dispatchers.Default).launch {
            karooSystem.streamDataFlow(DataType.Type.DISTANCE).collect { state ->
                // First few of each run only — this fires at the stream's own rate and
                // would otherwise flood logcat for the whole ride.
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
                    // Idle/Searching/NotAvailable pass straight through, so this field
                    // shows the same "--" treatment the stock Distance field would before
                    // a ride starts.
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

        // Hand the numeric treatment back to Karoo. Without this the field renders as a
        // blank cell with only our dot in it.
        emitter.onNext(
            UpdateGraphicConfig(
                showHeader = true,
                formatDataTypeId = DataType.Type.DISTANCE,
            ),
        )
        Log.i(TAG, "startView: sent formatDataTypeId=${DataType.Type.DISTANCE}")

        // Put the dot opposite whichever side the rider aligned their data to, so it can
        // never sit on top of the digits. RemoteViews can't move a child, so the layout
        // holds one dot per side and we show the one we want (see view_recording_distance.xml).
        val dotId = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> R.id.recordingDistanceDotEnd
            ViewConfig.Alignment.CENTER, ViewConfig.Alignment.RIGHT -> R.id.recordingDistanceDotStart
        }
        val otherDotId = when (dotId) {
            R.id.recordingDistanceDotEnd -> R.id.recordingDistanceDotStart
            else -> R.id.recordingDistanceDotEnd
        }

        var job: Job? = null
        job = CoroutineScope(Dispatchers.Default).launch {
            var dotOn = true
            while (isActive) {
                // In page-editing preview there's no live camera state and a permanently
                // absent dot makes the field look identical to the stock Distance field,
                // which is exactly the question the rider is trying to answer while
                // choosing it. So show it steady there.
                val recording = config.preview || Insta360ConnectionManager.isAnyCameraRecording(context)
                val dark = AppSettings.isFieldThemeDark(context)
                val dotDrawable = if (dark) R.drawable.ic_rec_dot_on_dark else R.drawable.ic_rec_dot_on_light

                val views = RemoteViews(context.packageName, R.layout.view_recording_distance).apply {
                    setImageViewResource(dotId, dotDrawable)
                    setViewVisibility(
                        dotId,
                        if (recording && (dotOn || config.preview)) android.view.View.VISIBLE else android.view.View.INVISIBLE,
                    )
                    // INVISIBLE rather than GONE for the shown side so the dot blinks in
                    // place without the layout reflowing under it; the unused side is
                    // GONE since it never needs to occupy space at all.
                    setViewVisibility(otherDotId, android.view.View.GONE)
                }
                emitter.updateView(views)

                delay(BLINK_PERIOD_MS)
                dotOn = !dotOn
            }
        }

        emitter.setCancellable {
            Log.i(TAG, "stopView")
            job?.cancel()
        }
    }

    companion object {
        private const val TAG = "RecordingDistance"
        const val TYPE_ID = "recording_distance"

        /**
         * Matches ViewEmitter's own ~900ms drop threshold with a little headroom. Going
         * lower doesn't blink faster, it just discards frames.
         */
        const val BLINK_PERIOD_MS = 1_000L
    }
}
