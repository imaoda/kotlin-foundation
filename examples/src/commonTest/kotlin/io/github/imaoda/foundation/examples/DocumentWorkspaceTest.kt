package io.github.imaoda.foundation.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DocumentWorkspaceTest {
    @Test
    fun workspaceOwnsDocumentsButBorrowsRepository() {
        val repository = FakeRepository()
        val workspace = DocumentWorkspace(repository) { throw it }
        val first = workspace.openDocument("first")
        val second = workspace.openDocument("second")
        assertSame(first, workspace.openDocument("first"))

        assertTrue(workspace.closeDocument("first"))
        assertFalse(workspace.closeDocument("first"))
        assertFalse(second.isClosed)
        second.changeText("still available")
        assertEquals("still available", second.state.value.text)

        workspace.close()
        workspace.close()
        assertTrue(second.isClosed)
        assertEquals(listOf(1, 1), repository.connections.map { it.closeCount })
        assertEquals(0, repository.closeCount)
        assertFailsWith<IllegalStateException> { workspace.openDocument("third") }

        repository.close() // The separate owner closes the shared service.
        assertEquals(1, repository.closeCount)
    }

    @Test
    fun initializationRollsBackAcquiredResourcesAndPreservesOriginalFailure() {
        val original = IllegalArgumentException("invalid initial content")
        val cleanup = IllegalStateException("connection close failed")
        val repository = FakeRepository(readFailure = original, closeFailure = cleanup)
        val workspace = DocumentWorkspace(repository) { throw it }

        val thrown = assertFailsWith<IllegalArgumentException> { workspace.openDocument("broken") }
        assertSame(original, thrown)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions)
        assertEquals(1, repository.connections.single().closeCount)
        assertEquals(0, repository.closeCount)

        workspace.close()
        assertEquals(1, repository.connections.single().closeCount)
    }

    @Test
    fun replacementAndIndependentCloseDetachOnlyTheOwnedSubscription() {
        val repository = FakeRepository()
        val workspace = DocumentWorkspace(repository) { throw it }
        val document = workspace.openDocument("notes")
        val firstObservations = mutableListOf<String>()
        val secondObservations = mutableListOf<String>()
        val first = document.replacePlugin { firstObservations += it.text }
        document.changeText("one")
        val second = document.replacePlugin { secondObservations += it.text }
        assertTrue(first.isClosed)
        document.changeText("two")

        second.close()
        second.close()
        document.changeText("three")
        assertFalse(document.isClosed)
        assertEquals(listOf("one"), firstObservations)
        assertEquals(listOf("two"), secondObservations)
        assertEquals("three", document.state.value.text)

        workspace.close()
        assertFailsWith<IllegalStateException> { document.events.subscribe { } }
    }

    @Test
    fun observerFailureDoesNotRollBackTheFactAndIsReportedOnce() {
        val failure = IllegalStateException("plugin failed")
        val reports = mutableListOf<Throwable>()
        val repository = FakeRepository()
        val workspace = DocumentWorkspace(repository, reports::add)
        val document = workspace.openDocument("notes")
        val laterObserver = mutableListOf<String>()
        document.replacePlugin { throw failure }
        val subscription = document.events.subscribe { laterObserver += it.text }

        document.changeText("committed")
        assertEquals("committed", document.state.value.text)
        assertEquals("committed", repository.connections.single().text)
        assertEquals(listOf("committed"), laterObserver)
        assertEquals(listOf<Throwable>(failure), reports)

        subscription.close()
        workspace.close()
    }

    private class FakeRepository(
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : DocumentRepository {
        val connections = mutableListOf<FakeConnection>()
        var closeCount = 0

        override fun open(id: String): DocumentConnection =
            FakeConnection(readFailure, closeFailure).also { connections += it }

        override fun close() { closeCount++ }
    }

    private class FakeConnection(
        private val readFailure: Throwable?,
        private val closeFailure: Throwable?,
    ) : DocumentConnection {
        var closeCount = 0
        var text = "initial"

        override fun read(): String {
            readFailure?.let { throw it }
            return text
        }

        override fun write(text: String) { this.text = text }

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }
}
