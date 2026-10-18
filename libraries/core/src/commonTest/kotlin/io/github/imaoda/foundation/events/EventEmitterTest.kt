package io.github.imaoda.foundation.events

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EventEmitterTest {
    @Test
    fun notificationIsSynchronousOrderedAndDoesNotReplay() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        emitter.emit(0)
        emitter.event.subscribe { calls += "first:$it" }
        emitter.event.subscribe { calls += "second:$it" }

        emitter.emit(1)
        calls += "returned"

        assertEquals(listOf("first:1", "second:1", "returned"), calls)
        emitter.close()
    }

    @Test
    fun repeatedRegistrationCreatesIndependentIdempotentHandles() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<Int>()
        val listener: (Int) -> Unit = { calls += it }
        val first = emitter.event.subscribe(listener)
        val second = emitter.event.subscribe(listener)
        emitter.emit(1)

        first.close()
        first.close()
        emitter.emit(2)
        second.close()
        emitter.emit(3)

        assertEquals(listOf(1, 1, 2), calls)
        emitter.close()
    }

    @Test
    fun removalSkipsAnUpcomingListenerAndAdditionWaitsForNextSnapshot() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        lateinit var upcoming: AutoCloseable
        emitter.event.subscribe { value ->
            calls += "first:$value"
            upcoming.close()
            if (value == 1) emitter.event.subscribe { calls += "added:$it" }
        }
        upcoming = emitter.event.subscribe { calls += "removed:$it" }
        emitter.event.subscribe { calls += "last:$it" }

        emitter.emit(1)
        emitter.emit(2)

        assertEquals(listOf("first:1", "last:1", "first:2", "last:2", "added:2"), calls)
        emitter.close()
    }

    @Test
    fun reentrantNotificationIsDepthFirstAndSeesNewSubscriptions() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        emitter.event.subscribe { value ->
            calls += "first:$value"
            if (value == 1) {
                emitter.event.subscribe { calls += "added:$it" }
                emitter.emit(2)
                calls += "nested returned"
            }
        }
        emitter.event.subscribe { calls += "second:$it" }

        emitter.emit(1)

        assertEquals(
            listOf("first:1", "first:2", "second:2", "added:2", "nested returned", "second:1"),
            calls,
        )
        emitter.close()
    }

    @Test
    fun selfRemovalAffectsNestedAndLaterNotification() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        lateinit var self: AutoCloseable
        self = emitter.event.subscribe {
            calls += "self:$it"
            self.close()
            emitter.emit(2)
            calls += "self finished"
        }
        emitter.event.subscribe { calls += "other:$it" }

        emitter.emit(1)
        emitter.emit(3)

        assertEquals(listOf("self:1", "other:2", "self finished", "other:1", "other:3"), calls)
        emitter.close()
    }

    @Test
    fun closeDuringNotificationLetsCurrentCallbackFinishAndSkipsRemaining() {
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        val first = emitter.event.subscribe {
            calls += "started"
            emitter.close()
            calls += "finished"
        }
        val second = emitter.event.subscribe { calls += "should not run" }

        emitter.emit(Unit)
        emitter.close()
        first.close()
        second.close()

        assertEquals(listOf("started", "finished"), calls)
        assertFailsWith<IllegalStateException> { emitter.emit(Unit) }
        assertFailsWith<IllegalStateException> { emitter.event.subscribe {} }
    }

    @Test
    fun closingSubscriptionDoesNotCloseItsBusinessObject() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val observer = Observer()
        val subscription = emitter.event.subscribe(observer::onValue)

        subscription.close()
        emitter.close()

        assertEquals(0, observer.closeCount)
        observer.close()
        assertEquals(1, observer.closeCount)
    }

    @Test
    fun failFastPropagatesOriginalFailureAndLeavesEmitterOpen() {
        val emitter = EventEmitter<Int>(ListenerFailurePolicy.FailFast)
        val calls = mutableListOf<String>()
        val failure = IllegalArgumentException("listener failed")
        val failing = emitter.event.subscribe { throw failure }
        emitter.event.subscribe { calls += "other:$it" }

        assertSame(failure, assertFailsWith<IllegalArgumentException> { emitter.emit(1) })
        assertTrue(calls.isEmpty())
        failing.close()
        emitter.emit(2)

        assertEquals(listOf("other:2"), calls)
        emitter.close()
    }

    @Test
    fun reportAndContinueReportsInPlaceAndKeepsTheSubscription() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val failure = IllegalArgumentException("listener failed")
        val emitter = EventEmitter<Int>(
            ListenerFailurePolicy.ReportAndContinue {
                failures += it
                calls += "reported"
            },
        )
        emitter.event.subscribe {
            calls += "failed:$it"
            throw failure
        }
        emitter.event.subscribe { calls += "other:$it" }

        emitter.emit(1)
        emitter.emit(2)

        assertEquals(listOf("failed:1", "reported", "other:1", "failed:2", "reported", "other:2"), calls)
        assertEquals(listOf<Throwable>(failure, failure), failures)
        emitter.close()
    }

    @Test
    fun cancellationIsNeverReportedOrSwallowed() {
        val reported = mutableListOf<Throwable>()
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.ReportAndContinue(reported::add))
        val cancellation = CancellationException("cancel operation")
        var laterRan = false
        emitter.event.subscribe { throw cancellation }
        emitter.event.subscribe { laterRan = true }

        assertSame(cancellation, assertFailsWith<CancellationException> { emitter.emit(Unit) })
        assertTrue(reported.isEmpty())
        assertEquals(false, laterRan)
        emitter.close()
    }

    @Test
    fun errorsAreNeverReportedOrSwallowed() {
        val reported = mutableListOf<Throwable>()
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.ReportAndContinue(reported::add))
        val error = Error("unrecoverable")
        var laterRan = false
        emitter.event.subscribe { throw error }
        emitter.event.subscribe { laterRan = true }

        assertSame(error, assertFailsWith<Error> { emitter.emit(Unit) })
        assertTrue(reported.isEmpty())
        assertEquals(false, laterRan)
        emitter.close()
    }

    @Test
    fun nonExceptionThrowablesAreNeverReportedOrSwallowed() {
        val reported = mutableListOf<Throwable>()
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.ReportAndContinue(reported::add))
        val failure = Throwable("not an Exception")
        emitter.event.subscribe { throw failure }

        assertSame(failure, assertFailsWith<Throwable> { emitter.emit(Unit) })
        assertTrue(reported.isEmpty())
        emitter.close()
    }

    @Test
    fun reporterFailurePropagatesOnceWithOriginalListenerFailureSuppressed() {
        val failure = IllegalArgumentException("listener failed")
        val reportingFailure = IllegalStateException("reporter failed")
        var reportCount = 0
        var laterRan = false
        val emitter = EventEmitter<Unit>(
            ListenerFailurePolicy.ReportAndContinue {
                assertSame(failure, it)
                reportCount++
                throw reportingFailure
            },
        )
        emitter.event.subscribe { throw failure }
        emitter.event.subscribe { laterRan = true }

        assertSame(reportingFailure, assertFailsWith<IllegalStateException> { emitter.emit(Unit) })
        assertEquals(listOf(failure), reportingFailure.suppressedExceptions)
        assertEquals(1, reportCount)
        assertEquals(false, laterRan)
        emitter.close()
    }

    @Test
    fun reporterRethrowingOriginalFailureDoesNotSelfSuppress() {
        val failure = IllegalArgumentException("listener failed")
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.ReportAndContinue { throw it })
        emitter.event.subscribe { throw failure }

        assertSame(failure, assertFailsWith<IllegalArgumentException> { emitter.emit(Unit) })
        assertTrue(failure.suppressedExceptions.isEmpty())
        emitter.close()
    }

    @Test
    fun nestedReporterFailureIsNotReportedAgainWhileUnwinding() {
        val listenerFailure = IllegalArgumentException("nested listener failed")
        val reportingFailure = IllegalStateException("reporter failed")
        val reported = mutableListOf<Throwable>()
        val emitter = EventEmitter<Int>(
            ListenerFailurePolicy.ReportAndContinue {
                reported += it
                throw reportingFailure
            },
        )
        var laterRan = false
        emitter.event.subscribe {
            if (it == 1) emitter.emit(2) else throw listenerFailure
        }
        emitter.event.subscribe { laterRan = true }

        assertSame(reportingFailure, assertFailsWith<IllegalStateException> { emitter.emit(1) })
        assertEquals(listOf<Throwable>(listenerFailure), reported)
        assertEquals(listOf(listenerFailure), reportingFailure.suppressedExceptions)
        assertEquals(false, laterRan)
        emitter.close()
    }

    @Test
    fun earlierReporterFailureCanBeReportedAsANewListenerFailureInALaterEmission() {
        val listenerFailure = IllegalArgumentException("listener failed")
        val reportingFailure = IllegalStateException("reporter failed")
        val reported = mutableListOf<Throwable>()
        val emitter = EventEmitter<Int>(
            ListenerFailurePolicy.ReportAndContinue {
                reported += it
                if (it === listenerFailure) throw reportingFailure
            },
        )
        emitter.event.subscribe {
            if (it == 1) throw listenerFailure else throw reportingFailure
        }

        assertSame(reportingFailure, assertFailsWith<IllegalStateException> { emitter.emit(1) })
        emitter.emit(2)

        assertEquals(listOf<Throwable>(listenerFailure, reportingFailure), reported)
        emitter.close()
    }

    @Test
    fun reporterCanCloseEmitterAndSkipRemainingListeners() {
        lateinit var emitter: EventEmitter<Unit>
        var laterRan = false
        emitter = EventEmitter(ListenerFailurePolicy.ReportAndContinue { emitter.close() })
        emitter.event.subscribe { throw IllegalArgumentException("listener failed") }
        emitter.event.subscribe { laterRan = true }

        emitter.emit(Unit)

        assertEquals(false, laterRan)
        assertFailsWith<IllegalStateException> { emitter.emit(Unit) }
    }

    private class Observer : AutoCloseable {
        var closeCount = 0
        fun onValue(value: Int) {
            check(value >= 0)
        }
        override fun close() {
            closeCount++
        }
    }
}
