package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Nonblocking critical-read/critical-write arbiter for one actor-owned region.
 *
 * <p>Multiple distinct logical invocation identities may read simultaneously;
 * a write requires zero readers and no writer. One identity cannot reenter a
 * read or write while it owns a loan. Caller-owned invocation identities are
 * runtime minted, never guest-controlled strings or carrier thread IDs.
 *
 * <p>No guest code/callback is invoked under the internal monitor. A
 * non-admitted operation returns null rather than parking a carrier or creating
 * an unbounded waiter. Closing the arbiter revokes every view but retains its
 * active tickets until their lexical finally blocks close them, so physical
 * region retirement can join outstanding invocations safely.
 */
final class ActorReadWriteLoans implements AutoCloseable {
    private final Object monitor = new Object();
    private final Set<Ticket> active =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Object, Ticket> owners = new IdentityHashMap<>();
    private int readers;
    private Ticket writer;
    private long nextEpoch;
    private boolean closed;

    Ticket tryRead(Object handlerIdentity) {
        return tryAcquire(handlerIdentity, false);
    }

    Ticket tryWrite(Object handlerIdentity) {
        return tryAcquire(handlerIdentity, true);
    }

    private Ticket tryAcquire(Object handlerIdentity, boolean writable) {
        Objects.requireNonNull(handlerIdentity, "logical handler identity");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("actor state loans closed");
            if (owners.containsKey(handlerIdentity)) {
                throw new IllegalStateException("actor handler already owns a state loan");
            }
            if (writer != null || (writable && readers != 0)) return null;
            if (nextEpoch == Long.MAX_VALUE)
                throw new IllegalStateException("actor state loan epoch exhausted");
            Ticket ticket = new Ticket(handlerIdentity, ++nextEpoch, writable);
            active.add(ticket);
            owners.put(handlerIdentity, ticket);
            if (writable) writer = ticket;
            else readers++;
            return ticket;
        }
    }

    int activeCount() {
        synchronized (monitor) { return active.size(); }
    }

    int readerCount() {
        synchronized (monitor) { return readers; }
    }

    boolean hasWriter() {
        synchronized (monitor) { return writer != null; }
    }

    final class Ticket implements AutoCloseable {
        private final Object owner;
        private final long epoch;
        private final boolean writable;
        private boolean released;

        private Ticket(Object owner, long epoch, boolean writable) {
            this.owner = owner;
            this.epoch = epoch;
            this.writable = writable;
        }

        long epoch() { return epoch; }
        boolean writable() { return writable; }

        boolean heldBy(Object handlerIdentity) {
            synchronized (monitor) {
                return !closed && !released
                        && owner == handlerIdentity
                        && owners.get(owner) == this
                        && active.contains(this);
            }
        }

        @Override public void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
                if (!active.remove(this) || owners.remove(owner) != this) {
                    throw new IllegalStateException("actor state loan ownership corrupted");
                }
                if (writable) {
                    if (writer != this)
                        throw new IllegalStateException("actor write loan ownership corrupted");
                    writer = null;
                } else {
                    if (readers <= 0)
                        throw new IllegalStateException("actor reader loan count underflow");
                    readers--;
                }
            }
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            closed = true;
            // Active tickets remain until their physical lexical cleanup
            // releases them; heldBy() rejects every ticket from now on.
        }
    }
}
