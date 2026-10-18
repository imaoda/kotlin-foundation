package io.github.imaoda.foundation.resources

/**
 * Owns synchronous resources and closes them in reverse registration order.
 *
 * Access must be externally serialized. Same-thread reentrancy is supported; this class is not
 * thread-safe. Owning a resource does not make its operations or its [AutoCloseable.close] thread-safe.
 * A resource must have one owner; this group detects duplicate identity only within itself, not across
 * groups. Borrowed dependencies must not be added.
 *
 * [close] enters the terminal state before invoking user code. It attempts every registered cleanup,
 * rethrows the first [Throwable], and attaches later failures as suppressed exceptions in first
 * occurrence order, deduplicating by identity against that exception and its existing suppressed list.
 * Cancellation and fatal failures are not converted to success or wrapped. Reentrant or repeated
 * close calls do nothing, including while the first close is still running.
 */
public class CloseableGroup : AutoCloseable {
    private val resources: MutableList<AutoCloseable> = mutableListOf()
    private val releasing: MutableList<AutoCloseable> = mutableListOf()
    private var closed: Boolean = false

    /** Whether closing has started; this is not a concurrent completion barrier. */
    public val isClosed: Boolean
        get() = closed

    /**
     * Transfers [resource] to this group and returns the same instance.
     *
     * Adding this group or an instance already owned or being released by it throws
     * [IllegalArgumentException] without releasing that instance again.
     *
     * After closing starts, a new resource is immediately closed and registration is rejected with
     * [IllegalStateException]. If that cleanup throws, its original failure is rethrown with the
     * rejection suppressed. An instance already scheduled for release is rejected without another
     * close. No closed resource is returned as a successful registration.
     */
    public fun <T : AutoCloseable> add(resource: T): T {
        require(resource !== this) { "A group cannot own itself." }
        if (isClosed) {
            rejectClosedResource(resource, releasing)
        }
        require(resources.none { it === resource } && releasing.none { it === resource }) {
            "This resource is already owned or being released by this group."
        }
        resources.add(resource)
        return resource
    }

    /**
     * Removes an owned [resource] before closing it, releasing this group's reference even on failure.
     * Returns false when the instance is not currently registered. The original cleanup failure is
     * propagated. A callback may close this group without releasing [resource] a second time.
     */
    public fun removeAndClose(resource: AutoCloseable): Boolean {
        val index = resources.indexOfFirst { it === resource }
        if (index < 0) return false
        resources.removeAt(index)
        releasing.add(resource)
        try {
            resource.close()
        } finally {
            releasing.removeIdentity(resource)
        }
        return true
    }

    /** Closes all owned resources once, in reverse registration order; see the class contract. */
    public override fun close(): Unit {
        if (isClosed) return
        closed = true
        val pending = resources.toList()
        resources.clear()
        // Track the whole batch so a callback cannot re-register a resource still awaiting release.
        releasing.addAll(pending)
        var failure: Throwable? = null
        try {
            for (resource in pending.asReversed()) {
                try {
                    resource.close()
                } catch (caught: Throwable) {
                    failure = combineFailures(failure, caught)
                }
            }
        } finally {
            for (resource in pending) {
                releasing.removeIdentity(resource)
            }
        }
        failure?.let { throw it }
    }
}
