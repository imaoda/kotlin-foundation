package io.github.imaoda.foundation.consumer

import io.github.imaoda.foundation.components.ManagedComponent
import io.github.imaoda.foundation.events.EventEmitter
import io.github.imaoda.foundation.events.ListenerFailurePolicy

/** Compile a real Android consumer against the public API without any UI integration. */
class ConsumerSession : ManagedComponent() {
    private val changes = own(EventEmitter<Int>(ListenerFailurePolicy.FailFast))
    var total: Int = 0
        private set

    init {
        own(changes.event.subscribe { total += it })
    }

    fun increment() {
        ensureOpen()
        changes.emit(1)
    }
}
