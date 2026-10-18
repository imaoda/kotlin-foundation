package io.github.imaoda.foundation.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloseableSlotTest {
    @Test
    fun replacementSameInstanceClearAndCloseReleaseEachValueOnce() {
        val slot = CloseableSlot<AutoCloseable>()
        val closed = mutableListOf<Int>()
        val first = AutoCloseable { closed.add(1) }
        slot.value = first
        slot.value = first
        assertTrue(closed.isEmpty())
        slot.value = AutoCloseable { closed.add(2) }
        assertEquals(listOf(1), closed)
        slot.clear()
        slot.clear()
        assertFalse(slot.isClosed)
        slot.value = AutoCloseable { closed.add(3) }

        slot.close()
        slot.close()
        slot.clear()

        assertNull(slot.value)
        assertEquals(listOf(1, 2, 3), closed)
    }

    @Test
    fun failingOldCleanupLeavesReplacementOwned() {
        val slot = CloseableSlot<AutoCloseable>()
        val failure = IllegalStateException("old cleanup")
        var replacementClosed = false
        val replacement = AutoCloseable { replacementClosed = true }
        slot.value = AutoCloseable { throw failure }

        assertSame(failure, assertFailsWith<IllegalStateException> { slot.value = replacement })
        assertSame(replacement, slot.value)
        assertFalse(replacementClosed)

        slot.close()

        assertTrue(replacementClosed)
    }

    @Test
    fun reentrantCloseSeesAndClosesReplacementWithoutRestoringIt() {
        val slot = CloseableSlot<AutoCloseable>()
        var replacementClosed = 0
        val replacement = AutoCloseable { replacementClosed++ }
        slot.value = AutoCloseable {
            assertSame(replacement, slot.value)
            slot.close()
        }

        slot.value = replacement
        slot.close()

        assertTrue(slot.isClosed)
        assertNull(slot.value)
        assertEquals(1, replacementClosed)
    }

    @Test
    fun reentrantReplacementWinsAndReleasesIntermediateValue() {
        val slot = CloseableSlot<AutoCloseable>()
        val closed = mutableListOf<Int>()
        val third = AutoCloseable { closed.add(3) }
        slot.value = AutoCloseable {
            closed.add(1)
            slot.value = third
        }

        slot.value = AutoCloseable { closed.add(2) }

        assertSame(third, slot.value)
        assertEquals(listOf(1, 2), closed)
        slot.close()
        assertEquals(listOf(1, 2, 3), closed)
    }

    @Test
    fun clearDetachesBeforeCleanupAndAllowsReentrantReplacement() {
        val slot = CloseableSlot<AutoCloseable>()
        var secondClosed = 0
        val second = AutoCloseable { secondClosed++ }
        slot.value = AutoCloseable {
            assertNull(slot.value)
            slot.clear()
            slot.value = second
        }

        slot.clear()

        assertSame(second, slot.value)
        slot.close()
        assertEquals(1, secondClosed)
    }

    @Test
    fun terminalAssignmentCleansNewValueBeforeRejectingIt() {
        val slot = CloseableSlot<AutoCloseable>()
        slot.close()
        var closed = 0

        assertFailsWith<IllegalStateException> { slot.value = AutoCloseable { closed++ } }

        assertNull(slot.value)
        assertEquals(1, closed)
    }

    @Test
    fun terminalAssignmentPreservesOriginalCleanupFailure() {
        val slot = CloseableSlot<AutoCloseable>()
        slot.close()
        val fatal = Error("cleanup marker")

        assertSame(fatal, assertFailsWith<Error> { slot.value = AutoCloseable { throw fatal } })

        assertTrue(fatal.suppressedExceptions.single() is IllegalStateException)
        assertNull(slot.value)
    }

    @Test
    fun closeIsTerminalEvenIfCleanupFailsOrReenters() {
        val slot = CloseableSlot<AutoCloseable>()
        val failure = IllegalStateException("cleanup")
        var count = 0
        slot.value = AutoCloseable {
            count++
            assertTrue(slot.isClosed)
            assertNull(slot.value)
            slot.close()
            throw failure
        }

        assertSame(failure, assertFailsWith<IllegalStateException> { slot.close() })
        slot.close()

        assertNull(slot.value)
        assertEquals(1, count)
    }

    @Test
    fun selfOwnershipIsRejectedWithoutClosingTheSlot() {
        val slot = CloseableSlot<AutoCloseable>()

        assertFailsWith<IllegalArgumentException> { slot.value = slot }

        assertFalse(slot.isClosed)
        assertNull(slot.value)
    }

    @Test
    fun inFlightValueCannotReacquireItself() {
        val slot = CloseableSlot<AutoCloseable>()
        lateinit var first: AutoCloseable
        var count = 0
        first = AutoCloseable {
            count++
            assertFailsWith<IllegalArgumentException> { slot.value = first }
        }
        slot.value = first

        slot.clear()

        assertNull(slot.value)
        assertEquals(1, count)
    }

    @Test
    fun closedSlotDoesNotRecursivelyCloseAnInFlightResource() {
        val slot = CloseableSlot<AutoCloseable>()
        lateinit var resource: AutoCloseable
        var count = 0
        resource = AutoCloseable {
            count++
            assertFailsWith<IllegalStateException> { slot.value = resource }
        }
        slot.value = resource

        slot.close()

        assertEquals(1, count)
    }

    @Test
    fun reentrantFailureDoesNotRestoreAnAlreadyClosedReplacement() {
        val slot = CloseableSlot<AutoCloseable>()
        val failure = IllegalStateException("new cleanup")
        var count = 0
        slot.value = AutoCloseable { slot.close() }

        assertSame(failure, assertFailsWith<IllegalStateException> {
            slot.value = AutoCloseable { count++; throw failure }
        })

        assertTrue(slot.isClosed)
        assertNull(slot.value)
        slot.close()
        assertEquals(1, count)
    }
}
