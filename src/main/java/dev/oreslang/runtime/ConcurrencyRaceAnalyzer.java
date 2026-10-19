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
        if (++events > MAX_EVENTS) throw new IllegalStateException("race analyzer event budget exhausted");
    }

    private Map<String, Long> clock(String participant) {
        String id = key(participant);
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
        budget();
        String loc = key(location);
        Access access = tick(participant);
        Location state = locations.computeIfAbsent(loc, ignored -> new Location());
        for (Access writer : state.writes.values()) {
            conflict(loc, writer, access, "write-read");
        }
        state.reads.put(participant, access);
    }

    public synchronized void write(String participant, String location) {
        budget();
        String loc = key(location);
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
        budget();
        String id = key(messageId);
        if (!issuedMessageIds.add(id)) {
            throw new IllegalStateException("message token cannot be reused, including after receive");
        }
        messages.put(id, tick(participant).clock());
    }

    public synchronized void receive(String participant, String messageId) {
        budget();
        Map<String, Long> sent = messages.remove(key(messageId));
        if (sent == null) throw new IllegalStateException("message token not published");
        merge(clock(participant), sent);
        tick(participant);
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
