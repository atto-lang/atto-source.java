package dev.oreslang.nodes;

/** Shared failure selection for synchronous and continuation-driven cleanup. */
final class CleanupFailureOrder {
    private static final int MAX_SUPPRESSED = 64;

    private CleanupFailureOrder() {}

    static void suppress(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        synchronized (primary) {
            if (primary.getSuppressed().length < MAX_SUPPRESSED) {
                primary.addSuppressed(secondary);
            }
        }
    }

    /**
     * Preserve the existing resumable path's classification: first ordinary
     * failure wins, an unmaskable failure can replace ordinary failure, and
     * scheduler-fatal failure can replace cancellation. A later process exit
     * cannot replace a pending failure. Typed guest panic/cancel classification
     * is a separate compiler/runtime admission gate.
     */
    static Throwable merge(Throwable earlier, Throwable cleanup,
            boolean cleanupExit, boolean cleanupUnmaskable,
            boolean earlierUnmaskable, boolean earlierCancellation,
            boolean cleanupSchedulerFatal) {
        if (earlier == null || earlier == cleanup) return cleanup;
        if (!cleanupExit && cleanupUnmaskable
                && (!earlierUnmaskable || (earlierCancellation && cleanupSchedulerFatal))) {
            suppress(cleanup, earlier);
            return cleanup;
        }
        suppress(earlier, cleanup);
        return earlier;
    }
}
