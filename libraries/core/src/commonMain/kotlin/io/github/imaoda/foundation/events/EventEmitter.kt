package io.github.imaoda.foundation.events

import kotlin.coroutines.cancellation.CancellationException

/**
 * Publishes synchronous notifications with explicit [failurePolicy].
 *
 * Keep the emitter private and expose [event] to observers. That view only permits
 * subscription; it is not the emitter cast to a narrower interface.
 *
 * All operations, including subscription handle closure, require externally serialized
 * access. The emitter does not synchronize or switch threads. Reentrant calls on the same
 * stack are supported; concurrent calls are not. Callers remain responsible for any
 * thread-affinity requirements of listeners.
 *
 * Each [emit] takes a snapshot in subscription order. New subscriptions are visible to the
 * next emission, including a nested emission, but not to the current snapshot. A listener
 * removed before its turn is skipped. Reentrant emissions run immediately, depth-first.
 * The same callback may be registered repeatedly, creating independent subscriptions.
 *
 * Closing the emitter is terminal. It disconnects every subscription, releases their
 * callback references, and prevents new subscriptions and emissions. Closing a subscription
 * also releases its callback and emitter references. Neither operation closes a listener's
 * business object or interrupts a callback that is already running.
 */
public class EventEmitter<T>(private val failurePolicy: ListenerFailurePolicy) : AutoCloseable {
    private var closed: Boolean = false
    private val subscriptions: MutableList<Subscription<T>> = mutableListOf()
    private var emissionDepth: Int = 0
    private val reportingFailures: MutableList<Throwable> = mutableListOf()

    /**
     * A stable, subscription-only view of this emitter.
     *
     * Subscription is immediate and does not replay previous values. After emitter closure,
     * subscribing throws [IllegalStateException].
     */
    public val event: EventSource<T> = EventSource { listener ->
        checkOpen()
        Subscription(this, listener).also(subscriptions::add)
    }

    /**
     * Delivers [value] synchronously to the current subscriptions.
     *
     * Listener failures follow [ListenerFailurePolicy]. A failure does not automatically
     * remove its listener, close the emitter, or undo earlier effects. If a listener closes
     * the emitter, the current callback may finish and remaining subscriptions are skipped.
     *
     * @throws IllegalStateException if this emitter has already been closed.
     */
    public fun emit(value: T) {
        checkOpen()
        emissionDepth++
        try {
            for (subscription in subscriptions.toList()) {
                val listener = subscription.listener ?: continue
                when (val policy = failurePolicy) {
                    ListenerFailurePolicy.FailFast -> listener(value)
                    is ListenerFailurePolicy.ReportAndContinue -> {
                        try {
                            listener(value)
                        } catch (failure: Exception) {
                            if (failure is CancellationException || reportingFailures.any { it === failure }) {
                                throw failure
                            }
                            try {
                                policy.onFailure(failure)
                            } catch (reportingFailure: Throwable) {
                                if (reportingFailure !== failure) reportingFailure.addSuppressed(failure)
                                // An outer callback can be executing a nested emit. Preserve this
                                // failure's identity across that unwind instead of reporting it twice.
                                reportingFailures.add(reportingFailure)
                                throw reportingFailure
                            }
                        }
                    }
                }
            }
        } finally {
            emissionDepth--
            if (emissionDepth == 0) reportingFailures.clear()
        }
    }

    /**
     * Disconnects all subscriptions and permanently closes this emitter.
     *
     * Repeated closure, and closing any previously returned subscription handle afterward,
     * are harmless. Closing during delivery skips listeners that have not yet started.
     */
    public override fun close() {
        if (closed) return
        closed = true
        for (subscription in subscriptions) subscription.detach()
        subscriptions.clear()
    }

    private fun checkOpen() {
        check(!closed) { "EventEmitter is closed" }
    }

    private class Subscription<T>(
        private var owner: EventEmitter<T>?,
        var listener: ((T) -> Unit)?,
    ) : AutoCloseable {
        override fun close() {
            val previousOwner = owner
            detach()
            previousOwner?.subscriptions?.remove(this)
        }

        fun detach() {
            owner = null
            listener = null
        }
    }
}
