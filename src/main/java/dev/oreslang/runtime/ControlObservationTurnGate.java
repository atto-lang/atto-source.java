package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A physical CONTROL turn and a dispatch-failure observer race on one
 * atomic admission decision. Failing dispatch can release accounting only
 * if no physical turn has claimed the request. A late/duplicate executor
 * cannot run work after such a release.
 *
 * This is host-owned accounting, never a guest capability or a replacement
 * for actual CONTROL turn completion/teardown evidence.
 */
final class ControlObservationTurnGate {
    private static final int QUEUED = 0;
    private static final int STARTED = 1;
    private static final int ABANDONED = 2;
    private final AtomicInteger phase = new AtomicInteger(QUEUED);

    boolean tryStart() {
        return phase.compareAndSet(QUEUED, STARTED);
    }

    boolean abandonIfQueued() {
        return phase.compareAndSet(QUEUED, ABANDONED);
    }
}
