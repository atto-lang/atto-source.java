package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Actor-owned arena for finite byte nodes and NON-OWNING graph links.
 *
 * The node payloads use the actor's owner-checked direct-memory region.
 * Edges are generational handles, never strong pointers to other nodes.
 * Consequently a cycle of edges cannot keep any node alive at actor exit.
 *
 * Host bookkeeping still uses Java objects; ByteBuffer backing memory is
 * detached/zeroed but physical release remains JVM-implementation-dependent.
 * This is a deterministic logical arena, NOT a native allocator or a
 * separate JVM garbage collector.
 *
 * The compiler will need source-level handle lowering and destructor support
 * before this becomes the general Oreslang heap for arbitrary guest objects.
 */
public final class ActorGraphArena implements AutoCloseable {
    private static final int MAX_NODES = 65_536;
    private static final int MAX_NODE_BYTES = 1 << 20;
    private static final int MAX_EDGES = 100_000;
    private static final int MAX_EDGES_PER_NODE = 256;

    /** A handle never conveys ownership, and is valid only in its own arena. */
    public record NodeId(UUID arenaId, int slot, long generation) {
        public NodeId {
            Objects.requireNonNull(arenaId, "arenaId");
            if (slot < 0 || generation <= 0) {
                throw new IllegalArgumentException("invalid arena node identity");
            }
        }
    }

    private static final class Slot {
        long generation = 1;
        ActorRuntime.PrivateMemoryBlock payload;
        final Set<NodeId> outgoing = new HashSet<>();
    }

    private final UUID arenaId = UUID.randomUUID();
    private final ActorRuntime.ActorMemorySlice memory;
    private final List<Slot> slots = new ArrayList<>();
    private final ArrayDeque<Integer> vacant = new ArrayDeque<>();
    private int links;
    private boolean closed;

    /** Only ActorRuntime's owning ActorCell constructs this arena. */
    ActorGraphArena(ActorRuntime.ActorMemorySlice memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public UUID arenaId() { return arenaId; }
    public ActorRuntime.ActorId owner() { return memory.owner(); }

    public synchronized int liveNodes() {
        requireOwner();
        return slots.size() - vacant.size();
    }

    public synchronized int liveLinks() {
        requireOwner();
        return links;
    }

    public synchronized NodeId allocate(byte[] value) {
        requireOwner();
        Objects.requireNonNull(value, "value");
        if (value.length > MAX_NODE_BYTES) {
            throw new IllegalArgumentException("graph node exceeds 1 MiB");
        }
        if (slots.size() >= MAX_NODES && vacant.isEmpty()) {
            throw new IllegalStateException("actor graph node quota exceeded");
        }

        // Reserve actor-owned bytes first. A failed reservation must not
        // consume an arena slot or change its generation.
        ActorRuntime.PrivateMemoryBlock block = memory.allocatePrivateBytes(value.length);
        try {
            block.copyIn(value);
        } catch (RuntimeException | Error failure) {
            block.close();
            throw failure;
        }

        int index;
        Slot slot;
        if (!vacant.isEmpty()) {
            index = vacant.removeFirst();
            slot = slots.get(index);
        } else {
            index = slots.size();
            slot = new Slot();
            slots.add(slot);
        }
        slot.payload = block;
        return new NodeId(arenaId, index, slot.generation);
    }

    public synchronized byte[] read(NodeId id) {
        requireOwner();
        return liveSlot(id).payload.copyOut();
    }

    public synchronized void write(NodeId id, byte[] payload) {
        requireOwner();
        Objects.requireNonNull(payload, "payload");
        liveSlot(id).payload.copyIn(payload);
    }

    /** Cycles and self links are legal because links are not owning pointers. */
    public synchronized boolean link(NodeId from, NodeId to) {
        requireOwner();
        Slot source = liveSlot(from);
        liveSlot(to);
        if (source.outgoing.contains(to)) return false;
        if (source.outgoing.size() >= MAX_EDGES_PER_NODE || links >= MAX_EDGES) {
            throw new IllegalStateException("actor graph edge quota exceeded");
        }
        boolean added = source.outgoing.add(to);
        if (added) links++;
        return added;
    }

    public synchronized boolean unlink(NodeId from, NodeId to) {
        requireOwner();
        Slot source = liveSlot(from);
        // Stale targets can be unlinked; they cannot be dereferenced.
        Objects.requireNonNull(to, "to");
        if (!arenaId.equals(to.arenaId())) throw new SecurityException("foreign graph arena handle");
        boolean removed = source.outgoing.remove(to);
        if (removed) links--;
        return removed;
    }

    public synchronized List<NodeId> edges(NodeId from) {
        requireOwner();
        return List.copyOf(liveSlot(from).outgoing);
    }

    /** Explicitly remove one node; all old handles become stale permanently. */
    public synchronized void remove(NodeId id) {
        requireOwner();
        Slot slot = liveSlot(id);
        // Fail before release if the generation cannot be advanced.
        long next = Math.addExact(slot.generation, 1);
        slot.payload.close();
        // Purge incoming handles before recycling the slot. A stale incoming
        // edge cannot dereference the node, but retaining it would consume
        // the finite edge budget over many allocate/delete generations.
        for (Slot candidate : slots) {
            if (candidate.payload != null && candidate.outgoing.remove(id)) links--;
        }
        slot.payload = null;
        links -= slot.outgoing.size();
        slot.outgoing.clear();
        slot.generation = next;
        vacant.addLast(id.slot());
        // Incoming non-owning links may now be stale, which is safe; resolve
        // always validates generations and never dereferences a stale target.
    }

    private Slot liveSlot(NodeId id) {
        Objects.requireNonNull(id, "id");
        if (!arenaId.equals(id.arenaId())) throw new SecurityException("foreign graph arena handle");
        if (id.slot() >= slots.size()) throw new IllegalStateException("unknown actor graph node");
        Slot slot = slots.get(id.slot());
        if (slot.payload == null || slot.generation != id.generation()) {
            throw new IllegalStateException("stale actor graph node handle");
        }
        return slot;
    }

    private void requireOwner() {
        if (closed) throw new IllegalStateException("actor graph arena is closed");
        memory.requireCurrentOwner();
    }

    /**
     * Guest-initiated deterministic logical teardown. Actual ByteBuffer
     * native-memory deallocation is outside this Java class's guarantees.
     */
    @Override
    public synchronized void close() {
        requireOwner();
        closed = true;
        RuntimeException failure = null;
        for (Slot slot : slots) {
            if (slot.payload != null) {
                try { slot.payload.close(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
                slot.payload = null;
            }
            slot.outgoing.clear();
        }
        slots.clear();
        vacant.clear();
        links = 0;
        if (failure != null) throw failure;
    }

    /** ActorCell calls this only after all actor turns/continuations drain. */
    synchronized void retireFromActorTermination() {
        closed = true;
        for (Slot slot : slots) {
            // ActorMemorySlice.close() invalidates and zeroes every block.
            slot.payload = null;
            slot.outgoing.clear();
        }
        slots.clear();
        vacant.clear();
        links = 0;
    }
}
