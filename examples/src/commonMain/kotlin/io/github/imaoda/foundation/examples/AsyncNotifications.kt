package io.github.imaoda.foundation.examples

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class AsyncRevisionCommitted(val revision: Int, val text: String)

/**
 * An application-specific logic object using SharedFlow directly, not a reusable event wrapper.
 *
 * Callers and collectors use one serialized execution context. The caller owns each change
 * operation; each observer owns and cancels its own collecting coroutine. This object creates
 * no scope and SharedFlow has no close operation. Its notifications never complete normally.
 */
internal class AsyncDocument {
    private val mutableRevisions = MutableSharedFlow<AsyncRevisionCommitted>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    val revisions: SharedFlow<AsyncRevisionCommitted> = mutableRevisions.asSharedFlow()

    var revision: Int = 0
        private set

    var text: String = ""
        private set

    /**
     * Commits a revision before announcing it to active observers.
     *
     * One buffered notification may await a slow observer. When that buffer is full, emit
     * suspends this operation without blocking its thread. It does not wait for all observer
     * business logic to finish. With no observers, replay=0 discards notifications even though
     * extra buffer capacity is positive. Cancellation thrown by emit propagates and does not
     * undo the commit.
     * Callers that need an acknowledgement or durable delivery require a different protocol.
     */
    suspend fun changeText(newText: String) {
        text = newText
        revision++
        mutableRevisions.emit(AsyncRevisionCommitted(revision, text))
    }
}
