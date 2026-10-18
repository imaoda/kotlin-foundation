package io.github.imaoda.foundation.resources

/**
 * Owns at most one synchronous resource, closing its previous value when it changes.
 *
 * Access must be externally serialized; same-thread reentrancy is supported. No thread switching or
 * asynchronous completion is implied. A new value is installed before the previous value is closed,
 * so a reentrant replacement, [clear], or [close] observes and can release the new value. That nested
 * operation wins: the outer setter does not restore a value after cleanup returns or throws.
 *
 * Resources may have only one owner. This slot detects its own current and in-flight resources, but
 * cannot detect ownership by a different slot or group.
 */
public class CloseableSlot<T : AutoCloseable> : AutoCloseable {
    private var current: T? = null
    private val releasing: MutableList<AutoCloseable> = mutableListOf()
    private var closed: Boolean = false

    /** Whether closing has started; repeated and reentrant close calls are no-ops. */
    public val isClosed: Boolean
        get() = closed

    /**
     * The owned resource, or null when empty.
     *
     * Assigning the same instance is a no-op. Assigning this slot or an instance currently being
     * released throws [IllegalArgumentException]. A replacement remains owned if closing the previous
     * value throws, unless a reentrant operation has changed it again.
     *
     * Assigning a non-null value after closing immediately closes it and then throws
     * [IllegalStateException]. Cleanup failures are rethrown unchanged with the rejection suppressed;
     * an instance already being released is rejected without a second close. Assigning null to a
     * closed slot is a no-op.
     */
    public var value: T?
        get() = current
        set(resource) {
            require(resource !== this) { "A slot cannot own itself." }
            if (isClosed) {
                if (resource != null) rejectClosedResource(resource, releasing)
                return
            }
            if (resource === current) return
            require(releasing.none { it === resource }) {
                "A resource being released cannot be owned again."
            }
            val previous = current
            current = resource
            if (previous != null) release(previous)
        }

    /** Empties this slot before closing its previous value. It remains open for later assignments. */
    public fun clear(): Unit {
        value = null
    }

    /**
     * Enters the terminal state, empties this slot, and closes its previous value once.
     * Any cleanup [Throwable], including cancellation or fatal failures, propagates unchanged.
     */
    public override fun close(): Unit {
        if (isClosed) return
        closed = true
        val previous = current
        current = null
        if (previous != null) release(previous)
    }

    private fun release(resource: T): Unit {
        releasing.add(resource)
        try {
            resource.close()
        } finally {
            releasing.removeIdentity(resource)
        }
    }
}
