package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for mutable host Map input that changes after initial validation.
 * The detached payload, not a previous view of a caller-owned Map, is the
 * authoritative message admitted to the destination actor.
 */
final class ActorPreparedMessageOwnershipTest {

    /**
     * Return the foreign reference precisely while ActorRuntime is copying
     * the sender-owned Map into the mailbox snapshot. Earlier preflight
     * traversals remain safe. Stack inspection is restricted to this
     * adversarial *test fixture*, never used by the runtime admission policy.
     */
    private static Map<String, Object> injectAtSnapshotCopy(
            Object foreignRef, AtomicBoolean copyObserved) {
        return new AbstractMap<>() {
            @Override public int size() { return 1; }

            @Override public Set<Entry<String, Object>> entrySet() {
                boolean snapshotCopy = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
                        frames.anyMatch(frame ->
                                frame.getClassName().equals(ActorRuntime.class.getName())
                                && (frame.getMethodName().equals("freeze")
                                    || frame.getMethodName().equals("isolateCopy"))
                                && frame.getMethodType().parameterCount() == 3));
                if (snapshotCopy) copyObserved.set(true);
                return Set.of(Map.entry("ref",
                        snapshotCopy ? foreignRef : "safe"));
            }
        };
    }

    /**
     * Every nested list stays below the per-container bound. Only their
     * combined, late-mutated snapshot exceeds the global graph node budget.
     */
    private static Map<String, Object> nestedGrowthDuringCopy(
            String copierName, AtomicBoolean expanded) {
        return new AbstractMap<>() {
            @Override public int size() { return 2; }

            @Override public Set<Entry<String, Object>> entrySet() {
                boolean copying = StackWalker.getInstance(
                        StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
                        frames.anyMatch(frame ->
                                frame.getClassName().equals(ActorRuntime.class.getName())
                                && frame.getMethodName().equals(copierName)
                                && frame.getMethodType().parameterCount() == 3));
                if (!copying) {
                    return Set.of(
                            Map.entry("left", "safe"),
                            Map.entry("right", "safe"));
                }
                expanded.set(true);
                var many = java.util.Collections.nCopies(60_000, "value");
                return Set.of(
                        Map.<String, Object>entry("left", many),
                        Map.<String, Object>entry("right", many));
            }
        };
    }

    @Test
    void frozenCopyEnforcesAggregateGraphQuotaAcrossNestedLists() {
        AtomicBoolean expanded = new AtomicBoolean();
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(nestedGrowthDuringCopy("freeze", expanded)));
        assertTrue(expanded.get(), "source must grow only inside deep copy");
        assertTrue(denied.getMessage().contains("maximum node count"),
                denied.getMessage());
    }

    @Test
    void privateCopyEnforcesAggregateGraphQuotaAcrossNestedLists() {
        AtomicBoolean expanded = new AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var sink = runtime.<Object>spawnPrivate(
                    ignored -> (message, context) -> {});
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(nestedGrowthDuringCopy("isolateCopy", expanded)));
            assertTrue(expanded.get(), "source must grow only inside private copy");
            assertTrue(denied.getMessage().contains("maximum node count"),
                    denied.getMessage());
        }
    }

    @Test
    void frozenCopyRejectsHostileMapThatExpandsOnlyDuringSnapshot() {
        AtomicBoolean expanded = new AtomicBoolean();
        Map<String, Integer> hostile = new AbstractMap<>() {
            @Override public int size() { return 1; }

            @Override public Set<Entry<String, Integer>> entrySet() {
                boolean copying = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
                        frames.anyMatch(frame ->
                                frame.getClassName().equals(ActorRuntime.class.getName())
                                && frame.getMethodName().equals("freeze")
                                // Exclude the public freeze(Object) preflight
                                // wrapper; only the 3-argument recursive copy
                                // is the authoritative snapshot phase.
                                && frame.getMethodType().parameterCount() == 3));
                if (!copying) return Set.of(Map.entry("safe", 1));
                expanded.set(true);
                // The source Map advertises one entry throughout preflight;
                // its malicious iterator grows only as the deep copy walks
                // it. Repeated keys deliberately violate Set semantics.
                return new java.util.AbstractSet<>() {
                    @Override public int size() { return 1; }
                    @Override public java.util.Iterator<Entry<String, Integer>> iterator() {
                        return new java.util.Iterator<>() {
                            private int produced;
                            @Override public boolean hasNext() { return produced <= 50_000; }
                            @Override public Entry<String, Integer> next() {
                                if (!hasNext()) throw new java.util.NoSuchElementException();
                                produced++;
                                return Map.entry("safe", 1);
                            }
                        };
                    }
                };
            }
        };
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));
        assertTrue(expanded.get(), "malicious iterator must run during deep copy");
        assertTrue(denied.getMessage().contains("node budget"), denied.getMessage());
    }

    @Test
    void privateMailboxRejectsHostileMapThatExpandsOnlyDuringSnapshot() {
        AtomicBoolean expanded = new AtomicBoolean();
        Map<String, Integer> hostile = new AbstractMap<>() {
            @Override public int size() { return 1; }

            @Override public Set<Entry<String, Integer>> entrySet() {
                boolean copying = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
                        frames.anyMatch(frame ->
                                frame.getClassName().equals(ActorRuntime.class.getName())
                                && frame.getMethodName().equals("isolateCopy")
                                && frame.getMethodType().parameterCount() == 3));
                if (!copying) return Set.of(Map.entry("safe", 1));
                expanded.set(true);
                return new java.util.AbstractSet<>() {
                    @Override public int size() { return 1; }
                    @Override public java.util.Iterator<Entry<String, Integer>> iterator() {
                        return new java.util.Iterator<>() {
                            private int produced;
                            @Override public boolean hasNext() { return produced <= 50_000; }
                            @Override public Entry<String, Integer> next() {
                                if (!hasNext()) throw new java.util.NoSuchElementException();
                                produced++;
                                return Map.entry("safe", 1);
                            }
                        };
                    }
                };
            }
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var sink = runtime.<Object>spawnPrivate(
                    ignored -> (message, context) -> {});
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class, () -> sink.send(hostile));
            assertTrue(expanded.get(),
                    "the source must expand only inside private actor copy");
            assertTrue(denied.getMessage().contains("node budget"), denied.getMessage());
        }
    }

    @Test
    void dataOnlyFreezeRejectsCapabilityInsertedDuringSnapshot() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(injectAtSnapshotCopy(ref, copyObserved)));
            assertTrue(copyObserved.get(),
                    "the hostile map must inject during the actual frozen snapshot");
            assertTrue(denied.getMessage().contains("data values only"),
                    denied.getMessage());
        }
    }

    @Test
    void sharedMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnShared(() -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(injectAtSnapshotCopy(foreign, copyObserved)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(copyObserved.get(),
                    "the hostile source Map must inject during frozen snapshot construction");
        }
    }

    @Test
    void privateMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnPrivate(
                    ignored -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(injectAtSnapshotCopy(foreign, copyObserved)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(copyObserved.get(),
                    "the hostile source Map must inject during private snapshot construction");
        }
    }
}
