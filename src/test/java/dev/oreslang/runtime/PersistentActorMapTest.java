package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class PersistentActorMapTest {
    @Test
    void pathCopyAndHashCollisionsKeepPriorVersions() {
        var empty = PersistentActorMap.empty();
        var a = empty.with("Aa", 1);
        var b = a.with("BB", 2); // Same String.hashCode as Aa.
        var c = b.with("Aa", 3);
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertSame(a, a.with("Aa", 1));
        assertEquals(0, empty.size());
        assertEquals(1, a.size());
        assertEquals(2, b.size());
        assertEquals(1, a.get("Aa"));
        assertEquals(1, b.get("Aa"));
        assertEquals(2, b.get("BB"));
        assertEquals(3, c.get("Aa"));
        assertEquals(2, c.size());
        assertEquals(1, c.without("Aa").size());
        assertEquals(2, c.without("Aa").get("BB"));
        assertSame(empty, empty.without("missing"));
        assertSame(empty, c.without("Aa").without("BB"));
        assertThrows(UnsupportedOperationException.class, () -> b.put("escape", 9));
        assertThrows(UnsupportedOperationException.class,
                () -> b.entrySet().iterator().next().setValue(123));
    }

    @Test
    void randomizedUpdatesMatchStandardMapIncludingUpperHashBits() {
        var random = new Random(81271);
        var oracle = new HashMap<String, Object>();
        PersistentActorMap map = PersistentActorMap.empty();
        for (int i = 0; i < 12_000; i++) {
            String key = "key-" + random.nextInt(1800);
            PersistentActorMap before = map;
            Map<String, Object> snapshot = new HashMap<>(oracle);
            if (random.nextInt(4) == 0) {
                map = map.without(key);
                oracle.remove(key);
            } else {
                int val = random.nextInt();
                map = map.with(key, val);
                oracle.put(key, val);
            }
            assertEquals(oracle.size(), map.size());
            assertEquals(oracle.get(key), map.get(key));
            assertEquals(snapshot, before);
        }
        assertEquals(oracle, map);
        assertEquals(oracle.keySet(), map.keySet());
        for (var entry : oracle.entrySet()) {
            assertTrue(map.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), map.get(entry.getKey()));
        }
        assertFalse(map.containsKey(new Object()));
        assertNull(map.get(new Object()));
    }

    @Test
    void hashFloodFailsClosedWhileOldRootRemainsUsable() {
        // Every fixed-length combination of Aa/BB has the same String hash.
        PersistentActorMap map = PersistentActorMap.empty();
        for (int n = 0; n < 64; n++) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 7; i++)
                key.append(((n >>> i) & 1) == 0 ? "Aa" : "BB");
            map = map.with(key.toString(), n);
        }
        assertEquals(64, map.size());
        var before = map;
        assertThrows(IllegalArgumentException.class,
                () -> before.with("AaAaAaAaAaAaBB", 65));
        assertEquals(64, before.size());
        assertEquals(0, before.get("AaAaAaAaAaAaAa"));
    }

    @Test
    void onlyImmutableDataMayBecomeSharedPersistentValues() {
        var leaf = PersistentActorMap.empty().with("v", new BigDecimal("1.25"));
        var parent = PersistentActorMap.empty().with("nested", leaf);
        assertSame(leaf, parent.get("nested"));
        assertThrows(IllegalArgumentException.class, () -> parent.with("mutable", new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> parent.with("array", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> parent.with("map", Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> parent.with("foreign", new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> parent.with("subclass", new java.math.BigInteger("2") { }));
        assertThrows(IllegalArgumentException.class, () -> parent.with("null", null));
        assertThrows(NullPointerException.class, () -> parent.with(null, 2));
        assertThrows(NullPointerException.class, () -> parent.without(null));
        assertSame(parent, ActorRuntime.freeze(parent));
        assertSame(leaf, ActorRuntime.freeze(leaf));
    }

    @Test
    void sharedMailboxPreservesRootButPrivateMailboxStillIsolates() throws Exception {
        var payload = PersistentActorMap.empty()
                .with("id", "pipeline-1")
                .with("nested", PersistentActorMap.empty().with("score", 3));
        AtomicReference<Object> sharedSeen = new AtomicReference<>();
        AtomicReference<Object> privateSeen = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.<Object>spawnShared(() ->
                    (message, context) -> sharedSeen.set(message));
            var isolated = runtime.<Object>spawnPrivate(() ->
                    (message, context) -> privateSeen.set(message));
            shared.send(payload);
            isolated.send(payload);
            shared.drain().get(5, TimeUnit.SECONDS);
            isolated.drain().get(5, TimeUnit.SECONDS);
            assertSame(payload, sharedSeen.get(),
                    "only runtime-validated immutable data uses zero-copy shared transport");
            assertInstanceOf(Map.class, privateSeen.get());
            assertNotSame(payload, privateSeen.get(),
                    "private actor contract must not silently change");
            assertEquals(payload, privateSeen.get());
            var newer = payload.with("id", "pipeline-2");
            assertEquals("pipeline-1", ((Map<?, ?>) sharedSeen.get()).get("id"));
            assertEquals("pipeline-2", newer.get("id"));
            shared.stop();
            isolated.stop();
            shared.getDone().get(5, TimeUnit.SECONDS);
            isolated.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
