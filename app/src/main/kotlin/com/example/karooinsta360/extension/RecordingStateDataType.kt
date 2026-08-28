package com.example.karooinsta360.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState

/**
 * Simple non-graphical data field: streams `1.0` while [Insta360Extension] believes
 * the camera is recording, `0.0` otherwise. Add it to a ride page for a quick visual
 * confirmation that a BonusAction button press actually toggled the camera, since
 * there's no other in-ride feedback for that.
 */
class RecordingStateDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    @Volatile
    private var emitter: Emitter<StreamState>? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        this.emitter = emitter
        publish(lastKnownState)
        emitter.setCancellable {
            if (this.emitter === emitter) this.emitter = null
        }
    }

    /** Called by [Insta360Extension] whenever the recording state changes (or a stream starts). */
    fun publish(recording: Boolean) {
        lastKnownState = recording
        emitter?.onNext(
            StreamState.Streaming(
                DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to if (recording) 1.0 else 0.0)),
            ),
        )
    }

    companion object {
        const val TYPE_ID = "recording_state"

        @Volatile
        private var lastKnownState: Boolean = false
    }
}
