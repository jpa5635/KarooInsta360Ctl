package com.example.karooinsta360

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Handles a tap on the "Recording Control" ride-page data field (see
 * [com.example.karooinsta360.extension.RecordingControlDataType]).
 *
 * Unlike [ControlCenterActionActivity], this deliberately never launches anything
 * visible — a `RemoteViews` click can target a broadcast just as well as an activity via
 * `PendingIntent.getBroadcast`, which is the better fit here since the tile lives
 * on-screen for the whole ride and shouldn't flash a window open every time it's tapped.
 * Toggles every saved camera together (starts all connected-but-idle ones if none are
 * currently recording, otherwise stops all currently-recording ones) — see
 * [Insta360ConnectionManager.toggleAllCameras].
 */
class RecordingToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Insta360ConnectionManager.ensureStarted(context)
        Insta360ConnectionManager.toggleAllCameras(context)
    }
}
