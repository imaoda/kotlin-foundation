package io.github.imaoda.foundation.events

/**
 * The subscription-only view of synchronous, typed object notifications.
 *
 * A subscription belongs to its listener's owner. Closing its handle removes only that
 * subscription; it does not close the listener's business object. Implementations document
 * delivery order, reentrancy, concurrency, failure handling, and their terminal state.
 */
public fun interface EventSource<T> {
    /**
     * Registers [listener] and returns an independently closeable subscription.
     *
     * The returned handle is idempotent: closing it more than once has no further effect.
     */
    public fun subscribe(listener: (T) -> Unit): AutoCloseable
}
