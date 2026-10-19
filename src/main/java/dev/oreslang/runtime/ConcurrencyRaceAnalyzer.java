package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded happens-before race analysis for *instrumented* logical executions.
 *
 * A caller must provide all mutable memory reads/writes and synchronization
 * events. Merely enabling this analyzer without compiler/runtime access hooks
 * cannot establish race freedom. Logical participant IDs survive carrier
 * migration; JVM thread IDs are deliberately not used as ownership IDs.
 *
 * CRITICAL: participant IDs denote independently executing logical task/message
 * lifetimes (including distinct concurrent lanes within ONE actor), not actor
 * reference IDs. Reusing the same participant ID for independent concurrent
 * operations would merge their clocks and could hide a real race. Every guest
 * instrumentation backend must enforce unique live logical participant IDs.
 */
public final class ConcurrencyRaceAnalyzer {
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_FINDINGS = 1_000;
    private static final int MAX_PARTICIPANTS = 256;

    public record Finding(String location, String priorParticipant,
                          String currentParticipant, String conflict) { }

    private record Access(String participant, Map<String, Long> clock) { }

    private static final class Location {
        // Preserve one latest access per participant and mode. Overwriting a
        // different participant's last writer would lose a concurrent history.
        final Map<String, Access> writes = new HashMap<>();
        final Map<String, Access> reads = new HashMap<>();
    }

    private final Map<String, Map<String, Long>> clocks = new HashMap<>();
    private final Map<String, Location> locations = new HashMap<>();
    private final Map<String, Map<String, Long>> messages = new HashMap<>();
    private final Set<String> issuedMessageIds = new HashSet<>();
    private final Map<String, Map<String, Long>> locks = new HashMap<>();
    private final Map<String, String> lockOwners = new HashMap<>();
    private final Map<String, Integer> lockDepths = new HashMap<>();
    // Only distinct logical child lifetimes may supply fork/join synchronization.
    private final Map<String, String> forkParents = new HashMap<>();
    private final Map<String, Map<String, Long>> completedChildren = new HashMap<>();
    private final Set<String> finishedParticipants = new HashSet<>();
    private final Set<String> joinedChildren = new HashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private int events;
    private long conflictCount;

    private static String key(String name) {
        Objects.requireNonNull(name, "identity");
        if (name.isBlank() || name.length() > 128
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "event identity must contain 1..128 non-control characters");
        }
        return name;
    }

    private void budget() {
        if (events >= MAX_EVENTS) throw new IllegalStateException("race analyzer event budget exhausted");
        ++events;
    }

    private Map<String, Long> clock(String participant) {
        String id = key(participant);
        if (finishedParticipants.contains(id)) {
            throw new IllegalStateException("participant lifetime already finished");
        }
        Map<String, Long> existing = clocks.get(id);
        if (existing != null) return existing;
        if (clocks.size() == MAX_PARTICIPANTS) {
            throw new IllegalStateException("race analyzer participant budget exhausted");
        }
        Map<String, Long> fresh = new LinkedHashMap<>();
        clocks.put(id, fresh);
        return fresh;
    }

    private static void merge(Map<String, Long> destination, Map<String, Long> incoming) {
        incoming.forEach((id, epoch) -> destination.merge(id, epoch, Math::max));
    }

    private static boolean happensBefore(Map<String, Long> earlier, Map<String, Long> later) {
        for (var entry : earlier.entrySet()) {
            if (entry.getValue() > later.getOrDefault(entry.getKey(), 0L)) return false;
        }
        return true;
    }

    private Access tick(String participant) {
        Map<String, Long> current = clock(participant);
        current.merge(participant, 1L, Math::addExact);
        return new Access(participant, Map.copyOf(current));
    }

    private void conflict(String location, Access previous, Access current, String kind) {
        if (previous != null && !happensBefore(previous.clock(), current.clock())) {
            conflictCount = Math.addExact(conflictCount, 1L);
            if (findings.size() < MAX_FINDINGS) {
                findings.add(new Finding(location, previous.participant(),
                        current.participant(), kind));
            }
        }
    }

    public synchronized void read(String participant, String location) {
        String loc = key(location);
        key(participant);
        clock(participant);
        budget();
        Access access = tick(participant);
        Location state = locations.computeIfAbsent(loc, ignored -> new Location());
        for (Access writer : state.writes.values()) {
            conflict(loc, writer, access, "write-read");
        }
        state.reads.put(participant, access);
    }

    public synchronized void write(String participant, String location) {
        String loc = key(location);
        key(participant);
        clock(participant);
        budget();
        Access access = tick(participant);
        Location state = locations.computeIfAbsent(loc, ignored -> new Location());
        for (Access writer : state.writes.values()) {
            conflict(loc, writer, access, "write-write");
        }
        for (Access reader : state.reads.values()) {
            conflict(loc, reader, access, "read-write");
        }
        state.writes.put(participant, access);
    }

    /** One unique, single-consumer message token, not a reusable channel name. */
    public synchronized void send(String participant, String messageId) {
        String id = key(messageId);
        String actor = key(participant);
        if (issuedMessageIds.contains(id)) {
            throw new IllegalStateException("message token cannot be reused, including after receive");
        }
        clock(actor);
        budget();
        Map<String, Long> published = tick(actor).clock();
        issuedMessageIds.add(id);
        messages.put(id, published);
    }

    public synchronized void receive(String participant, String messageId) {
        String id = key(messageId);
        String actor = key(participant);
        Map<String, Long> sent = messages.get(id);
        if (sent == null) throw new IllegalStateException("message token not published");
        Map<String, Long> destination = clock(actor);
        budget();
        merge(destination, sent);
        tick(actor);
        messages.remove(id);
    }

    /**
     * Reentrant ownership is by logical participant, never by carrier thread.
     * Only the final release publishes a happens-before edge to another owner.
     */
    public synchronized void release(String participant, String lockId) {
        String id = key(lockId);
        String actor = key(participant);
        if (!actor.equals(lockOwners.get(id))) {
            throw new IllegalStateException("release requires the current lock owner");
        }
        clock(actor);
        budget();
        int depth = lockDepths.get(id);
        if (depth > 1) {
            tick(actor);
            lockDepths.put(id, depth - 1);
        } else {
            locks.put(id, tick(actor).clock());
            lockOwners.remove(id);
            lockDepths.remove(id);
        }
    }

    public synchronized void acquire(String participant, String lockId) {
        String id = key(lockId);
        String actor = key(participant);
        String owner = lockOwners.get(id);
        if (owner != null && !owner.equals(actor)) {
            throw new IllegalStateException("lock is already acquired by another participant");
        }
        if (owner != null && lockDepths.get(id) == Integer.MAX_VALUE) {
            throw new IllegalStateException("reentrant lock depth exhausted");
        }
        clock(actor);
        budget();
        if (owner != null) {
            tick(actor);
            lockDepths.put(id, lockDepths.get(id) + 1);
        } else {
            Map<String, Long> published = locks.get(id);
            if (published != null) merge(clock(actor), published);
            tick(actor);
            lockOwners.put(id, actor);
            lockDepths.put(id, 1);
        }
    }

    /**
     * Fork a fresh logical task/message lifetime. The child inherits only
     * events preceding fork, never later parent or sibling operations.
     */
    public synchronized void fork(String parent, String child) {
        String ancestor = key(parent);
        String descendant = key(child);
        if (ancestor.equals(descendant) || clocks.containsKey(descendant)
                || forkParents.containsKey(descendant)
                || finishedParticipants.contains(descendant)) {
            throw new IllegalStateException("fork requires a fresh child participant");
        }
        clock(ancestor);
        if (clocks.size() >= MAX_PARTICIPANTS) {
            throw new IllegalStateException("race analyzer participant budget exhausted");
        }
        budget();
        Map<String, Long> inherited = tick(ancestor).clock();
        Map<String, Long> childClock = new LinkedHashMap<>(inherited);
        childClock.put(descendant, 1L);
        clocks.put(descendant, childClock);
        forkParents.put(descendant, ancestor);
    }

    /**
     * A completion event cannot be forged by join: only finish captures the
     * child's final vector clock. A finished lifetime cannot emit more events.
     */
    public synchronized void finish(String participant) {
        String child = key(participant);
        Map<String, Long> childClock = clocks.get(child);
        if (childClock == null || finishedParticipants.contains(child)) {
            throw new IllegalStateException("finish requires one live participant");
        }
        for (var held : lockOwners.entrySet()) {
            if (held.getValue().equals(child)) {
                throw new IllegalStateException("cannot finish while owning a lock");
            }
        }
        budget();
        Map<String, Long> completed = tick(child).clock();
        finishedParticipants.add(child);
        if (forkParents.containsKey(child)) {
            completedChildren.put(child, completed);
        }
    }

    /** Join a finished, directly forked child exactly once. */
    public synchronized void join(String parent, String child) {
        String ancestor = key(parent);
        String descendant = key(child);
        if (!ancestor.equals(forkParents.get(descendant))
                || !completedChildren.containsKey(descendant)
                || joinedChildren.contains(descendant)) {
            throw new IllegalStateException("join requires a finished direct child, exactly once");
        }
        Map<String, Long> destination = clock(ancestor);
        budget();
        merge(destination, completedChildren.get(descendant));
        tick(ancestor);
        joinedChildren.add(descendant);
        completedChildren.remove(descendant);
    }

    public synchronized List<Finding> findings() {
        return List.copyOf(findings);
    }

    public synchronized int eventCount() {
        return events;
    }

    /** Total observed conflicts; findings() is only the first 1000 examples. */
    public synchronized long conflictCount() {
        return conflictCount;
    }
}
