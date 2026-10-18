package io.github.imaoda.foundation.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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
        assertEquals(1 to 2, session.runRelated({ 1 }, { 2 }))
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
