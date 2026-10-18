package dev.oreslang.runtime;

/**
 * Admission-time capacity reservation for terminal supervisor CONTROL sequence
 * numbers.
 *
 * <p>The actual sequence is claimed only when a terminal failure is finalized,
 * preserving failure-publication order. Admission reserves enough remaining
 * sequence space so finalization can never discover sequence exhaustion after
 * the actor has already failed.</p>
 */
final class ActorSupervisorControlSequence {
    static final class Reservation {
        private final ActorSupervisorControlSequence owner;
        private int state; // 0=reserved, 1=claimed, 2=released

        private Reservation(ActorSupervisorControlSequence owner) {
            this.owner = owner;
        }

        long claim() {
            return owner.claim(this);
        }

        void release() {
            owner.release(this);
        }

        boolean pending() {
            synchronized (owner.lock) {
                return state == 0;
            }
        }
    }

    record Snapshot(
            long nextSequence,
            int reserved,
            long claimed,
            long released,
            long reservationRejected) { }

    private final Object lock = new Object();
    /** Positive next sequence; zero permanently means exhausted. */
    private long nextSequence;
    private int reserved;
    private long claimed;
    private long released;
    private long reservationRejected;

    ActorSupervisorControlSequence() {
        this(1L);
    }

    ActorSupervisorControlSequence(long firstSequence) {
        if (firstSequence <= 0) {
            throw new IllegalArgumentException("first CONTROL sequence must be positive");
        }
        this.nextSequence = firstSequence;
    }

    Reservation reserve() {
        synchronized (lock) {
            if (nextSequence == 0L) {
                reservationRejected = saturatingIncrement(reservationRejected);
                throw new IllegalStateException(
                        "actor supervisor CONTROL sequence exhausted");
            }

            // nextSequence is positive. This arithmetic cannot overflow:
            // Long.MAX_VALUE - 1 + 1 == Long.MAX_VALUE.
            long remaining = Long.MAX_VALUE - nextSequence + 1L;
            if ((long) reserved >= remaining) {
                reservationRejected = saturatingIncrement(reservationRejected);
                throw new IllegalStateException(
                        "actor supervisor CONTROL sequence capacity exhausted");
            }
            if (reserved == Integer.MAX_VALUE) {
                reservationRejected = saturatingIncrement(reservationRejected);
                throw new IllegalStateException(
                        "actor supervisor CONTROL sequence reservation count exhausted");
            }
            reserved++;
            return new Reservation(this);
        }
    }

    private long claim(Reservation reservation) {
        synchronized (lock) {
            requireOwnedPending(reservation);
            if (nextSequence <= 0L || reserved <= 0) {
                throw new IllegalStateException(
                        "reserved CONTROL sequence capacity invariant violated");
            }

            long sequence = nextSequence;
            nextSequence = sequence == Long.MAX_VALUE ? 0L : sequence + 1L;
            reserved--;
            reservation.state = 1;
            claimed = saturatingIncrement(claimed);
            return sequence;
        }
    }

    private void release(Reservation reservation) {
        synchronized (lock) {
            requireOwnedPending(reservation);
            if (reserved <= 0) {
                throw new IllegalStateException(
                        "reserved CONTROL sequence capacity invariant violated");
            }
            reserved--;
            reservation.state = 2;
            released = saturatingIncrement(released);
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    nextSequence,
                    reserved,
                    claimed,
                    released,
                    reservationRejected);
        }
    }

    private void requireOwnedPending(Reservation reservation) {
        if (reservation == null || reservation.owner != this) {
            throw new IllegalArgumentException("foreign CONTROL sequence reservation");
        }
        if (reservation.state != 0) {
            throw new IllegalStateException("CONTROL sequence reservation already settled");
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
