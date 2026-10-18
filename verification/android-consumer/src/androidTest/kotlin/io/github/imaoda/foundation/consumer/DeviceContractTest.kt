package io.github.imaoda.foundation.consumer

import io.github.imaoda.foundation.events.EventEmitter
import io.github.imaoda.foundation.events.ListenerFailurePolicy
import io.github.imaoda.foundation.resources.CloseableGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceContractTest {
    @Test fun componentLifecycleRunsOnAndroid() {
        val session = ConsumerSession()
        session.increment()
        assertEquals(1, session.total)
        session.close()
        session.close()
        assertThrows(IllegalStateException::class.java) { session.increment() }
    }

    @Test fun cleanupPreservesBothFailuresOnAndroid() {
        val group = CloseableGroup()
        val first = IllegalStateException("last registered")
        val second = IllegalArgumentException("first registered")
        group.add(AutoCloseable { throw second })
        group.add(AutoCloseable { throw first })
        val thrown = assertThrows(IllegalStateException::class.java) { group.close() }
        assertSame(first, thrown)
        assertEquals(listOf(second), thrown.suppressedExceptions)
        group.close()
    }

    @Test fun subscriptionCancellationAppliesWithinCurrentEmission() {
        val emitter = EventEmitter<Unit>(ListenerFailurePolicy.FailFast)
        var pending: AutoCloseable? = null
        emitter.event.subscribe { pending?.close() }
        var called = false
        pending = emitter.event.subscribe { called = true }
        emitter.emit(Unit)
        assertEquals(false, called)
        emitter.close()
    }
}
