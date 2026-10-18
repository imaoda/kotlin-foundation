package io.github.imaoda.foundation.events

/**
 * Explicit failure handling for an [EventEmitter]. There is no implicit default policy.
 *
 * Events report facts that have already happened. Neither policy rolls back the operation
 * that published a fact, and neither retries a failed listener.
 */
public sealed class ListenerFailurePolicy private constructor() {
    /** Propagates the first listener failure immediately and skips remaining listeners. */
    public data object FailFast : ListenerFailurePolicy()

    /**
     * Reports ordinary listener [Exception]s to [onFailure], then continues notification.
     *
     * Cancellation exceptions, [Error]s, and other non-[Exception] failures propagate
     * immediately without reporting. Reporting happens synchronously on the emitting call
     * stack. If [onFailure] throws, its failure propagates with the listener's failure
     * suppressed and remaining listeners are skipped. Nested emissions on the same emitter
     * propagate that reporting failure without reporting it again while the outer emission
     * is active. Different emitters remain independent failure boundaries. If both failures
     * are the same object, self-suppression is omitted.
     *
     * [onFailure] is an observation boundary, not a recovery or retry mechanism. It must not
     * assume that a failed listener completed its work or that the publisher rolled back.
     */
    public class ReportAndContinue(public val onFailure: (Throwable) -> Unit) : ListenerFailurePolicy()
}
