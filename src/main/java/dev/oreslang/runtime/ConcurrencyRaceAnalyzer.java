package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded happens-before race analysis for *instrumented* logical executions.
 *
 * A caller must provide all mutable memory reads/writes and synchronization
 * events. Merely enabling this analyzer without compiler/runtime access hooks
 * cannot establish race freedom. Logical participant IDs survive carrier
 * migration; JVM thread IDs are deliberately not used as ownership IDs.
 */
public final class ConcurrencyRaceAnalyzer {
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_FINDINGS = 1_000;

    public record Finding(String location, String priorParticipant,
                          String currentParticipant, String conflict) { }

    private record Access(String participant, Map<String, Long> clock) { }

    private static final class Location {
        Access lastWrite;
        final Map<String, Access> reads = new HashMap<>();
    }

    private final Map<String, Map<String, Long>> clocks = new HashMap<>();
    private final Map<String, Location> locations = new HashMap<>();
    private final Map<String, Map<String, Long>> messages = new HashMap<>();
    private final Map<String, Map<String, Long>> locks = new HashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private int events;

    private static String key(String name) {
        Objects.requireNonNull(name, "identity");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("event identity must contain 1..128 characters");
        }
        return name;
    }

    private void budget() {
        if (++events > MAX_EVENTS) throw new IllegalStateException("race analyzer event budget exhausted");
    }

    private Map<String, Long> clock(String participant) {
        return clocks.computeIfAbsent(key(participant), ignored -> new LinkedHashMap<>());
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
        if (previous != null && !happensBefore(previous.clock(), current.clock())
                && findings.size() < MAX_FINDINGS) {
            findings.add(new Finding(location, previous.participant(), current.participant(), kind));
        }
    }

    public synchronized void read(String participant, String location) {
        budget();
        String loc = key(location);
        Access access = tick(participant);
        Location state = locations.computeIfAbsent(loc, ignored -> new Location());
        conflict(loc, state.lastWrite, access, "write-read");
        state.reads.put(participant, access);
    }

    public synchronized void write(String participant, String location) {
        budget();
        String loc = key(location);
        Access access = tick(participant);
        Location state = locations.computeIfAbsent(loc, ignored -> new Location());
        conflict(loc, state.lastWrite, access, "write-write");
        for (Access reader : state.reads.values()) {
            conflict(loc, reader, access, "read-write");
        }
        state.reads.clear();
        state.lastWrite = access;
    }

    /** One unique, single-consumer message token, not a reusable channel name. */
    public synchronized void send(String participant, String messageId) {
        budget();
        String id = key(messageId);
        if (messages.containsKey(id)) throw new IllegalStateException("message token already published");
        messages.put(id, tick(participant).clock());
    }

    public synchronized void receive(String participant, String messageId) {
        budget();
        Map<String, Long> sent = messages.remove(key(messageId));
        if (sent == null) throw new IllegalStateException("message token not published");
        merge(clock(participant), sent);
        tick(participant);
    }

    public synchronized void release(String participant, String lockId) {
        budget();
        locks.put(key(lockId), tick(participant).clock());
    }

    public synchronized void acquire(String participant, String lockId) {
        budget();
        Map<String, Long> published = locks.get(key(lockId));
        if (published != null) merge(clock(participant), published);
        tick(participant);
    }

    public synchronized List<Finding> findings() {
        return List.copyOf(findings);
    }

    public synchronized int eventCount() {
        return events;
    }
}
