package consumer

import io.github.imaoda.foundation.components.ManagedComponent
import io.github.imaoda.foundation.events.EventEmitter
import io.github.imaoda.foundation.events.EventSource
import io.github.imaoda.foundation.events.ListenerFailurePolicy
import io.github.imaoda.foundation.resources.CloseableGroup
import io.github.imaoda.foundation.resources.CloseableSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// This build has no project dependency or composite build: only Maven artifacts are visible.
private class ConsumerComponent : ManagedComponent() {
    private val emitter = own(EventEmitter<Int>(ListenerFailurePolicy.FailFast))
    val changed: EventSource<Int> = emitter.event
    fun change(value: Int) {
        ensureOpen()
        emitter.emit(value)
    }
}

class PublishedApiTest {
    @Test fun publishedArtifactsExposeUsableOwnershipAndEventApis() {
        val root = CloseableGroup()
        val component = root.add(ConsumerComponent())
        val subscription = root.add(CloseableSlot<AutoCloseable>())
        val observed = mutableListOf<Int>()
        subscription.value = component.changed.subscribe { observed += it }
        component.change(1)
        subscription.value = component.changed.subscribe { observed += it * 10 }
        component.change(2)
        subscription.clear()
        component.change(3)
        root.close()
        root.close()
        assertEquals(listOf(1, 20), observed)
        assertFailsWith<IllegalStateException> { component.change(4) }
    }
}
