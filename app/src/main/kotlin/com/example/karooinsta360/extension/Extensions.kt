package com.example.karooinsta360.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Same small wrappers karoo-ext's own sample app uses (neither is part of the published
 * library) to turn a data type subscription or a [KarooEvent] subscription into a cold
 * [Flow].
 */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

/**
 * Added (2026-08-29) for [Insta360Extension]'s `RideState` collector — the sample app
 * has this too, but nothing here needed it until the Control Center ride-disappearing
 * fix, so it was never copied over alongside [streamDataFlow].
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySend(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}
