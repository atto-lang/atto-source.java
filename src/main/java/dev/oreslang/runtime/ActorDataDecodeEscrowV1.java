package dev.oreslang.runtime;

import dev.oreslang.types.PortableCodecRegistry.Descriptor;
import dev.oreslang.types.PortableCodecRegistry.Format;
import dev.oreslang.types.PortableCodecRegistry.Purpose;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Trusted runtime-owned staging queue for one pinned MessagePack i64 endpoint.
 * Not yet wired to ActorRuntime's actual mailbox or transport authentication.
 * Only runtime code can create tickets. Input must be exclusively owned and
 * authenticated by the caller; schema metadata is not a transport credential.
 * Logical credits are not physical heap accounting.
 */
final class ActorDataDecodeEscrowV1 {
    private static final int MAX_WIRE = 1024 * 1024;
    private static final long MAX_LOGICAL = 8L * 1024 * 1024;
    private static final long MAX_WORK = 4L * 1024 * 1024;

    static final class Ticket {
        private final long generation;
        private final int wireLength;
        private final long bytes;
        private final long work;
        private boolean decoding;
        private boolean cancelled;

        private Ticket(long generation, int length) {
            this.generation = generation;
            wireLength = length;
            bytes = 256L + 16L * length;
            work = 3L * length + 1L;
        }
    }

    private record Entry(long value, long bytes, long work) { }
    record Snapshot(long generation, long availableBytes, long availableWork,
                    int reservations, int queued, long publications) { }

    private final Descriptor descriptor;
    private final long byteLimit;
    private final long workLimit;
    private final int slots;
    private final IdentityHashMap<Ticket, Boolean> pending = new IdentityHashMap<>();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long generation;
    private long availableBytes;
    private long availableWork;
    private long publications;

    ActorDataDecodeEscrowV1(Descriptor pinned, long generation, long bytes, long work, int slots) {
        descriptor = Objects.requireNonNull(pinned);
        if (pinned.purpose() != Purpose.ACTOR_DATA || pinned.format() != Format.MESSAGEPACK_V2
                || pinned.version() != 1 || !pinned.canonicalShape().equals("S(I64)")
                || pinned.schemaId().length() > 256
                || generation < 1 || bytes < 1 || bytes > MAX_LOGICAL
                || work < 1 || work > MAX_WORK || slots < 1 || slots > 4096) {
            throw new IllegalArgumentException("unsupported actor DATA endpoint or quota");
        }
        this.generation = generation;
        this.byteLimit = this.availableBytes = bytes;
        this.workLimit = this.availableWork = work;
        this.slots = slots;
    }

    // Caller supplies its authenticated endpoint envelope metadata. No lookup
    // by wire-selected type, codec fallback, or guest-provided descriptor.
    synchronized Ticket reserve(long epoch, Format format, String schemaId,
                                int schemaVersion, String fingerprint, int length) {
        if (epoch != generation || format != descriptor.format()
                || schemaVersion != descriptor.version()
                || !descriptor.schemaId().equals(schemaId)
                || !descriptor.fingerprintHex().equals(fingerprint)
                || length <= 0 || length > MAX_WIRE
                || pending.size() + queue.size() >= slots) return null;
        long bytes = 256L + 16L * length;
        long work = 3L * length + 1L;
        if (bytes > availableBytes || work > availableWork) return null;
        Ticket ticket = new Ticket(epoch, length);
        pending.put(ticket, Boolean.TRUE);
        availableBytes -= bytes;
        availableWork -= work;
        return ticket;
    }

    // Single-use decode admission. Cancellation cannot refund active decode
    // credits until this method's finally block runs. No callbacks under lock.
    boolean decodeAndCommit(Ticket ticket, byte[] ownedWire) {
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
                if (ticket.cancelled || ticket.generation != generation
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
    Long decodeForMailbox(Ticket ticket, byte[] ownedWire) {
        synchronized (this) {
            if (ticket == null || !pending.containsKey(ticket) || ticket.decoding
                    || ticket.cancelled || ownedWire == null
                    || ownedWire.length != ticket.wireLength) return null;
            ticket.decoding = true;
        }
        Object decoded = MessagePackWireV2.decode(descriptor.schemaId(),
                MessagePackWireV2.Shape.i64(), ownedWire);
        return decoded instanceof Long value ? value : null;
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

    synchronized void restart(long nextGeneration) {
        if (nextGeneration <= generation) throw new IllegalArgumentException("non-monotonic generation");
        generation = nextGeneration;
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
