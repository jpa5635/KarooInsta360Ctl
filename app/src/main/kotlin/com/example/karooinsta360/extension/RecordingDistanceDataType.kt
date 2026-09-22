package com.example.karooinsta360.extension

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
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
        // at in this cell. See VALUE_SIZE_RATIO for why it is used unscaled.
        val valueTextSizeSp = config.textSize * VALUE_SIZE_RATIO

        // (2026-09-22) Everything about the header that doesn't change frame to frame.
        // The label's *nominal* size is what a native header would be; what it actually
        // renders at depends on how many cameras are connected, so that is worked out per
        // frame by fitHeader().
        val nominalLabelSp = (config.textSize * LABEL_SIZE_RATIO).coerceAtLeast(MIN_LABEL_SP)
        // Percentages keep the size they've had since 0.1.38. LABEL_SIZE_RATIO went up to
        // match native labels; the percentages aren't native anything, and at the old size
        // they already read clearly without taking more of the row.
        val nominalPctSp = (config.textSize * PCT_SIZE_RATIO).coerceAtLeast(MIN_LABEL_SP)
        val metrics = HeaderMetrics(context, config)
        val alignment = config.alignment
        // Label and value both follow the rider's alignment setting, as native fields do.
        val horizontal = when (alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        val labelGravity = horizontal or Gravity.BOTTOM
        val valueGravity = horizontal or Gravity.CENTER_VERTICAL
        // Percentages go on the edge the label isn't on. CENTER keeps them at the start,
        // and fitHeader() reserves the same width on the other side so the label stays
        // centred on the cell.
        val pctOnEnd = alignment == ViewConfig.Alignment.LEFT
        val pctRowGravity = (if (pctOnEnd) Gravity.END else Gravity.START) or Gravity.BOTTOM

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

        var lastHeader: HeaderSizes? = null
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
                // renders as a plain DISTANCE label at native size and alignment, i.e. as
                // an ordinary distance field.
                val cameras = Insta360ConnectionManager.getCameraStates(context)
                    .filter { it.connected }
                    .take(CAMERA_SLOTS.size)

                val visibleSlots = if (cameras.isEmpty() && config.preview) 1 else cameras.size
                val header = fitHeader(metrics, alignment, nominalLabelSp, nominalPctSp, visibleSlots)
                if (header != lastHeader) {
                    lastHeader = header
                    // Logged on change only. This is the line to read when the label looks
                    // the wrong size: it shows whether the width we were given (viewSize) is
                    // what the cell really is, and what the fit made of it.
                    Log.i(
                        TAG,
                        "header: viewSize=${config.viewSize} textSize=${config.textSize} " +
                            "density=${context.resources.displayMetrics.density} " +
                            "slots=$visibleSlots alignment=$alignment -> $header",
                    )
                }

                val views = RemoteViews(context.packageName, R.layout.view_recording_distance).apply {
                    setViewPadding(R.id.distanceHeader, 0, metrics.headerPadTopPx(header), 0, 0)
                    setViewPadding(R.id.distanceValue, 0, metrics.valueTopPx, 0, 0)
                    setInt(R.id.distanceLabel, "setGravity", labelGravity)
                    setInt(R.id.distancePctRow, "setGravity", pctRowGravity)
                    setInt(R.id.distanceValue, "setGravity", valueGravity)

                    // Just the field name, matching how Karoo labels its own fields. The
                    // unit is deliberately not shown: the rider set it in their profile
                    // and it doesn't change mid-ride, so repeating it in every frame is
                    // noise on a cell this small.
                    setTextViewText(R.id.distanceLabel, "DISTANCE")
                    setTextViewTextSize(R.id.distanceLabel, TypedValue.COMPLEX_UNIT_SP, header.labelSp)
                    setViewPadding(R.id.distanceLabel, header.labelInsetPx, 0, 0, 0)
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
                        setTextViewTextSize(slot.percentId, TypedValue.COMPLEX_UNIT_SP, header.pctSp)
                        // The gap to the label goes on the label's side of each percentage,
                        // so the outermost one still sits flush with the cell's edge.
                        if (pctOnEnd) {
                            setViewPadding(slot.percentId, metrics.pctGapPx, 0, 0, 0)
                        } else {
                            setViewPadding(slot.percentId, 0, 0, metrics.pctGapPx, 0)
                        }

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
     * Widths the header arithmetic needs, measured once per view rather than per frame.
     *
     * Every width is stored per sp of text size. Text width scales linearly with size, so
     * one measurement at a reference size answers "how wide at N sp" for any N, which is
     * what lets [fitHeader] solve for a size directly instead of trying sizes in a loop.
     *
     * The paints mirror the XML: sans-serif, textScaleX 0.8. They measure with a bold
     * face for the label and the normal face for the percentages. On Karoo 2 (API 27)
     * textFontWeight is ignored and the label renders regular, so measuring bold there
     * overestimates slightly, which errs toward a label that fits.
     */
    private class HeaderMetrics(context: Context, config: ViewConfig) {
        private val dm = context.resources.displayMetrics
        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
        private val pxPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm)

        /** Header row width: the cell less the root's 4dp start and end padding. */
        val availablePx: Float = config.viewSize.first - 2 * dp(4f)

        val labelPxPerSp: Float = widthPerSp(Typeface.create("sans-serif", Typeface.BOLD), "DISTANCE")

        /**
         * Measured on "100%", the widest a percentage can be, so the label's size depends
         * only on how many cameras are connected — not on their charge. Otherwise the label
         * would resize the moment a battery ticked down from 100 to 99.
         */
        val pctPxPerSp: Float = widthPerSp(Typeface.create("sans-serif", Typeface.NORMAL), "100%")

        val pctGapPx: Int = dp(PCT_GAP_DP).toInt()

        private val textSizePx = config.textSize * pxPerSp

        // Vertical font metrics, per px of text size. With includeFontPadding=false a
        // TextView's top is the font's ascent line and its height is ascent + descent, so
        // these are exactly what the layout does with them. Ascent and descent come from the
        // family, not the weight, so the one Paint serves both label and percentages.
        private val ascentPerPx: Float
        private val lineHeightPerPx: Float
        private val capTopPerPx: Float
        init {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = 100f
            }
            val fm = paint.fontMetrics
            ascentPerPx = -fm.ascent / 100f
            lineHeightPerPx = (fm.descent - fm.ascent) / 100f
            val bounds = android.graphics.Rect()
            paint.getTextBounds("H", 0, 1, bounds)
            // From the TextView's top (the ascent line) down to the top of a capital.
            capTopPerPx = ascentPerPx - (-bounds.top) / 100f
        }

        private fun spToPx(sp: Float) = sp * pxPerSp

        /**
         * Header top padding that puts the tops of the label's capitals [LABEL_CAP_TOP_RATIO]
         * of the cell's text size below the top of the view, where native labels sit.
         *
         * Placed by cap top rather than by padding alone so it holds for whatever size the
         * label is drawn at. The label is bottom-anchored in a header as tall as its taller
         * child, so when the percentages are the taller line the label sits lower inside the
         * header by the difference, and the padding takes that back off.
         */
        fun headerPadTopPx(sizes: HeaderSizes): Int {
            val labelPx = spToPx(sizes.labelSp)
            val pctPx = spToPx(sizes.pctSp)
            val headerHeight = maxOf(labelPx, pctPx) * lineHeightPerPx
            val labelTopInHeader = headerHeight - labelPx * lineHeightPerPx
            val target = dp(HEADER_BASE_DP) + LABEL_CAP_TOP_RATIO * textSizePx
            return (target - labelTopInHeader - labelPx * capTopPerPx).coerceAtLeast(0f).toInt()
        }

        /**
         * Where the value's box starts. Reproduces exactly the inset the value had when it
         * sat under a stacked 3dp + one-line header at [VALUE_INSET_LABEL_RATIO], the layout
         * whose value position was measured against stock fields, so that separating the
         * header from the value does not move the number at all.
         */
        val valueTopPx: Int = run {
            val legacyLabelPx = spToPx(
                (config.textSize * VALUE_INSET_LABEL_RATIO).coerceAtLeast(MIN_LABEL_SP),
            )
            (dp(HEADER_BASE_DP) + legacyLabelPx * lineHeightPerPx).toInt()
        }

        private fun widthPerSp(face: Typeface, text: String): Float {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                textScaleX = 0.8f
                textSize = 100f
            }
            return paint.measureText(text) / 100f * pxPerSp
        }
    }

    /**
     * [labelInsetPx] is start padding on the label, used only for CENTER when centring on
     * the whole cell would run into the percentages: the label is then centred in the space
     * beside them instead.
     */
    private data class HeaderSizes(val labelSp: Float, val pctSp: Float, val labelInsetPx: Int)

    /**
     * Sizes and places the label and percentages so they share the header row without
     * touching.
     *
     * The label keeps its native size whenever it physically fits beside the percentages.
     * It shrinks only when it does not, and only as far as it must — the label is the one
     * that yields because everyone knows what the field says, while the percentages are the
     * information the field adds. Once the label is down at [MIN_FITTED_LABEL_SP] the
     * percentages start shrinking too.
     *
     * CENTER (2026-09-22). The first cut of this header reserved the percentages' width on *both* sides so
     * the label could stay centred on the whole cell. That doubled the room one camera
     * cost, and on a half-width cell it took the label from native size to about two
     * thirds of it with a third of the header row sitting empty. Now the label stays
     * centred on the cell when that clears the percentages, and otherwise moves over to be
     * centred in the space beside them ([HeaderSizes.labelInsetPx]) at full size. Staying
     * big matters more than staying exactly centred: next to a native label, a small one
     * reads as wrong far more than a slightly offset one does.
     */
    private fun fitHeader(
        m: HeaderMetrics,
        alignment: ViewConfig.Alignment,
        nominalLabelSp: Float,
        nominalPctSp: Float,
        slots: Int,
    ): HeaderSizes {
        if (slots == 0) return HeaderSizes(nominalLabelSp, nominalPctSp, 0)

        val usable = m.availablePx * (1f - FIT_SAFETY)
        fun pctBlockPx(sp: Float) = slots * (sp * m.pctPxPerSp + m.pctGapPx)
        val block = pctBlockPx(nominalPctSp)

        val labelSp = ((usable - block) / m.labelPxPerSp).coerceAtMost(nominalLabelSp)
        if (labelSp >= MIN_FITTED_LABEL_SP) {
            // Only CENTER needs placing: LEFT/RIGHT already pin the label to the far edge.
            val inset = if (alignment == ViewConfig.Alignment.CENTER) {
                val halfLabel = labelSp * m.labelPxPerSp / 2f
                if (halfLabel <= usable / 2f - block) 0 else block.toInt()
            } else {
                0
            }
            return HeaderSizes(labelSp, nominalPctSp, inset)
        }

        // Label is at its floor; whatever it leaves over goes to the percentages.
        val leftForPct = usable - MIN_FITTED_LABEL_SP * m.labelPxPerSp - slots * m.pctGapPx
        val pctSp = (leftForPct / (slots * m.pctPxPerSp)).coerceIn(MIN_PCT_SP, nominalPctSp)
        val inset = if (alignment == ViewConfig.Alignment.CENTER) pctBlockPx(pctSp).toInt() else 0
        return HeaderSizes(MIN_FITTED_LABEL_SP, pctSp, inset)
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
        /**
         * The label's native size as a fraction of ViewConfig.textSize.
         *
         * 0.30 -> 0.34 (2026-09-22), measured. In a photo of a light-theme page, the
         * capitals of native labels (HR, AVG POWER) stood 30px tall and ours 26.5px, in the
         * same row of same-sized cells. Same family (IBM Plex Sans), so cap height scales
         * directly with size: 0.30 x 30 / 26.5 = 0.34.
         */
        private const val LABEL_SIZE_RATIO = 0.34f

        /**
         * The label ratio the value's position was calibrated under (see valueTopPx). Not a
         * label size any more — changing LABEL_SIZE_RATIO must not move the number, so the
         * old figure is kept here on its own.
         */
        private const val VALUE_INSET_LABEL_RATIO = 0.30f

        /** Battery percentages' size as a fraction of textSize; unchanged since 0.1.38. */
        private const val PCT_SIZE_RATIO = 0.30f

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
         * 1.0 -> 1.08 -> 1.05 -> 1.0, and this time measured rather than guessed.
         *
         * A photo of the ride page was compared glyph to glyph: the leading zero in SPEED
         * and in AVG SPEED both measured 116px tall, ours measured 121px, and the white
         * panels measured 844px against 848px at those two heights, so the camera angle
         * accounts for about 0.5% of it. That leaves the value 3.8% larger than the stock
         * fields at a ratio of 1.05 — which puts the matching ratio at 1.05/1.038 ≈ 1.01.
         *
         * So 1.0 it is, and the tidy interpretation is the right one: Karoo draws its value
         * at exactly the textSize it reports, and every value above 1.0 this constant has
         * held was compensating for the wrong typeface rather than for anything real.
         *
         * This is still the dial that clips first. If the tops or bottoms of digits are
         * ever cut off on a short cell, lower this and change nothing else.
         */
        private const val VALUE_SIZE_RATIO = 1.0f
        /** Floor for the label's native size, so it doesn't vanish in a small cell. */
        private const val MIN_LABEL_SP = 10f

        /**
         * How far the label may shrink below that to make room for camera percentages.
         * Lower than [MIN_LABEL_SP] on purpose: a slightly small label beside three
         * batteries is better than a truncated one.
         */
        private const val MIN_FITTED_LABEL_SP = 8f

        /** Floor for the percentages once the label has hit [MIN_FITTED_LABEL_SP]. */
        private const val MIN_PCT_SP = 8f

        /** Space between a percentage and its neighbour (or the label). */
        private const val PCT_GAP_DP = 6f

        /**
         * Headroom left unused when fitting the header, so rounding and small differences
         * between the measuring Paint and the rendered TextView can't make the label and a
         * percentage kiss.
         */
        private const val FIT_SAFETY = 0.04f

        /** Fixed part of the top inset, kept from the first cut of this header. */
        private const val HEADER_BASE_DP = 3f

        /**
         * Where the tops of the label's capitals sit below the top of the view, as a
         * fraction of ViewConfig.textSize (plus [HEADER_BASE_DP]). This is the dial for
         * "the label is too high or too low next to a native field".
         *
         * Measured (2026-09-22) from a photo of DISTANCE beside AVG POWER in the same
         * row, both cells' top borders level to within 2px. From border to cap top: ours
         * 22px, native 39px. The value's digits in the same photo stood 96px, which at
         * Relative's 0.70 digit height puts ViewConfig.textSize at ~137px — so native sits
         * 17/137 = 0.125 of textSize lower than the first cut drew it.
         *
         * The first cut put cap tops at 3dp + 0.30 x (ascent - cap height), about 0.098 of textSize
         * for IBM Plex's metrics; add 0.125 and this is 0.223.
         */
        private const val LABEL_CAP_TOP_RATIO = 0.223f

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
