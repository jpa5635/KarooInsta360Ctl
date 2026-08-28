package com.example.karooinsta360

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Invisible trampoline activity for the Start/Stop button on the Karoo Control Center
 * notification (see [com.example.karooinsta360.extension.Insta360Extension]'s
 * `updateControlCenterNotification`).
 *
 * karoo-ext's `SystemNotification.actionIntent` can only name an intent *action* to
 * launch an activity with — there's no way for that click to call back into the
 * extension directly, and no way to pass extras. So this activity exists purely to
 * receive that click (matched by [RecordingActions.ACTION_START_ALL]/`STOP_ALL` in its
 * manifest intent-filter), perform the actual start/stop against every saved camera via
 * [Insta360ConnectionManager], and close itself immediately — its manifest theme
 * (`Theme.Transparent.NoDisplay`) means it never actually draws a visible window, so a
 * tap in Control Center behaves like a single in-place action rather than "opens the
 * app."
 *
 * Deliberately "all saved cameras together," not one specific camera — a Control Center
 * notification button has no way to offer a per-camera choice. Same all-or-nothing
 * design as the tappable ride-page tile ([com.example.karooinsta360.RecordingToggleReceiver]).
 */
class ControlCenterActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Insta360ConnectionManager.ensureStarted(this)
        when (intent?.action) {
            RecordingActions.ACTION_START_ALL -> {
                Insta360ConnectionManager.startAllCameras(this)
                Toast.makeText(this, "Starting all cameras", Toast.LENGTH_SHORT).show()
            }
            RecordingActions.ACTION_STOP_ALL -> {
                Insta360ConnectionManager.stopAllCameras(this)
                Toast.makeText(this, "Stopping all cameras", Toast.LENGTH_SHORT).show()
            }
            else -> Log.w(TAG, "Launched with unexpected action: ${intent?.action}")
        }
        finish()
        overridePendingTransition(0, 0) // skip the (still brief, even translucent) activity-switch animation
    }

    companion object {
        private const val TAG = "ControlCenterAction"
    }
}
