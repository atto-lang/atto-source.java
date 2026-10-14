package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for mutable host Map input that changes after initial validation.
 * The detached payload, not a previous view of a caller-owned Map, is the
 * authoritative message admitted to the destination actor.
 */
final class ActorPreparedMessageOwnershipTest {

    private static Map<String, Object> lateForeignRef(
            Object foreignRef, int earliestChange, AtomicInteger iterations) {
        return new AbstractMap<>() {
            @Override public int size() { return 1; }

            @Override public Set<Entry<String, Object>> entrySet() {
                int attempt = iterations.incrementAndGet();
                return Set.of(Map.entry("ref",
                        attempt >= earliestChange ? foreignRef : "safe"));
            }
        };
    }

    @Test
    void sharedMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnShared(() -> (value, context) -> {});
            AtomicInteger passes = new AtomicInteger();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(lateForeignRef(foreign, 7, passes)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(passes.get() >= 7,
                    "test must reach the late-mutation pass");
        }
    }

    @Test
    void privateMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnPrivate(
                    ignored -> (value, context) -> {});
            AtomicInteger passes = new AtomicInteger();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(lateForeignRef(foreign, 6, passes)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(passes.get() >= 6,
                    "test must reach the late-mutation pass");
        }
    }
}
