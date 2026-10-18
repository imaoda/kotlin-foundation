package io.github.imaoda.foundation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagedComponentTest {
    @Test
    fun ownershipTreeClosesChildrenAndActionsButNotBorrowedDependencies() {
        val closed = mutableListOf<String>()
        val borrowed = AutoCloseable { closed.add("borrowed") }
        val root = TestComponent(borrowed)
        val child = root.add(TestComponent(borrowed))
        child.cleanup { closed.add("child") }
        root.cleanup {
            assertTrue(root.isClosed)
            root.close()
            closed.add("root action")
        }

        root.close()
        root.close()

        assertTrue(child.isClosed)
        assertEquals(listOf("root action", "child"), closed)
    }

    @Test
    fun operationsRejectTerminalComponentAndLateCleanupIsNotLost() {
        val component = TestComponent()
        component.work()
        component.close()
        var count = 0

        assertFailsWith<IllegalStateException> { component.work() }
        assertFailsWith<IllegalStateException> { component.cleanup { count++ } }
        assertFailsWith<IllegalStateException> { component.add(AutoCloseable { count++ }) }

        assertEquals(2, count)
    }

    @Test
    fun componentCannotOwnItself() {
        val component = TestComponent()

        assertFailsWith<IllegalArgumentException> { component.add(component) }

        assertFalse(component.isClosed)
    }

    @Test
    fun factoryRollsBackPartialInitializationAndPreservesTheOriginalFailure() {
        val original = IllegalArgumentException("initialization")
        val cleanup = IllegalStateException("cleanup")
        var acquiredClosed = false
        lateinit var instance: TestComponent

        val caught = assertFailsWith<IllegalArgumentException> {
            createComponent({ instance = it }) {
                add(AutoCloseable { acquiredClosed = true })
                cleanup { throw cleanup }
                throw original
            }
        }

        assertSame(original, caught)
        assertEquals(listOf(cleanup), original.suppressedExceptions)
        assertTrue(acquiredClosed)
        assertTrue(instance.isClosed)
    }

    @Test
    fun successfulFactoryTransfersTheLiveInstanceToItsCaller() {
        var closed = 0
        val instance = createComponent {} .also { it.cleanup { closed++ } }

        assertFalse(instance.isClosed)
        assertEquals(0, closed)

        instance.close()
        assertEquals(1, closed)
    }

    private class TestComponent(val borrowed: AutoCloseable? = null) : ManagedComponent() {
        fun <T : AutoCloseable> add(resource: T): T = own(resource)
        fun cleanup(action: () -> Unit) = onClose(action)
        fun work() = ensureOpen()
    }

    // A private constructor/factory can use this pattern without expanding the library's public API.
    private fun createComponent(
        observe: (TestComponent) -> Unit = {},
        initialize: TestComponent.() -> Unit,
    ): TestComponent {
        val component = TestComponent()
        observe(component)
        try {
            component.initialize()
            return component
        } catch (original: Throwable) {
            try {
                component.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
            throw original
        }
    }
}
