package io.github.imaoda.foundation.examples

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
     * The caller observes this operation's failure. Both parts must succeed together;
     * a failure or local cancellation of either part cancels the other part.
     * A supervisor isolates the operation from other session operations, not its own parts.
     * A caller already cancelled on entry starts no work.
     * Even when the caller is cancelled, this method waits for the operation and its children
     * to finish. A final non-cancellation failure takes precedence over caller cancellation;
     * cancellation is retained in the failure's cause/suppressed graph unless that would create a cycle.
     * Otherwise the original cancellation propagates, even if the operation completed normally.
     * Uncooperative work or finalizers can delay that completion barrier indefinitely; the
     * operation is not left running after return. Observed failures are not also reported as
     * independent background failures.
     */
    suspend fun <A, B> runRelated(
        first: suspend () -> A,
        second: suspend () -> B,
    ): Pair<A, B> {
        currentCoroutineContext().ensureActive()
        ensureAcceptingWork()
        val operation = scope.async {
            coroutineScope {
                val left = async { first() }
                val right = async { second() }
                // Local cancellation does not fail the parent: observe both parts immediately.
                awaitAll(left, right)
                left.await() to right.await()
            }
        }
        var cancellation: CancellationException? = null
        try {
            val result = operation.await()
            // An already completed Deferred can return without checking caller cancellation.
            currentCoroutineContext().ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        } catch (failure: Throwable) {
            // A completed Deferred can throw its failure before checking caller cancellation.
            // Preserve that cancellation without replacing the operation's actual failure.
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                failure.retainCancellation(cancelled)
            }
            throw failure
        } finally {
            // Awaiting an independently owned Deferred does not itself cancel that Deferred.
            // Stop its work and await finalizers even if the caller has already been cancelled.
            withContext(NonCancellable) { operation.cancelAndJoin() }
            cancellation?.let { cancelled ->
                // The job is complete, so this handler reads its final cause immediately.
                // Joining alone cannot expose a work/cleanup failure hidden by a cancelled await.
                var completionFailure: Throwable? = null
                operation.invokeOnCompletion { completionFailure = it }
                val failure = completionFailure
                if (failure != null && failure !is CancellationException) {
                    failure.retainCancellation(cancelled)
                    throw failure
                }
            }
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

private fun Throwable.retainCancellation(cancellation: CancellationException) {
    if (containsFailure(cancellation)) return
    // JVM coroutine recovery replaces same-type wrappers at later suspension boundaries. Attach
    // diagnostics to their deepest matching cause; keep the exception we propagate unchanged.
    var target = this
    val visited = mutableListOf<Throwable>()
    while (true) {
        visited.add(target)
        val cause = target.cause ?: break
        if (cause::class != target::class || visited.any { it === cause }) break
        target = cause
    }
    if (!cancellation.containsFailure(target)) target.addSuppressed(cancellation)
}

// Exceptions can already reference each other through causes or suppressed failures, including
// JVM stack-trace recovery copies. Preserve those links without adding duplicates or a new cycle.
private fun Throwable.containsFailure(target: Throwable): Boolean {
    val pending = mutableListOf(this)
    val visited = mutableListOf<Throwable>()
    while (pending.isNotEmpty()) {
        val failure = pending.removeAt(pending.lastIndex)
        if (failure === target) return true
        if (visited.any { it === failure }) continue
        visited.add(failure)
        failure.cause?.let(pending::add)
        pending.addAll(failure.suppressedExceptions)
    }
    return false
}
