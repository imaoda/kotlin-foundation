package io.github.imaoda.foundation.examples

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SessionPhase { Active, Stopping, Closed }

/**
 * An application-specific asynchronous owner, deliberately not an AutoCloseable.
 *
 * Entry points and the supplied coroutine context must use the same serialized execution
 * context. [shutdown] must run outside this session's owned jobs: an owned job cannot wait
 * for itself to finish. The external owner remains responsible for completing shutdown.
 */
internal class AsyncWorkSession(
    context: CoroutineContext,
    private val synchronousResource: AutoCloseable,
    onUnexpectedFailure: (operation: String, failure: Throwable) -> Unit,
) {
    private val tasks: Job = SupervisorJob(context[Job])
    private val scope: CoroutineScope = CoroutineScope(
        context + tasks + CoroutineExceptionHandler { failureContext, failure ->
            // Only unhandled independent background failures arrive here, once.
            onUnexpectedFailure(failureContext[CoroutineName]?.name ?: "background", failure)
        },
    )
    private val mutablePhase = MutableStateFlow(SessionPhase.Active)
    val phase: StateFlow<SessionPhase> = mutablePhase.asStateFlow()

    /** Independent work fails locally; its unhandled failure goes to the supplied callback. */
    fun launchIndependent(operation: String, work: suspend () -> Unit): Job {
        ensureAcceptingWork()
        return scope.launch(CoroutineName(operation)) { work() }
    }

    /**
     * The caller observes this operation's failure. Both parts must succeed together.
     * A supervisor isolates the operation from other session operations, not its own parts.
     * Even when the caller is cancelled, this method waits for the operation and its children
     * to finish before propagating cancellation. Uncooperative work or finalizers can delay
     * that completion barrier indefinitely; the operation is not left running after return.
     */
    suspend fun <A, B> runRelated(
        first: suspend () -> A,
        second: suspend () -> B,
    ): Pair<A, B> {
        ensureAcceptingWork()
        val operation = scope.async {
            coroutineScope {
                val left = async { first() }
                val right = async { second() }
                left.await() to right.await()
            }
        }
        try {
            return operation.await()
        } finally {
            // Awaiting an independently owned Deferred does not itself cancel that Deferred.
            // Stop its work and await finalizers even if the caller has already been cancelled.
            withContext(NonCancellable) { operation.cancelAndJoin() }
        }
    }

    /**
     * Stops admission, cancels and waits for owned tasks, then closes the synchronous resource.
     *
     * Cancellation of the caller is propagated. If cancellation interrupts the wait, phase
     * remains Stopping and resources remain open; a later active external caller must retry.
     * Once Closed, repeated calls do nothing. A close failure is thrown once, after entering
     * the terminal Closed state. Overlapping callers on the serialized context close once.
     */
    suspend fun shutdown() {
        currentCoroutineContext().ensureActive()
        if (mutablePhase.value == SessionPhase.Closed) return
        mutablePhase.value = SessionPhase.Stopping
        tasks.cancel()
        tasks.join()
        currentCoroutineContext().ensureActive()
        if (mutablePhase.value == SessionPhase.Closed) return
        // There is no suspension between terminal transition and synchronous release.
        mutablePhase.value = SessionPhase.Closed
        synchronousResource.close()
    }

    private fun ensureAcceptingWork() {
        check(mutablePhase.value == SessionPhase.Active && tasks.isActive) {
            "The session no longer accepts work"
        }
    }
}
