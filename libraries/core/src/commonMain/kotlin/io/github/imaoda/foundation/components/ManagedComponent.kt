package io.github.imaoda.foundation.components

import io.github.imaoda.foundation.resources.CloseableGroup

/**
 * A base for a long-lived logic object with explicitly owned synchronous resources.
 *
 * Children and subscriptions are registered with [own]; arbitrary synchronous cleanup is registered
 * with [onClose]. Borrowed services are plain references and must not be registered. Value objects and
 * stateless logic do not need this base class.
 *
 * Operations require externally serialized access. Same-thread cleanup reentrancy is supported, but
 * neither this component nor its business methods are made thread-safe. [close] is final, enters the
 * terminal state before callbacks, and delegates reverse-order cleanup and failure preservation to
 * [CloseableGroup]. It does not cancel or await coroutine work.
 *
 * A failing constructor cannot be rolled back by this base. Acquire external resources only after a
 * minimally initialized instance exists, using a factory that closes the instance if initialization
 * fails and rethrows the initialization failure with cleanup failures suppressed.
 */
public abstract class ManagedComponent : AutoCloseable {
    private val resources: CloseableGroup = CloseableGroup()

    /** Whether closing has started. This does not act as a concurrent completion barrier. */
    public val isClosed: Boolean
        get() = resources.isClosed

    /**
     * Transfers [resource] to this component; see [CloseableGroup.add] for duplicate and terminal rules.
     * Owning this component itself throws [IllegalArgumentException].
     */
    protected fun <T : AutoCloseable> own(resource: T): T {
        require(resource !== this) { "A component cannot own itself." }
        return resources.add(resource)
    }

    /**
     * Registers a synchronous cleanup action in the same reverse-order sequence as owned resources.
     * After closing, executes [action] immediately and rejects registration as [own] does.
     */
    protected fun onClose(action: () -> Unit): Unit {
        resources.add(AutoCloseable(action))
    }

    /** Throws [IllegalStateException] if closing has started; this is not a concurrency guard. */
    protected fun ensureOpen(): Unit {
        check(!isClosed) { "The component is closed." }
    }

    /**
     * Closes owned synchronous resources once, in reverse registration order.
     * Later calls, including calls from cleanup callbacks, do nothing.
     */
    final public override fun close(): Unit {
        resources.close()
    }
}
