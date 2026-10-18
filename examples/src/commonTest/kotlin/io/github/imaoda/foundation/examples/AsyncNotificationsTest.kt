package io.github.imaoda.foundation.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class AsyncNotificationsTest {
    @Test
    fun everyActiveCollectorReceivesEachRevision() = runTest {
        val document = AsyncDocument()
        val first = mutableListOf<AsyncRevisionCommitted>()
        val second = mutableListOf<AsyncRevisionCommitted>()
        val firstJob = backgroundScope.launch { document.revisions.collect { first += it } }
        val secondJob = backgroundScope.launch { document.revisions.collect { second += it } }
        runCurrent() // Establish both subscriptions before publication.

        document.changeText("first")
        runCurrent()
        document.changeText("second")
        runCurrent()

        val expected = listOf(AsyncRevisionCommitted(1, "first"), AsyncRevisionCommitted(2, "second"))
        assertEquals(expected, first)
        assertEquals(expected, second)
        assertEquals(2, document.revision)
        assertEquals("second", document.text)
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()
    }

    @Test
    fun cancellingOneCollectorRemovesOnlyItsSubscription() = runTest {
        val document = AsyncDocument()
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        var firstFinished = false
        val firstJob = backgroundScope.launch {
            try {
                document.revisions.collect { first += it.revision }
            } finally {
                firstFinished = true
            }
        }
        val secondJob = backgroundScope.launch { document.revisions.collect { second += it.revision } }
        runCurrent()
        document.changeText("first")
        runCurrent()

        firstJob.cancelAndJoin()
        document.changeText("second")
        runCurrent()

        assertTrue(firstFinished)
        assertEquals(listOf(1), first)
        assertEquals(listOf(1, 2), second)
        assertTrue(secondJob.isActive)
        secondJob.cancelAndJoin()
    }

    @Test
    fun positiveExtraCapacityDoesNotKeepNotificationsForAbsentCollectors() = runTest {
        val document = AsyncDocument()
        repeat(3) { document.changeText("before subscription $it") }
        assertTrue(document.revisions.replayCache.isEmpty())
        val received = mutableListOf<AsyncRevisionCommitted>()
        val collector = backgroundScope.launch { document.revisions.collect { received += it } }

        runCurrent()
        assertTrue(received.isEmpty())
        document.changeText("after subscription")
        runCurrent()

        assertEquals(listOf(AsyncRevisionCommitted(4, "after subscription")), received)
        collector.cancelAndJoin()
    }

    @Test
    fun slowCollectorFillsTheBufferAndSuspendsFurtherEmissionWithoutDropping() = runTest {
        val document = AsyncDocument()
        val releaseFirstHandler = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val collector = backgroundScope.launch {
            document.revisions.collect {
                started += it.revision
                if (it.revision == 1) releaseFirstHandler.await()
                finished += it.revision
            }
        }
        runCurrent()
        document.changeText("first")
        runCurrent() // First value was received, but its business handler is still suspended.

        document.changeText("second") // The extra slot accepts a value despite the busy handler.
        assertTrue(finished.isEmpty()) // emit returning is not a processing acknowledgement.
        val thirdEmission = launch { document.changeText("third") }
        runCurrent()

        assertFalse(thirdEmission.isCompleted)
        assertEquals(listOf(1), started)
        assertEquals(3, document.revision) // Committing and notification delivery are distinct.

        releaseFirstHandler.complete(Unit)
        thirdEmission.join()
        runCurrent()

        assertEquals(listOf(1, 2, 3), started)
        assertEquals(listOf(1, 2, 3), finished)
        collector.cancelAndJoin()
    }
}
