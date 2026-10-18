package io.github.imaoda.foundation.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
internal class AsyncWorkSessionTest {
    @Test
    fun shutdownStopsAdmissionAndWaitsForWorkersBeforeClosingResources() = runTest {
        val trace = mutableListOf<String>()
        val releaseWorker = CompletableDeferred<Unit>()
        val session = AsyncWorkSession(
            backgroundScope.coroutineContext,
            AutoCloseable { trace += "resource closed" },
        ) { _, failure -> throw failure }
        session.launchIndependent("worker") {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    releaseWorker.await()
                    trace += "worker finished"
                }
            }
        }
        runCurrent()

        val closing = launch { session.shutdown() }
        runCurrent()
        assertEquals(SessionPhase.Stopping, session.phase.value)
        assertTrue(trace.isEmpty())
        assertFalse(closing.isCompleted)
        assertFailsWith<IllegalStateException> { session.launchIndependent("too late") { } }

        releaseWorker.complete(Unit)
        closing.join()
        session.shutdown()
        assertEquals(listOf("worker finished", "resource closed"), trace)
        assertEquals(SessionPhase.Closed, session.phase.value)
    }

    @Test
    fun cancelledShutdownCallerLeavesResourcesOpenUntilAnActiveOwnerRetries() = runTest {
        var closed = 0
        var cancellationObserved = false
        val releaseWorker = CompletableDeferred<Unit>()
        val session = AsyncWorkSession(
            backgroundScope.coroutineContext,
            AutoCloseable { closed++ },
        ) { _, failure -> throw failure }
        session.launchIndependent("worker") {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { releaseWorker.await() }
            }
        }
        runCurrent()
        val interruptedOwner = launch {
            try {
                session.shutdown()
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                throw cancelled
            }
        }
        runCurrent()
        interruptedOwner.cancelAndJoin()
        assertTrue(cancellationObserved)
        assertEquals(SessionPhase.Stopping, session.phase.value)
        assertEquals(0, closed)

        releaseWorker.complete(Unit)
        runCurrent()
        assertEquals(0, closed) // Completing worker cleanup does not silently finish shutdown.
        session.shutdown()
        session.shutdown()
        assertEquals(1, closed)
        assertEquals(SessionPhase.Closed, session.phase.value)
    }

    @Test
    fun externalOwnerMayExplicitlyFinishShutdownInNonCancellableFinally() = runTest {
        val trace = mutableListOf<String>()
        val session = AsyncWorkSession(
            backgroundScope.coroutineContext,
            AutoCloseable { trace += "resource closed" },
        ) { _, failure -> throw failure }
        session.launchIndependent("worker") {
            try { awaitCancellation() } finally { trace += "worker finished" }
        }
        val externalOwner = launch {
            try {
                awaitCancellation()
            } finally {
                // The outer owner opts into waiting; shutdown itself does not hide cancellation.
                withContext(NonCancellable) { session.shutdown() }
            }
        }
        runCurrent()
        externalOwner.cancelAndJoin()
        assertTrue(externalOwner.isCancelled)
        assertEquals(listOf("worker finished", "resource closed"), trace)
    }

    @Test
    fun relatedWorkFailsTogetherAndItsCallerReceivesTheFailureCause() = runTest {
        val failure = IllegalStateException("encoding failed")
        val reports = mutableListOf<Throwable>()
        var siblingCancelled = false
        val siblingStarted = CompletableDeferred<Unit>()
        val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                _, error -> reports += error
        }

        val thrown = assertFailsWith<IllegalStateException> {
            session.runRelated(
                first = { siblingStarted.await(); throw failure },
                second = {
                    try {
                        siblingStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        siblingCancelled = true
                    }
                },
            )
        }
        // JVM coroutine stack-trace recovery may copy the exception and retain it as a cause.
        assertEquals(failure.message, thrown.message)
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
        assertTrue(siblingCancelled)
        assertTrue(reports.isEmpty()) // Observed failures are not also reported as unhandled.
        assertEquals(SessionPhase.Active, session.phase.value)
        session.shutdown()
    }

    @Test
    fun eitherRelatedPartTimingOutCancelsItsSiblingAndWaitsForCleanup() = runTest {
        for (timeoutFirst in listOf(true, false)) {
            val reports = mutableListOf<Throwable>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var siblingCancelled = false
            var observed: Throwable? = null
            val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                    _, failure -> reports += failure
            }
            val survivor = session.launchIndependent("unrelated") { awaitCancellation() }
            val timeout: suspend () -> Unit = { withTimeout(100) { awaitCancellation() } }
            val sibling: suspend () -> Unit = {
                try {
                    awaitCancellation()
                } finally {
                    siblingCancelled = true
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
            val caller = launch {
                try {
                    session.runRelated(
                        first = if (timeoutFirst) timeout else sibling,
                        second = if (timeoutFirst) sibling else timeout,
                    )
                } catch (failure: Throwable) {
                    observed = failure
                }
            }
            try {
                runCurrent()
                advanceTimeBy(100)
                runCurrent()

                assertTrue(siblingCancelled, "timeoutFirst=$timeoutFirst must cancel its sibling")
                assertFalse(caller.isCompleted) // The failure does not bypass the cleanup barrier.
                releaseCleanup.complete(Unit)
                runCurrent()
                assertTrue(caller.isCompleted)
                assertTrue(observed is TimeoutCancellationException)
                assertTrue(survivor.isActive)
                assertTrue(reports.isEmpty())
                assertEquals(SessionPhase.Active, session.phase.value)
            } finally {
                releaseCleanup.complete(Unit)
                caller.cancelAndJoin()
                session.shutdown()
            }
        }
    }

    @Test
    fun cancelledRelatedCallerReceivesCleanupFailureBeforeCancellation() = runTest {
        assertCancellationPreservesCleanupFailure(IllegalStateException("cleanup failed"))
    }

    @Test
    fun cancelledRelatedCallerReceivesFatalCleanupErrorBeforeCancellation() = runTest {
        assertCancellationPreservesCleanupFailure(AssertionError("fatal cleanup error"))
    }

    private suspend fun TestScope.assertCancellationPreservesCleanupFailure(failure: Throwable) {
        val reports = mutableListOf<Throwable>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cancellation = CancellationException("caller stopped")
        var cleanupStarted = false
        var observed: Throwable? = null
        val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                _, error -> reports += error
        }
        val survivor = session.launchIndependent("unrelated") { awaitCancellation() }
        val caller = launch {
            try {
                session.runRelated(
                    first = {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted = true
                                releaseCleanup.await()
                                throw failure
                            }
                        }
                    },
                    second = { awaitCancellation() },
                )
            } catch (error: Throwable) {
                observed = error
            }
        }
        try {
            runCurrent()
            caller.cancel(cancellation)
            runCurrent()
            assertTrue(cleanupStarted)
            assertFalse(caller.isCompleted)
            assertEquals(null, observed)

            releaseCleanup.complete(Unit)
            runCurrent()
            assertTrue(caller.isCompleted)
            val thrown = assertNotNull(observed)
            assertFalse(thrown is CancellationException)
            assertEquals(failure::class, thrown::class)
            assertTrue(thrown.containsReference(failure))
            assertTrue(thrown.containsReference(cancellation))
            assertAcyclicFailureGraph(thrown)
            assertTrue(survivor.isActive)
            assertTrue(reports.isEmpty())
            assertEquals(SessionPhase.Active, session.phase.value)
        } finally {
            releaseCleanup.complete(Unit)
            caller.cancelAndJoin()
            session.shutdown()
        }
    }

    @Test
    fun cleanupFailureRetainsCancellationWhenConsumedAcrossAnotherAwaitBoundary() = runTest {
        supervisorScope {
            val failure = IllegalStateException("cleanup failed before owner awaited")
            val cancellation = CancellationException("caller stopped")
            val releaseCleanup = CompletableDeferred<Unit>()
            val reports = mutableListOf<Throwable>()
            val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                    _, error -> reports += error
            }
            // No inner catch: the external owner consumes the failed caller through its Deferred.
            val caller = async {
                session.runRelated(
                    first = {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                releaseCleanup.await()
                                throw failure
                            }
                        }
                    },
                    second = { awaitCancellation() },
                )
            }
            try {
                runCurrent()
                caller.cancel(cancellation)
                runCurrent()
                assertFalse(caller.isCompleted)
                releaseCleanup.complete(Unit)
                runCurrent()
                assertTrue(caller.isCompleted)

                // JVM stack-trace recovery at this additional await must retain both causes.
                val thrown = assertFailsWith<IllegalStateException> { caller.await() }
                assertTrue(thrown.containsReference(failure))
                assertTrue(thrown.containsReference(cancellation))
                assertAcyclicFailureGraph(thrown)
                assertTrue(reports.isEmpty())
            } finally {
                releaseCleanup.complete(Unit)
                caller.cancelAndJoin()
                session.shutdown()
            }
        }
    }

    @Test
    fun callerCancellationDoesNotHideConcurrentBusinessFailure() = runTest {
        val failure = IllegalArgumentException("business failed")
        val cancellation = CancellationException("caller stopped before delivery")
        val reports = mutableListOf<Throwable>()
        val failOperation = CompletableDeferred<Unit>()
        var siblingCancelled = false
        var observed: Throwable? = null
        val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                _, error -> reports += error
        }
        val caller = launch {
            try {
                session.runRelated(
                    first = { failOperation.await(); throw failure },
                    second = {
                        try {
                            awaitCancellation()
                        } finally {
                            siblingCancelled = true
                        }
                    },
                )
            } catch (error: Throwable) {
                observed = error
            }
        }
        try {
            runCurrent()
            // Queue business failure first, then cancel its awaiting caller before either resumes.
            failOperation.complete(Unit)
            caller.cancel(cancellation)
            assertFalse(caller.isCompleted)
            assertEquals(null, observed)
            runCurrent()

            assertTrue(siblingCancelled)
            assertTrue(caller.isCompleted)
            val thrown = assertNotNull(observed)
            assertTrue(thrown is IllegalArgumentException)
            assertTrue(thrown.containsReference(failure))
            assertTrue(thrown.containsReference(cancellation))
            assertAcyclicFailureGraph(thrown)
            assertTrue(reports.isEmpty())
            assertEquals(SessionPhase.Active, session.phase.value)
        } finally {
            caller.cancelAndJoin()
            session.shutdown()
        }
    }

    @Test
    fun cancelledRelatedCallerWaitsForDelayedChildCleanupBeforeReturning() = runTest {
        val trace = mutableListOf<String>()
        val reports = mutableListOf<Throwable>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val session = AsyncWorkSession(
            backgroundScope.coroutineContext,
            AutoCloseable { trace += "resource closed" },
        ) { _, failure -> reports += failure }
        val caller = launch {
            try {
                session.runRelated(
                    first = {
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                trace += "child cleanup started"
                                releaseCleanup.await()
                                trace += "child cleanup finished"
                            }
                        }
                    },
                    second = {
                        secondStarted.complete(Unit)
                        awaitCancellation()
                    },
                )
            } catch (cancelled: CancellationException) {
                trace += "caller observed cancellation"
                throw cancelled
            } finally {
                trace += "caller finished"
            }
        }
        firstStarted.await()
        secondStarted.await()

        caller.cancel()
        runCurrent()
        assertTrue(caller.isCancelled)
        assertFalse(caller.isCompleted)
        assertEquals(listOf("child cleanup started"), trace)
        assertEquals(SessionPhase.Active, session.phase.value)

        releaseCleanup.complete(Unit)
        caller.join()
        assertEquals(
            listOf(
                "child cleanup started",
                "child cleanup finished",
                "caller observed cancellation",
                "caller finished",
            ),
            trace,
        )
        assertTrue(reports.isEmpty())
        session.shutdown()
        assertEquals("resource closed", trace.last())
    }

    @Test
    fun independentFailureIsReportedOnceWithoutCancellingOtherWork() = runTest {
        val failure = IllegalStateException("preview failed")
        val reports = mutableListOf<Pair<String, Throwable>>()
        val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { }) {
                name, error -> reports += name to error
        }
        val survivor = session.launchIndependent("index") { awaitCancellation() }
        val failed = session.launchIndependent("preview") { throw failure }
        runCurrent()

        assertTrue(failed.isCancelled)
        assertTrue(survivor.isActive)
        assertEquals(listOf<Pair<String, Throwable>>("preview" to failure), reports)
        assertEquals(1 to "two", session.runRelated({ 1 }, { "two" }))
        session.shutdown()
        assertEquals(listOf<Pair<String, Throwable>>("preview" to failure), reports) // Cancellation is not a fault.
    }

    @Test
    fun closeFailureLeavesTerminalStateAndIsNotRetried() = runTest {
        val failure = IllegalStateException("resource close failed")
        var attempts = 0
        val session = AsyncWorkSession(
            backgroundScope.coroutineContext,
            AutoCloseable { attempts++; throw failure },
        ) { _, error -> throw error }

        assertSame(failure, assertFailsWith<IllegalStateException> { session.shutdown() })
        assertEquals(SessionPhase.Closed, session.phase.value)
        session.shutdown()
        assertEquals(1, attempts)
    }

    private fun Throwable.containsReference(expected: Throwable): Boolean {
        val pending = mutableListOf(this)
        val visited = mutableListOf<Throwable>()
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            if (current === expected) return true
            if (visited.any { it === current }) continue
            visited += current
            current.cause?.let { pending += it }
            pending += current.suppressedExceptions
        }
        return false
    }

    private fun assertAcyclicFailureGraph(failure: Throwable, ancestors: List<Throwable> = emptyList()) {
        assertFalse(ancestors.any { it === failure }, "Exception cause/suppressed graph must be acyclic")
        val path = ancestors + failure
        failure.cause?.let { assertAcyclicFailureGraph(it, path) }
        failure.suppressedExceptions.forEach { assertAcyclicFailureGraph(it, path) }
    }

    @Test
    fun overlappingShutdownCallersOnOneDispatcherCloseOnlyOnce() = runTest {
        var closed = 0
        val releaseWorker = CompletableDeferred<Unit>()
        val session = AsyncWorkSession(backgroundScope.coroutineContext, AutoCloseable { closed++ }) {
                _, failure -> throw failure
        }
        session.launchIndependent("worker") {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { releaseWorker.await() }
            }
        }
        runCurrent()
        val first = launch { session.shutdown() }
        val second = launch { session.shutdown() }
        runCurrent()
        releaseWorker.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, closed)
    }
}
