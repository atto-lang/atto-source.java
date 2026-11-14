package dev.oreslang.runtime;

import java.util.OptionalLong;

/**
 * The first executable scalar state-container region. It deliberately holds
 * exactly one i64 value: no guest object, pointer, array or borrow can escape.
 * This is a VM substrate, NOT general guest "define state container" lowering.
 *
 * Operations are non-parking: an incompatible loan returns empty. The only
 * critical monitor protects scalar load/store and shutdown linearization;
 * no user callback runs under it. Overflow leaves the state unchanged.
 */
public final class ScalarStateContainer implements AutoCloseable {
    private final Object stateLock = new Object();
    private final StateContainerAccessGate gate;
    private long value;
    private boolean closed;

    public ScalarStateContainer(long initialValue, int maxReaders) {
        gate = new StateContainerAccessGate(maxReaders);
        value = initialValue;
    }

    public OptionalLong tryRead() {
        var lease = gate.tryRead();
        if (lease == null) return OptionalLong.empty();
        try (lease) {
            synchronized (stateLock) {
                if (closed) return OptionalLong.empty();
                lease.requireActive(StateContainerAccessGate.Mode.READ);
                return OptionalLong.of(value);
            }
        }
    }

    public OptionalLong tryAddChecked(long delta) {
        var lease = gate.tryWrite();
        if (lease == null) return OptionalLong.empty();
        try (lease) {
            synchronized (stateLock) {
                if (closed) return OptionalLong.empty();
                lease.requireActive(StateContainerAccessGate.Mode.WRITE);
                long next = Math.addExact(value, delta);
                value = next;
                return OptionalLong.of(next);
            }
        }
    }

    public OptionalLong trySet(long replacement) {
        var lease = gate.tryWrite();
        if (lease == null) return OptionalLong.empty();
        try (lease) {
            synchronized (stateLock) {
                if (closed) return OptionalLong.empty();
                lease.requireActive(StateContainerAccessGate.Mode.WRITE);
                value = replacement;
                return OptionalLong.of(value);
            }
        }
    }

    /** No new value may be committed after close linearizes. */
    @Override public void close() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            gate.close();
        }
    }

    /**
     * Arena reclamation is still a separate VM operation. This reports only
     * state-gate quiescence after close, not safety of unrelated native aliases.
     */
    public boolean readyForReclamation() {
        synchronized (stateLock) {
            return closed && gate.leasesQuiescentAfterClose();
        }
    }
}
