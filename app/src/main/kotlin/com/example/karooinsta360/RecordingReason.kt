package com.example.karooinsta360

/**
 * **Added (2026-09-07)** — replaces the free-text `reason: String` that
 * [com.example.karooinsta360.connection.Insta360ConnectionManager.startCapture]/[stopCapture]
 * took before.
 *
 * That string was written for logcat and read well there, but it was the only description
 * of *why* a recording changed, and the [io.hammerhead.karooext.models.InRideAlert] raised
 * by [com.example.karooinsta360.extension.Insta360Extension] couldn't use it: "no latch
 * wants recording (speed stop: rawSpeed=1.8m/s < 4.0m/s (9.0mph))" is exactly what you
 * want in a log and exactly what you don't want on a bike computer at 40kph.
 *
 * So this carries both. [logText] is the same detail as before, unchanged, and still what
 * goes to logcat. [alertText] is the short rider-facing version — "Speed trigger",
 * "Started manually from Karoo" — that the alert shows. One value, two audiences, no risk
 * of the two drifting apart because they're derived from the same construction site.
 */
sealed class RecordingReason {

    /** Short line for the in-ride alert. Keep it readable at a glance, at speed. */
    abstract val alertText: String

    /** Full detail for logcat. Defaults to [alertText] where there's nothing more to say. */
    open val logText: String get() = alertText

    /**
     * Rider pressed something. [source] distinguishes which surface, since all three act
     * on every saved camera identically and otherwise look the same after the fact.
     */
    data class Manual(val source: Source) : RecordingReason() {
        enum class Source { KAROO_FIELD, BONUS_ACTION, CONFIG_SCREEN }

        override val alertText: String
            get() = when (source) {
                Source.KAROO_FIELD -> "Manually from Karoo field"
                Source.BONUS_ACTION -> "Manually from Karoo button"
                Source.CONFIG_SCREEN -> "Manually from Configure screen"
            }

        override val logText: String
            get() = when (source) {
                Source.KAROO_FIELD -> "manual (ride-page tile)"
                Source.BONUS_ACTION -> "manual (controller BonusAction)"
                Source.CONFIG_SCREEN -> "manual Start/Stop button (Configure screen)"
            }
    }

    /**
     * An automatic latch flipped. [latches] is the `wantingLatches` value the monitor
     * already computes ("effort", "speed+radar", "none"); [detail] is its `lastLatchEvent`
     * string, unchanged from what used to be embedded in the old reason string.
     *
     * The alert names the trigger type only. Which specific latch crossed which threshold
     * with what value stays in the log, where there's room for it.
     */
    data class Trigger(val latches: String, val detail: String) : RecordingReason() {
        override val alertText: String
            get() = when (latches) {
                // No longer expected in normal operation — Insta360Extension now passes
                // the *previous* tick's latches for a stop event specifically so this
                // never has to describe an already-emptied set. Kept as a graceful
                // fallback (empty, matching raiseRecordingAlert's "nothing to add" case)
                // rather than "Trigger released", which named nothing and stated the
                // obvious on a "Recording stopped" alert.
                "none" -> ""
                else -> latches.split("+").joinToString(" + ") { latchLabel(it) } + " trigger"
            }

        override val logText: String get() = "$latches ($detail)"

        private fun latchLabel(latch: String) = when (latch) {
            "hr" -> "HR"
            "power" -> "Power"
            // Defensive fallback only — Insta360Extension always reports "hr"/"power"
            // individually now rather than the combined "effort" label.
            "effort" -> "Effort"
            "speed" -> "Speed"
            "radar" -> "Radar"
            else -> latch.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * The safety net in [com.example.karooinsta360.AppSettings.getDataSourceLossTimeoutMinutes]
     * fired — a sensor went quiet long enough that its held-over last value was no longer
     * trustworthy. Worth surfacing to the rider distinctly: it means a strap or meter has
     * dropped out, which is actionable mid-ride in a way an ordinary threshold stop isn't.
     */
    data class DataSourceLost(val detail: String) : RecordingReason() {
        override val alertText: String get() = "Sensor lost — $detail"
        override val logText: String get() = "data source lost — $detail"
    }

    /**
     * **Added (2026-09-07)** with camera-side notification handling — the camera started
     * or stopped on its own and told us about it, rather than us commanding it. Covers the
     * physical shutter button and any paired Insta360 remote.
     */
    data object CameraSide : RecordingReason() {
        // Deliberately neutral about direction: this same value describes a camera-side
        // start and a camera-side stop, and the alert already says which it was in its
        // title. "Started on the camera" would read as a lie on a stop alert.
        override val alertText: String get() = "On the camera"
        override val logText: String get() = "camera-side action (BLE notification)"
    }

    /** Camera stopped itself for a reason it reported: card full, battery, shutdown. */
    data class CameraFault(val fault: Fault) : RecordingReason() {
        enum class Fault { STORAGE_FULL, BATTERY_LOW, SHUTDOWN, UNKNOWN }

        override val alertText: String
            get() = when (fault) {
                Fault.STORAGE_FULL -> "Camera stopped — card full"
                Fault.BATTERY_LOW -> "Camera stopped — battery low"
                Fault.SHUTDOWN -> "Camera shut down"
                Fault.UNKNOWN -> "Camera stopped on its own"
            }

        override val logText: String get() = "camera fault: ${fault.name}"
    }

    /** Fallback for call sites that genuinely have nothing to say. */
    data object Unspecified : RecordingReason() {
        override val alertText: String get() = ""
        override val logText: String get() = "unspecified"
    }
}
