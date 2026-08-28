package com.example.karooinsta360.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.karooinsta360.R
import com.example.karooinsta360.RecordingActions
import com.example.karooinsta360.RecordingToggleReceiver
import com.example.karooinsta360.connection.Insta360ConnectionManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig

/**
 * A graphical, *tappable* ride-page data field — drop it on any page and it shows
 * whether any saved camera is currently recording; tapping it fires
 * [Insta360ConnectionManager.toggleAllCameras] (start every connected-but-idle camera, or
 * stop every currently-recording one). This is a manual override that's actually visible
 * on-screen during a ride, unlike a controller-button BonusAction or the Control Center
 * notification (both act on all cameras the same all-or-nothing way, for the same
 * reason: none of these tap surfaces can offer a per-camera choice).
 *
 * No `startStream` override — this field is purely graphical (no numeric value to
 * stream), so the default no-op from [DataTypeImpl] is fine.
 */
class RecordingControlDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        fun render() {
            val recording = Insta360ConnectionManager.isAnyCameraRecording(context)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RecordingToggleReceiver::class.java).setAction(RecordingActions.ACTION_TOGGLE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.view_recording_control).apply {
                setTextViewText(
                    R.id.recordingControlText,
                    if (recording) "● REC\nTap to Stop" else "Tap to\nStart",
                )
                setOnClickPendingIntent(R.id.recordingControlRoot, pendingIntent)
            }
            // Rate-limited to ~1Hz by ViewEmitter itself — a render() call that lands
            // less than ~900ms after the previous one is silently dropped, which is fine
            // here since the next onCameraStateChanged (or this data field's own next
            // render) will catch the view up shortly after.
            emitter.updateView(views)
        }

        render()
        val listener = object : Insta360ConnectionManager.Listener {
            override fun onCameraStateChanged(address: String) = render()
        }
        Insta360ConnectionManager.addListener(listener)
        emitter.setCancellable {
            Insta360ConnectionManager.removeListener(listener)
        }
    }

    companion object {
        const val TYPE_ID = "recording_control"
    }
}
