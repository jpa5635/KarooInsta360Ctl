package com.example.karooinsta360.camera

import android.os.SystemClock
import androidx.annotation.ColorRes
import com.example.karooinsta360.R

/**
 * A battery percentage as last reported by a camera, with the time it arrived.
 *
 * Deliberately not a bare `Int`. These readings arrive only when the camera pushes one
 * (see `Insta360ConnectionManager.handleBatteryPayload`), so "45%" and "45% as of twenty
 * minutes ago" are very different claims and only the second one is ever actually true.
 * Everything that displays a percentage asks for a *non-stale* reading and shows nothing
 * (or `--%`) rather than presenting an old number as current.
 */
data class BatteryReading(
    val percent: Int,
    val charging: Boolean,
    val atElapsedMs: Long = SystemClock.elapsedRealtime(),
) {
    val ageMs: Long get() = SystemClock.elapsedRealtime() - atElapsedMs

    val isStale: Boolean get() = ageMs > STALE_AFTER_MS

    val band: BatteryBand get() = BatteryBand.forPercent(percent)

    companion object {
        /**
         * How old a reading may be before it stops being shown at all.
         *
         * The Ace Pro 2's push cadence for 0x2003 is not yet known — it may be periodic or
         * it may only fire when the percentage actually changes. Fifteen minutes is chosen
         * to be generous enough that an on-change-only camera doesn't blank the field
         * during a slow-drain hour, while still being short enough that a number on screen
         * is worth trusting. Tighten it once a ride's logcat shows the real cadence.
         */
        const val STALE_AFTER_MS = 15 * 60 * 1000L
    }
}

/**
 * Six colour bands for a battery percentage.
 *
 * Boundaries are lower-inclusive reading downward: 100-80 [FULL], 79-60 [HIGH], 59-40
 * [MEDIUM], 39-20 [LOW], 19-10 [CRITICAL], 9-0 [DEPLETED]. So exactly 80 is [FULL] and
 * exactly 20 is [LOW].
 *
 * The yellow is pulled down from a true yellow on purpose: the light field background is
 * `#F2FFFFFF`, and `#FFEB3B` against it is close to invisible outdoors.
 */
enum class BatteryBand(val minPercent: Int, @ColorRes val fillColor: Int) {
    FULL(80, R.color.battery_full),
    HIGH(60, R.color.battery_high),
    MEDIUM(40, R.color.battery_medium),
    LOW(20, R.color.battery_low),
    CRITICAL(10, R.color.battery_critical),
    DEPLETED(0, R.color.battery_depleted),
    ;

    /**
     * True if this band is worse than [other]. Bands are declared best-first, so a higher
     * ordinal is a lower battery — see the latch in `Insta360ConnectionManager`.
     */
    fun isWorseThan(other: BatteryBand): Boolean = ordinal > other.ordinal

    companion object {
        fun forPercent(percent: Int): BatteryBand {
            val p = percent.coerceIn(0, 100)
            return values().first { p >= it.minPercent }
        }
    }
}
