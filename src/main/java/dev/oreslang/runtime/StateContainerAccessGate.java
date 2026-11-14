package dev.oreslang.runtime;

import java.util.Objects;

/**
 * VM-only, non-parking critical-region admission for future state containers.
 * Does not hold or expose guest heap state. Every VM read/write must separately
 * verify the returned lease; this object never gives callers an unchecked
 * reference to the protected region.
 *
 * Callbacks, guest code, scheduler turns and Future completions NEVER execute
 * under the monitor. There is no implicit queue or blocking wait.
 */
public final class StateContainerAccessGate implements AutoCloseable {
    public enum Mode { READ, WRITE }

    private final Object monitor = new Object();
    private final int maxReaders;
    private int readers;
    private Lease writer;
    private boolean closed;
    private long epoch;

    public StateContainerAccessGate(int maxReaders) {
        if (maxReaders < 1) throw new IllegalArgumentException("maxReaders must be positive");
        this.maxReaders = maxReaders;
    }

    public Lease tryRead() {
        synchronized (monitor) {
            if (closed || writer != null || readers == maxReaders) return null;
            if (epoch == Long.MAX_VALUE) return null;
            // Construct the lease before incrementing live-reader accounting.
            // If allocation fails, do not strand a reader count and prevent
            // shutdown from ever observing physical lease quiescence.
            Lease loan = new Lease(Mode.READ, epoch + 1);
            epoch++;
            readers++;
            return loan;
        }
    }

    public Lease tryWrite() {
        synchronized (monitor) {
            if (closed || writer != null || readers != 0) return null;
            if (epoch == Long.MAX_VALUE) return null;
            Lease loan = new Lease(Mode.WRITE, epoch + 1);
            epoch++;
            writer = loan;
            return loan;
        }
    }

    public int readerCount() {
        synchronized (monitor) { return readers; }
    }

    public boolean writerActive() {
        synchronized (monitor) { return writer != null; }
    }

    public boolean isClosed() {
        synchronized (monitor) { return closed; }
    }

    /**
     * Reclamation is safe only after shutdown AND all existing readers/writer
     * have physically released their grants. An owner must also establish
     * that no separate raw references or in-flight guest turns remain.
     */
    public boolean leasesQuiescentAfterClose() {
        synchronized (monitor) {
            return closed && writer == null && readers == 0;
        }
    }

    public final class Lease implements AutoCloseable {
        private final Mode mode;
        private final long version;
        private boolean released;

        private Lease(Mode mode, long version) {
            this.mode = Objects.requireNonNull(mode);
            this.version = version;
        }

        public Mode mode() { return mode; }
        public long version() { return version; }

        /** Observational only: callers must check under their own state access. */
        public boolean active() {
            synchronized (monitor) { return !closed && !released; }
        }

        /**
         * Enforce capability ownership at every future VM state access.
         * A stale or closed grant must not authorize a recycled arena slot.
         */
        public void requireActive(Mode required) {
            Objects.requireNonNull(required, "required");
            synchronized (monitor) {
                if (closed || released || (required == Mode.WRITE && mode != Mode.WRITE)
                        || (mode == Mode.WRITE && writer != this)) {
                    throw new IllegalStateException("inactive or insufficient state-container lease");
                }
            }
        }

        @Override public void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
                if (mode == Mode.WRITE) {
                    if (writer != this)
                        throw new IllegalStateException("state-container writer ownership corrupted");
                    writer = null;
                } else {
                    if (readers <= 0)
                        throw new IllegalStateException("state-container reader count corrupted");
                    readers--;
                }
            }
        }
    }

    /**
     * Revokes all current leases immediately. The enclosing owner must still
     * await physical turn quiescence before actually freeing guest memory.
     * Closing this gate is not a deallocator or a synchronization barrier for
     * arbitrary raw pointers.
     */
    @Override public void close() {
        synchronized (monitor) {
            closed = true;
        }
    }
}
