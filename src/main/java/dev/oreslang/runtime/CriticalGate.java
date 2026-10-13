package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Legacy exclusive critical API backed by the identical reader/writer gate
 * used for explicit read/write admission on this protection domain.
 *
 * <p>Legacy critical always requests WRITE. Reader and writer tickets on
 * the same protected object, static class or code unit must come from
 * rwGate(), never from a separately constructed RW gate.</p>
 */
public final class CriticalGate {
    public static final int MAX_WAITERS = CriticalRwGate.MAX_WAITERS;

    private final CriticalRwGate engine = new CriticalRwGate();

    /** The common admission engine for all critical modes on this domain. */
    public CriticalRwGate rwGate() {
        return engine;
    }

    public Ticket newTicket() {
        return new Ticket(engine.newCriticalTicket());
    }

    public Ticket newTicket(CriticalRwGate.Mode mode) {
        return new Ticket(engine.newTicket(Objects.requireNonNull(mode, "mode")));
    }

    public final class Ticket implements AutoCloseable {
        private final CriticalRwGate.Ticket delegated;

        private Ticket(CriticalRwGate.Ticket delegated) {
            this.delegated = delegated;
        }

        public OresFuture<Void> enterAsync() {
            return delegated.enterAsync();
        }

        /** Host-only waits; synchronous actor/scheduler contention fails fast. */
        public void enterSync() {
            delegated.enterSync();
        }

        public void releaseOnCompletion(OresFuture<?> completion) {
            delegated.releaseOnCompletion(completion);
        }

        public boolean isHeld() {
            return delegated.isHeld();
        }

        @Override
        public void close() {
            delegated.close();
        }
    }

    public int queuedCount() {
        return engine.queuedCount();
    }

    public boolean held() {
        return engine.activeReaderCount() > 0 || engine.hasWriter();
    }

    public static int waitingLogicalDomains() {
        return CriticalRwGate.waitingLogicalDomains();
    }
}
