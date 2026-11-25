package dev.oreslang.runtime;

import dev.oreslang.types.PortableCodecRegistry.Descriptor;
import dev.oreslang.types.PortableCodecRegistry.Format;
import dev.oreslang.types.PortableCodecRegistry.Purpose;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Trusted runtime-owned decode escrow for one pinned MessagePack endpoint.
 * ActorRuntime uses its ordinary mailbox; the standalone queue is a host test seam.
 * Transport authentication is not implemented here.
 * Only runtime code can create tickets. Input must be exclusively owned and
 * authenticated by the caller; schema metadata is not a transport credential.
 * Logical credits are not physical heap accounting.
 */
final class ActorDataDecodeEscrowV1 {
    private static final int MAX_WIRE = 1024 * 1024;
    private static final long MAX_LOGICAL = 8L * 1024 * 1024;
    private static final long MAX_WORK = 4L * 1024 * 1024;

    /**
     * One shared temporary budget per ActorRuntime, independent of endpoints.
     * No callbacks, actor locks or endpoint locks acquired from this monitor.
     * The caller holds a lease through final mailbox admission and rollback.
     */
    static final class RuntimeBudget {
        private final long byteLimit;
        private final long workLimit;
        private long bytes;
        private long work;
        private int inFlight;

        RuntimeBudget(long byteCeiling) {
            if (byteCeiling < 0) throw new IllegalArgumentException("invalid decode ceiling");
            byteLimit = Math.min(byteCeiling, MAX_LOGICAL);
            workLimit = MAX_WORK;
        }

        static final class Lease implements AutoCloseable {
            private final RuntimeBudget owner;
            private final long bytes;
            private final long work;
            private boolean released;
            private Lease(RuntimeBudget owner, long bytes, long work) {
                this.owner = owner;
                this.bytes = bytes;
                this.work = work;
            }
            @Override public void close() { owner.release(this); }
        }

        synchronized Lease reserve(int length) {
            if (length <= 0 || length > MAX_WIRE) return null;
            return reserveCost(256L + 16L * length, 3L * length + 1L);
        }

        synchronized Lease reserveTicket(Ticket ticket) {
            Objects.requireNonNull(ticket);
            return reserveCost(ticket.bytes, ticket.work);
        }

        private Lease reserveCost(long b, long w) {
            if (inFlight >= 4096 || b <= 0 || w <= 0) return null;
            if (b > byteLimit - bytes || w > workLimit - work) return null;
            Lease lease = new Lease(this, b, w);
            bytes += b;
            work += w;
            inFlight++;
            return lease;
        }

        private synchronized void release(Lease lease) {
            if (lease.released) return;
            if (lease.owner != this || inFlight <= 0 || bytes < lease.bytes || work < lease.work)
                throw new IllegalStateException("runtime DATA decode accounting invariant");
            lease.released = true;
            bytes -= lease.bytes;
            work -= lease.work;
            inFlight--;
        }

        synchronized int inFlight() { return inFlight; }
    }

    static final class Ticket {
        private final long generation;
        private final int wireLength;
        private final long bytes;
        private final long work;
        private boolean decoding;
        private boolean cancelled;

        private Ticket(long generation, int length, long bytes, long work) {
            this.generation = generation;
            wireLength = length;
            this.bytes = bytes;
            this.work = work;
        }
    }

    private record Entry(long value, long bytes, long work) { }
    record Snapshot(long generation, long availableBytes, long availableWork,
                    int reservations, int queued, long publications) { }

    private final Descriptor descriptor;
    private final ActorDataMessagePackPlanV1 typedPlan;
    private final long byteLimit;
    private final long workLimit;
    private final int slots;
    private final IdentityHashMap<Ticket, Boolean> pending = new IdentityHashMap<>();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long generation;
    private long availableBytes;
    private long availableWork;
    private long publications;
    private boolean closed;

    ActorDataDecodeEscrowV1(Descriptor pinned, long generation, long bytes, long work, int slots) {
        this(pinned, generation, bytes, work, slots, false);
    }

    ActorDataDecodeEscrowV1(Descriptor pinned, long generation, long bytes, long work,
                            int slots, boolean typed) {
        descriptor = Objects.requireNonNull(pinned);
        if (pinned.purpose() != Purpose.ACTOR_DATA || pinned.format() != Format.MESSAGEPACK_V2
                || pinned.version() != 1 || (!typed && !pinned.canonicalShape().equals("S(I64)"))
                || pinned.schemaId().length() > 256
                || generation < 1 || bytes < 1 || bytes > MAX_LOGICAL
                || work < 1 || work > MAX_WORK || slots < 1 || slots > 4096) {
            throw new IllegalArgumentException("unsupported actor DATA endpoint or quota");
        }
        this.typedPlan = typed ? new ActorDataMessagePackPlanV1(pinned) : null;
        this.generation = generation;
        this.byteLimit = this.availableBytes = bytes;
        this.workLimit = this.availableWork = work;
        this.slots = slots;
    }

    // Caller supplies its authenticated endpoint envelope metadata. No lookup
    // by wire-selected type, codec fallback, or guest-provided descriptor.
    synchronized Ticket reserve(long epoch, Format format, String schemaId,
                                int schemaVersion, String fingerprint, int length) {
        if (closed || epoch != generation || format != descriptor.format()
                || schemaVersion != descriptor.version()
                || !descriptor.schemaId().equals(schemaId)
                || !descriptor.fingerprintHex().equals(fingerprint)
                || length <= 0 || length > MAX_WIRE
                || pending.size() + queue.size() >= slots) return null;
        long bytes = typedPlan == null ? 256L + 16L * length
                : ActorDataMessagePackPlanV1.logicalBytes(length);
        long work = typedPlan == null ? 3L * length + 1L
                : ActorDataMessagePackPlanV1.logicalWork(length);
        if (bytes > availableBytes || work > availableWork) return null;
        Ticket ticket = new Ticket(epoch, length, bytes, work);
        pending.put(ticket, Boolean.TRUE);
        availableBytes -= bytes;
        availableWork -= work;
        return ticket;
    }

    // Single-use decode admission. Cancellation cannot refund active decode
    // credits until this method's finally block runs. No callbacks under lock.
    boolean decodeAndCommit(Ticket ticket, byte[] ownedWire) {
        if (typedPlan != null) throw new IllegalStateException("typed endpoint requires actual mailbox");
        synchronized (this) {
            if (ticket == null || !pending.containsKey(ticket) || ticket.decoding
                    || ticket.cancelled) return false;
            if (ownedWire == null || ownedWire.length != ticket.wireLength) {
                release(ticket);
                return false;
            }
            ticket.decoding = true;
        }
        try {
            Object decoded = MessagePackWireV2.decode(descriptor.schemaId(),
                    MessagePackWireV2.Shape.i64(), ownedWire);
            if (!(decoded instanceof Long value)) return false;
            synchronized (this) {
                if (closed || ticket.cancelled || ticket.generation != generation
                        || publications == Long.MAX_VALUE) return false;
                // Publication and credit transfer are one monitor transition.
                queue.addLast(new Entry(value, ticket.bytes, ticket.work));
                pending.remove(ticket);
                publications++;
                return true;
            }
        } catch (IllegalArgumentException malformed) {
            return false;
        } finally {
            synchronized (this) { release(ticket); }
        }
    }

    // ActorRuntime uses this path instead of the standalone staging queue.
    // Keep the temporary decode credits charged until ordinary mailbox
    // admission has either committed or rolled back. No monitor held across
    // runtime lifecycle locks; the caller owns this ticket exclusively.
    boolean isTyped() { return typedPlan != null; }

    Long decodeForMailbox(Ticket ticket, byte[] ownedWire) {
        if (isTyped()) throw new IllegalStateException("typed endpoint requires typed ingress");
        Object value = decodeValueForMailbox(ticket, ownedWire);
        return value instanceof Long number ? number : null;
    }

    Object decodeValueForMailbox(Ticket ticket, byte[] ownedWire) {
        synchronized (this) {
            if (ticket == null || !pending.containsKey(ticket) || ticket.decoding
                    || ticket.cancelled || ownedWire == null
                    || ownedWire.length != ticket.wireLength) return null;
            ticket.decoding = true;
        }
        if (typedPlan != null) {
            return typedPlan.decodeOwned(descriptor.format(), descriptor.schemaId(),
                    descriptor.version(), descriptor.fingerprintHex(), ownedWire,
                    ticket.bytes, ticket.work);
        }
        return MessagePackWireV2.decode(descriptor.schemaId(),
                MessagePackWireV2.Shape.i64(), ownedWire);
    }

    synchronized void finishMailboxDecode(Ticket ticket) {
        release(ticket);
    }

    synchronized void cancel(Ticket ticket) {
        if (!pending.containsKey(ticket)) return;
        ticket.cancelled = true;
        if (!ticket.decoding) release(ticket);
    }

    synchronized Long dequeue(long epoch) {
        if (epoch != generation) return null;
        Entry next = queue.pollFirst();
        if (next == null) return null;
        availableBytes += next.bytes();
        availableWork += next.work();
        return next.value();
    }

    synchronized void closeEndpoint() {
        if (closed) return;
        closed = true;
        revokePendingAndQueued();
    }

    synchronized void restart(long nextGeneration) {
        if (closed || nextGeneration <= generation) throw new IllegalArgumentException("closed or non-monotonic generation");
        generation = nextGeneration;
        revokePendingAndQueued();
    }

    private void revokePendingAndQueued() {
        // Active decoders remain charged until they physically exit.
        var iter = pending.keySet().iterator();
        while (iter.hasNext()) {
            Ticket ticket = iter.next();
            ticket.cancelled = true;
            if (!ticket.decoding) {
                iter.remove();
                availableBytes += ticket.bytes;
                availableWork += ticket.work;
            }
        }
        while (!queue.isEmpty()) {
            Entry entry = queue.removeFirst();
            availableBytes += entry.bytes();
            availableWork += entry.work();
        }
    }

    private void release(Ticket ticket) {
        if (pending.remove(ticket) != null) {
            availableBytes += ticket.bytes;
            availableWork += ticket.work;
        }
    }

    synchronized Snapshot snapshot() {
        long bytes = availableBytes;
        long work = availableWork;
        for (Ticket t : pending.keySet()) { bytes += t.bytes; work += t.work; }
        for (Entry e : queue) { bytes += e.bytes(); work += e.work(); }
        if (bytes != byteLimit || work != workLimit || availableBytes < 0 || availableWork < 0)
            throw new IllegalStateException("actor DATA escrow credit invariant violated");
        return new Snapshot(generation, availableBytes, availableWork,
                pending.size(), queue.size(), publications);
    }
}
