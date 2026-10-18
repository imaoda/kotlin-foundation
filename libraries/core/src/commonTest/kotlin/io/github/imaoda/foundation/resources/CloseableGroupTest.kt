package io.github.imaoda.foundation.resources

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloseableGroupTest {
    @Test
    fun closesInReverseOrderAndEntersTerminalStateBeforeCallbacks() {
        val group = CloseableGroup()
        val closed = mutableListOf<Int>()
        repeat(3) { index ->
            group.add(AutoCloseable {
                assertTrue(group.isClosed)
                closed.add(index)
                group.close()
            })
        }

        group.close()
        group.close()

        assertEquals(listOf(2, 1, 0), closed)
    }

    @Test
    fun attemptsAllCleanupAndPreservesOriginalFailureAndSuppressedFailures() {
        val group = CloseableGroup()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val closed = mutableListOf<Int>()
        group.add(AutoCloseable { closed.add(0) })
        group.add(AutoCloseable { closed.add(1); throw second })
        group.add(AutoCloseable { closed.add(2); throw first })

        assertSame(first, assertFailsWith<IllegalStateException> { group.close() })
        assertEquals(listOf(second), first.suppressedExceptions)
        assertEquals(listOf(2, 1, 0), closed)
        group.close()
    }

    @Test
    fun cancellationAndFatalFailuresAreNeverConvertedToSuccess() {
        for (failure in listOf(CancellationException("cancel"), Error("fatal marker"))) {
            val group = CloseableGroup()
            var siblingClosed = false
            group.add(AutoCloseable { siblingClosed = true })
            group.add(AutoCloseable { throw failure })

            assertSame(failure, assertFailsWith<Throwable> { group.close() })
            assertTrue(siblingClosed)
            assertTrue(group.isClosed)
        }
    }

    @Test
    fun repeatedFailureInstanceIsNotSuppressedOntoItself() {
        val group = CloseableGroup()
        val failure = IllegalStateException("shared exception")
        repeat(2) { group.add(AutoCloseable { throw failure }) }

        assertSame(failure, assertFailsWith<IllegalStateException> { group.close() })
        assertTrue(failure.suppressedExceptions.isEmpty())
    }

    @Test
    fun repeatedSuppressedFailuresAreDeduplicatedByIdentityInFirstOccurrenceOrder() {
        class EqualFailure(message: String) : IllegalStateException(message) {
            override fun equals(other: Any?): Boolean = other is EqualFailure
            override fun hashCode(): Int = 0
        }
        val group = CloseableGroup()
        val first = EqualFailure("first")
        val repeated = EqualFailure("repeated")
        val equalButDistinct = EqualFailure("equal but distinct")
        val failures = listOf(first, repeated, repeated, equalButDistinct, repeated)
        val closed = mutableListOf<Int>()
        for ((index, failure) in failures.withIndex().reversed()) {
            group.add(AutoCloseable { closed.add(index); throw failure })
        }

        assertSame(first, assertFailsWith<IllegalStateException> { group.close() })

        assertEquals(listOf(0, 1, 2, 3, 4), closed)
        assertEquals(2, first.suppressedExceptions.size)
        assertSame(repeated, first.suppressedExceptions[0])
        assertSame(equalButDistinct, first.suppressedExceptions[1])
    }

    @Test
    fun terminalRegistrationClosesNewResourceAndRejectsIt() {
        val group = CloseableGroup()
        group.close()
        var closed = 0

        assertFailsWith<IllegalStateException> { group.add(AutoCloseable { closed++ }) }

        assertEquals(1, closed)
    }

    @Test
    fun failedTerminalRegistrationPreservesCleanupFailureInsteadOfMaskingIt() {
        val group = CloseableGroup()
        group.close()
        val cancellation = CancellationException("cleanup cancellation")

        assertSame(cancellation, assertFailsWith<CancellationException> {
            group.add(AutoCloseable { throw cancellation })
        })

        assertEquals(1, cancellation.suppressedExceptions.size)
        assertTrue(cancellation.suppressedExceptions.single() is IllegalStateException)
    }

    @Test
    fun duplicateIdentityIsRejectedButEqualDistinctResourcesAreAllowed() {
        val group = CloseableGroup()
        var closed = 0
        class EqualResource : AutoCloseable {
            override fun close() { closed++ }
            override fun equals(other: Any?): Boolean = other is EqualResource
            override fun hashCode(): Int = 0
        }
        val one = EqualResource()
        val two = EqualResource()
        assertSame(one, group.add(one))
        assertFailsWith<IllegalArgumentException> { group.add(one) }
        group.add(two)
        assertEquals(0, closed)

        group.close()

        assertEquals(2, closed)
    }

    @Test
    fun selfOwnershipIsRejectedWithoutClosingTheGroup() {
        val group = CloseableGroup()

        assertFailsWith<IllegalArgumentException> { group.add(group) }

        assertFalse(group.isClosed)
    }

    @Test
    fun earlyRemovalDetachesBeforeCleanupAndDoesNotRetainRegistrationAfterFailure() {
        val group = CloseableGroup()
        var count = 0
        val failure = IllegalStateException("cleanup")
        val resource = AutoCloseable {
            count++
            group.close()
            throw failure
        }
        group.add(resource)

        assertSame(failure, assertFailsWith<IllegalStateException> {
            group.removeAndClose(resource)
        })

        assertFalse(group.removeAndClose(resource))
        group.close()
        assertEquals(1, count)
    }

    @Test
    fun earlyRemovalKeepsGroupOpenAndAllowsDifferentResources() {
        val group = CloseableGroup()
        var count = 0
        val resource = group.add(AutoCloseable { count++ })

        assertTrue(group.removeAndClose(resource))
        assertFalse(group.removeAndClose(resource))
        assertFalse(group.isClosed)
        group.add(AutoCloseable { count++ })
        group.close()

        assertEquals(2, count)
    }

    @Test
    fun closingBatchCannotBeRegisteredAgainBeforeOrAfterItsTurn() {
        val group = CloseableGroup()
        var firstCount = 0
        var secondCount = 0
        lateinit var first: AutoCloseable
        lateinit var second: AutoCloseable
        first = AutoCloseable {
            firstCount++
            assertFailsWith<IllegalStateException> { group.add(second) }
        }
        second = AutoCloseable {
            secondCount++
            assertFailsWith<IllegalStateException> { group.add(first) }
            assertFailsWith<IllegalStateException> { group.add(second) }
        }
        group.add(first)
        group.add(second)

        group.close()

        assertEquals(1, firstCount)
        assertEquals(1, secondCount)
    }

    @Test
    fun earlyRemovalCannotReacquireItsOwnResourceDuringCleanup() {
        val group = CloseableGroup()
        lateinit var resource: AutoCloseable
        var count = 0
        resource = AutoCloseable {
            count++
            assertFailsWith<IllegalArgumentException> { group.add(resource) }
        }
        group.add(resource)

        group.removeAndClose(resource)
        group.close()

        assertEquals(1, count)
    }

    @Test
    fun terminalRejectionDoesNotRecursivelyCloseTheRejectedResource() {
        val group = CloseableGroup()
        group.close()
        lateinit var resource: AutoCloseable
        var count = 0
        resource = AutoCloseable {
            count++
            assertFailsWith<IllegalStateException> { group.add(resource) }
        }

        assertFailsWith<IllegalStateException> { group.add(resource) }

        assertEquals(1, count)
    }
}
