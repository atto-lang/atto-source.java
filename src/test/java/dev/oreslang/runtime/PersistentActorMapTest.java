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
            Map<String, Object> snapshot = (i & 63) == 0 ? new HashMap<>(oracle) : null;
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
            if (snapshot != null) assertEquals(snapshot, before);
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
    void signedHashBranchesAndCollisionSubtreesRemainPersistentAfterRemovals() {
        assertTrue("zzzzzz".hashCode() < 0, "exercise negative signed hash");
        assertEquals("Aa".hashCode(), "BB".hashCode());

        var oracle = new HashMap<String, Object>();
        var keys = List.of("zzzzzz", "Aa", "BB", "111111111",
                "AAAAAAAA", "2147483648", "branch", "root");
        PersistentActorMap map = PersistentActorMap.empty();
        for (int i = 0; i < keys.size(); i++) {
            var prior = map;
            var priorSnapshot = new HashMap<>(oracle);
            map = map.with(keys.get(i), i);
            oracle.put(keys.get(i), i);
            assertEquals(priorSnapshot, prior,
                    "inserting across signed hash bits cannot change old roots");
            assertEquals(oracle, map);
            assertEquals(oracle.hashCode(), map.hashCode());
        }

        var completeRoot = map;
        for (String key : keys) {
            map = map.without(key);
            oracle.remove(key);
            assertEquals(oracle, map);
            assertEquals(keys.size(), completeRoot.size(),
                    "removing colliding descendants must preserve prior root");
        }
        assertSame(PersistentActorMap.empty(), map);
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
    void allMapMutationSurfacesRejectBeforeInvokingForeignCallbacks() {
        var empty = PersistentActorMap.empty();
        var map = empty.with("present", 7);
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(UnsupportedOperationException.class, empty::clear);
        assertThrows(UnsupportedOperationException.class, () -> empty.putAll(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> empty.remove("absent"));
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", 8));
        assertThrows(UnsupportedOperationException.class, () -> map.remove("present", 7));
        assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent("present", 4));
        assertThrows(UnsupportedOperationException.class, () -> map.replace("present", 9));
        assertThrows(UnsupportedOperationException.class, () -> map.replace("present", 7, 9));
        assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent("absent", k -> {
            called.set(true);
            return 3;
        }));
        assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent("present", (k, v) -> {
            called.set(true);
            return 3;
        }));
        assertThrows(UnsupportedOperationException.class, () -> map.compute("present", (k, v) -> {
            called.set(true);
            return 3;
        }));
        assertThrows(UnsupportedOperationException.class, () -> map.merge("present", 9, (a, b) -> {
            called.set(true);
            return 3;
        }));
        assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((k, v) -> {
            called.set(true);
            return 3;
        }));
        assertThrows(UnsupportedOperationException.class, () -> empty.entrySet().clear());
        assertThrows(UnsupportedOperationException.class, () -> empty.entrySet().remove(Map.entry("x", 7)));
        assertThrows(UnsupportedOperationException.class, () -> empty.keySet().remove("absent"));
        assertThrows(UnsupportedOperationException.class, () -> empty.values().remove(100));
        assertThrows(UnsupportedOperationException.class, () -> empty.keySet().clear());
        assertThrows(UnsupportedOperationException.class, () -> empty.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().removeIf(k -> {
            called.set(true);
            return false;
        }));
        assertThrows(UnsupportedOperationException.class, () -> map.values().removeIf(v -> {
            called.set(true);
            return false;
        }));
        assertFalse(called.get(), "a mutation callback must not run even once");
        assertSame(empty, PersistentActorMap.empty());
        assertEquals(7, map.get("present"));
    }

    @Test
    void persistentRootsCannotBypassActorMessageDepthOrNodeLimits() {
        PersistentActorMap nested = PersistentActorMap.empty();
        for (int i = 0; i < 300; i++) {
            nested = PersistentActorMap.empty().with("next", nested);
        }
        PersistentActorMap tooDeep = nested;
        var depthFailure = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));
        assertTrue(depthFailure.getMessage().contains("nesting depth"));

        PersistentActorMap wide = PersistentActorMap.empty();
        for (int i = 0; i < 51_000; i++) {
            wide = wide.with("key-" + i, i);
        }
        PersistentActorMap tooWide = wide;
        var nodeFailure = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooWide));
        assertTrue(nodeFailure.getMessage().contains("node count"));
    }

    @Test
    void identicalSharedRootsStillConsumePerMailboxQuotaAndReleaseOnDrain() throws Exception {
        var limited = new IsolatePolicy(IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024, 8192, java.time.Duration.ofSeconds(30));
        PersistentActorMap root = PersistentActorMap.empty();
        for (int i = 0; i < 5_000; i++) root = root.with("field-" + i, i);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnShared(limited, () -> (message, context) -> {
                entered.countDown();
                assertTrue(release.await(15, TimeUnit.SECONDS),
                        "held actor must only finish after the test releases it");
            });
            ref.send(root);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "actor did not enter its receive");
            long bytesAfterOne = runtime.sharedMemoryBytes();
            assertTrue(bytesAfterOne > 0, "the initial persistent root consumes a logical quota");
            boolean rejected = false;
            for (int i = 0; i < 120; i++) {
                try {
                    ref.send(root); // Exactly the same immutable object identity each time.
                } catch (IllegalStateException full) {
                    assertTrue(full.getMessage().contains("memory"),
                            "failure must be a logical mailbox memory quota: " + full);
                    rejected = true;
                    break;
                }
            }
            assertTrue(rejected, "zero-copy must not bypass the actor's logical mailbox quota");
            assertTrue(runtime.sharedMemoryBytes() > bytesAfterOne,
                    "queued copies must reserve distinct mailbox credits");
            release.countDown();
            ref.drain().get(10, TimeUnit.SECONDS);
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "processed messages must return all shared mailbox credits");
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void sharedPersistentMailboxCreditsAccountForLiveActorLocalArena() throws Exception {
        var limited = new IsolatePolicy(IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024, 8192, java.time.Duration.ofSeconds(30));
        PersistentActorMap root = PersistentActorMap.empty();
        for (int i = 0; i < 4_000; i++) root = root.with("field-" + i, i);
        PersistentActorMap messageRoot = root;
        var localAllocated = new java.util.concurrent.CountDownLatch(1);
        var releaseTurn = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<Object>spawnShared(limited, () -> (message, context) -> {
                assertSame(messageRoot, message,
                        "trusted SHARED delivery must preserve verified HAMT root identity");
                if (first.compareAndSet(false, true)) {
                    context.localMemory().orElseThrow().allocateActorLocalBytes(8L * 1024 * 1024);
                    localAllocated.countDown();
                    assertTrue(releaseTurn.await(15, TimeUnit.SECONDS));
                }
            });
            owner.send(messageRoot);
            assertTrue(localAllocated.await(5, TimeUnit.SECONDS));
            assertEquals(8L * 1024 * 1024, runtime.sharedActorLocalMemoryBytes());
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "SHARED actor-local arena bytes must never be charged to PRIVATE");

            boolean quotaDenied = false;
            for (int i = 0; i < 256; i++) {
                try {
                    owner.send(messageRoot);
                } catch (IllegalStateException denied) {
                    assertTrue(denied.getMessage().contains("shared actor mailbox memory limit"),
                            () -> "unexpected admission rejection: " + denied);
                    quotaDenied = true;
                    break;
                }
            }
            assertTrue(quotaDenied,
                    "retaining HAMT identity must not evade per-message credits or live arena bytes");
            assertEquals(8L * 1024 * 1024, runtime.sharedActorLocalMemoryBytes(),
                    "denied delivery must not release or increase live local arena credits");

            releaseTurn.countDown();
            owner.drain().get(10, TimeUnit.SECONDS);
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "drained immutable messages must return shared mailbox credits");
            assertEquals(8L * 1024 * 1024, runtime.sharedActorLocalMemoryBytes(),
                    "draining the mailbox does not retire actor-owned persistent memory");
            owner.stop();
            owner.getDone().get(5, TimeUnit.SECONDS);
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes(),
                    "actor retirement must release all local arena credits");
        } finally {
            releaseTurn.countDown();
        }
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
