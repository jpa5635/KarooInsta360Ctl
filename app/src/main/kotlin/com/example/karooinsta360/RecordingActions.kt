package com.example.karooinsta360

/**
 * Intent action for [RecordingToggleReceiver] (the tappable "Recording Control" ride-page
 * data field) — a single toggle is enough there since tapping it always means "flip
 * whatever it's currently doing."
 *
 * Kept in its own object (rather than inlined as a string literal) so the manifest's
 * `<receiver>` declaration, [RecordingToggleReceiver] itself, and the code that fires it
 * (`com.example.karooinsta360.extension.RecordingControlDataType`) can't drift out of sync.
 *
 * **Removed (2026-08-29):** `ACTION_START_ALL`/`ACTION_STOP_ALL`, used only by the
 * now-removed `ControlCenterActionActivity` trampoline for the Karoo Control Center
 * Start/Stop notification — see the README for why that surface was dropped (Control
 * Center is hidden for the whole duration of a ride, making it useless in-ride).
 */
object RecordingActions {
    const val ACTION_TOGGLE_ALL = "com.example.karooinsta360.action.TOGGLE_ALL"
}
