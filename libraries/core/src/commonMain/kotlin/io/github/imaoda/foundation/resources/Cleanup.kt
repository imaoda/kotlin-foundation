package io.github.imaoda.foundation.resources

internal fun combineFailures(first: Throwable?, next: Throwable): Throwable {
    if (first == null) return next
    if (first !== next && first.suppressedExceptions.none { it === next }) {
        first.addSuppressed(next)
    }
    return first
}

internal fun MutableList<AutoCloseable>.removeIdentity(resource: AutoCloseable): Unit {
    val index = indexOfFirst { it === resource }
    if (index >= 0) removeAt(index)
}

internal fun rejectClosedResource(
    resource: AutoCloseable,
    releasing: MutableList<AutoCloseable>,
): Nothing {
    val rejection = IllegalStateException("The owner is closed; registration was rejected.")
    if (releasing.any { it === resource }) throw rejection
    releasing.add(resource)
    try {
        resource.close()
    } catch (failure: Throwable) {
        failure.addSuppressed(rejection)
        throw failure
    } finally {
        releasing.removeIdentity(resource)
    }
    throw rejection
}
