package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-only queue substrate, not a guest API, persisted log or sandbox. */
public final class DurableLockQueues {
    public record Limits(int maxLocks, int maxEntries, int maxPerLock, long maxBytes) {
        public Limits { if (maxLocks <= 0 || maxEntries <= 0 || maxPerLock <= 0 || maxBytes <= 0)
            throw new IllegalArgumentException("invalid queue limits"); }
    }
    public record Snapshot(int locks, int entries, long bytes, int runningActors, boolean accepting) { }
    public static final class Ticket {
        private final long generation, lockId, sequence, operationId, actorId, actorGeneration;
        private Ticket(long generation, long lockId, long sequence, long operationId,
                       long actorId, long actorGeneration) {
            this.generation = generation; this.lockId = lockId; this.sequence = sequence;
            this.operationId = operationId; this.actorId = actorId; this.actorGeneration = actorGeneration;
        }
        public long isolateGeneration() { return generation; }
        public long lockId() { return lockId; }
        public long sequence() { return sequence; }
    }
    public record Admission(Ticket ticket, boolean head) { }
    private record Actor(long id, long generation) { }
    private static final class Entry {
        final Ticket ticket;
        final long bytes;
        boolean claimed;
        Entry(Ticket ticket, long bytes) { this.ticket = ticket; this.bytes = bytes; }
    }
    private final long generation;
    private final Limits limits;
    private final Map<Long, ArrayDeque<Entry>> lanes = new HashMap<>();
    private final IdentityHashMap<Ticket, Entry> tickets = new IdentityHashMap<>();
    private final Set<Long> operations = new HashSet<>();
    private final Set<Actor> runningActors = new HashSet<>();
    private long sequence = 1, bytes = 0;
    private int entries = 0;
    private boolean accepting = true;

    public DurableLockQueues(long generation, Limits limits) {
        if (generation <= 0 || limits == null) throw new IllegalArgumentException("invalid generation/limits");
        this.generation = generation; this.limits = limits;
    }
    public synchronized Admission enqueue(long actorId, long actorGeneration, long lockId,
                                           long operationId, long size) {
        if (actorId <= 0 || actorGeneration <= 0 || lockId <= 0 || operationId <= 0 || size <= 0)
            throw new IllegalArgumentException("invalid ticket attributes");
        if (!accepting) throw new IllegalStateException("tenant quiescing");
        if (operations.contains(operationId)) throw new IllegalStateException("duplicate operation");
        var lane = lanes.get(lockId);
        if (entries >= limits.maxEntries() || size > limits.maxBytes() - bytes ||
            (lane == null && lanes.size() >= limits.maxLocks()) ||
            (lane != null && lane.size() >= limits.maxPerLock()))
            throw new IllegalStateException("queue quota exhausted");
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("ticket sequence exhausted");
        // Identity issuance is host-private. Never expose a constructor or deserialize tickets.
        var ticket = new Ticket(generation, lockId, sequence++, operationId, actorId, actorGeneration);
        var entry = new Entry(ticket, size);
        boolean head = lane == null || lane.isEmpty();
        if (lane == null) { lane = new ArrayDeque<>(); lanes.put(lockId, lane); }
        lane.addLast(entry); tickets.put(ticket, entry); operations.add(operationId);
        entries++; bytes += size;
        return new Admission(ticket, head);
    }
    private Entry check(Ticket ticket) {
        var entry = tickets.get(ticket);
        if (entry == null || ticket.generation != generation) throw new IllegalStateException("stale/foreign ticket");
        return entry;
    }
    private Entry head(Ticket ticket) {
        var entry = check(ticket);
        if (lanes.get(ticket.lockId).peekFirst() != entry) throw new IllegalStateException("not FIFO head");
        return entry;
    }
    public synchronized boolean claim(Ticket ticket) {
        if (!accepting) throw new IllegalStateException("quiescing");
        var e = head(ticket);
        if (e.claimed) throw new IllegalStateException("already claimed");
        var actor = new Actor(ticket.actorId, ticket.actorGeneration);
        if (runningActors.contains(actor)) return false;
        runningActors.add(actor); e.claimed = true; return true;
    }
    private Ticket removeHead(Entry e) {
        var t = e.ticket;
        var lane = lanes.get(t.lockId);
        lane.removeFirst(); if (lane.isEmpty()) lanes.remove(t.lockId);
        tickets.remove(t); operations.remove(t.operationId); entries--; bytes -= e.bytes;
        return lane.isEmpty() ? null : lane.peekFirst().ticket;
    }
    public synchronized Ticket finish(Ticket ticket) {
        var e = head(ticket);
        if (!e.claimed) throw new IllegalStateException("unclaimed finish");
        if (!runningActors.remove(new Actor(ticket.actorId, ticket.actorGeneration)))
            throw new IllegalStateException("missing actor claim");
        return removeHead(e);
    }
    public synchronized Ticket cancelHead(Ticket ticket) {
        var e = head(ticket);
        if (e.claimed) throw new IllegalStateException("cannot cancel claimed head");
        return removeHead(e);
    }
    public synchronized void cancelWaiter(Ticket ticket) {
        var e = check(ticket);
        var lane = lanes.get(ticket.lockId);
        if (lane.peekFirst() == e || !lane.remove(e)) throw new IllegalStateException("not waiter");
        tickets.remove(ticket); operations.remove(ticket.operationId); entries--; bytes -= e.bytes;
    }
    public synchronized boolean hasActor(long id, long actorGeneration) {
        for (var lane : lanes.values()) for (var e : lane)
            if (e.ticket.actorId == id && e.ticket.actorGeneration == actorGeneration) return true;
        return false;
    }
    public synchronized List<Ticket> readyHeads() {
        var result = new ArrayList<Ticket>();
        if (accepting) for (var lane : lanes.values()) {
            var e = lane.peekFirst();
            if (!e.claimed && !runningActors.contains(new Actor(e.ticket.actorId, e.ticket.actorGeneration)))
                result.add(e.ticket);
        }
        return List.copyOf(result);
    }
    public synchronized void quiesce() { accepting = false; }
    public synchronized boolean drained() { return !accepting && entries == 0 && runningActors.isEmpty(); }
    public synchronized Snapshot snapshot() {
        return new Snapshot(lanes.size(), entries, bytes, runningActors.size(), accepting);
    }
}
