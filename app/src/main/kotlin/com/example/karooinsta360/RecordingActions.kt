package com.example.karooinsta360

/**
 * Intent actions shared by the two "act on every saved camera at once" tap surfaces:
 *  - [ControlCenterActionActivity] (Karoo Control Center's Start/Stop notification button
 *    — karoo-ext's `SystemNotification.actionIntent` can only launch an activity by
 *    action string, with no extras, so START and STOP need two distinct actions).
 *  - [RecordingToggleReceiver] (the tappable "Recording Control" ride-page data field —
 *    a single toggle is enough there since tapping it always means "flip whatever it's
 *    currently doing").
 *
 * Kept in one place so the manifest's intent-filters, the components that declare them,
 * and the code that fires them can't drift out of sync with each other.
 */
object RecordingActions {
    const val ACTION_START_ALL = "com.example.karooinsta360.action.START_ALL"
    const val ACTION_STOP_ALL = "com.example.karooinsta360.action.STOP_ALL"
    const val ACTION_TOGGLE_ALL = "com.example.karooinsta360.action.TOGGLE_ALL"
}
