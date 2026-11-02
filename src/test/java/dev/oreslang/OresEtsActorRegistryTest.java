package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresEtsActorRegistry;
import dev.oreslang.runtime.OresEtsActorRegistry.Access;
import dev.oreslang.runtime.OresSymbol;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Tests actual ActorRuntime-issued identities, not forged ActorId strings. */
final class OresEtsActorRegistryTest {
    @Test
    void privateProtectedAndPublicAuthorization() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 4)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var privateId = registry.<Object, Integer>create(owner, Access.PRIVATE, 2, 1024);
            assertTrue(registry.insertNew(privateId, owner, OresSymbol.process("ready"), 1));
            assertEquals(1, registry.lookup(privateId, owner, OresSymbol.process("ready")).orElseThrow());
            assertThrows(SecurityException.class, () -> registry.lookup(privateId, peer, OresSymbol.process("ready")));
            assertThrows(SecurityException.class, () -> registry.insert(privateId, peer, "x", 7));

            var protectedId = registry.<String, Integer>create(owner, Access.PROTECTED, 2, 1024);
            assertTrue(registry.insertNew(protectedId, owner, "score", 2));
            assertEquals(2, registry.lookup(protectedId, peer, "score").orElseThrow());
            assertThrows(SecurityException.class, () -> registry.take(protectedId, peer, "score"));
            assertThrows(SecurityException.class, () -> registry.compareExchange(protectedId, peer, "score", 2, 3));

            var publicId = registry.<String, Integer>create(owner, Access.PUBLIC, 2, 1024);
            assertTrue(registry.insertNew(publicId, peer, "score", 7));
            assertTrue(registry.compareExchange(publicId, peer, "score", 7, 8));
            assertEquals(8, registry.lookup(publicId, owner, "score").orElseThrow());
        }
    }

    @Test
    void foreignRuntimeCannotBorrowOwnerOrTableCapability() {
        try (var a = new ActorRuntime();
             var b = new ActorRuntime();
             var first = new OresEtsActorRegistry(a, 2);
             var second = new OresEtsActorRegistry(b, 2)) {
            var ownerA = a.<String>spawnShared(() -> (message, context) -> {});
            var actorB = b.<String>spawnShared(() -> (message, context) -> {});
            assertThrows(SecurityException.class, () -> first.create(actorB, Access.PUBLIC, 1, 64));
            var id = first.<String, String>create(ownerA, Access.PUBLIC, 2, 64);
            assertThrows(SecurityException.class, () -> first.lookup(id, actorB, "x"));
            assertThrows(SecurityException.class, () -> second.lookup(id, actorB, "x"));
            assertEquals(1, first.liveTableCount());
        }
    }

    @Test
    void quotaRetirementAndStaleHandleFailClosed() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var token = registry.<String, String>create(owner, Access.PRIVATE, 1, 4);
            assertTrue(registry.insertNew(token, owner, "a", "b"));
            assertEquals(2L, registry.byteSize(token, owner));
            assertThrows(IllegalStateException.class,
                    () -> registry.create(owner, Access.PRIVATE, 1, 4));
            registry.retire(token, owner);
            assertThrows(IllegalStateException.class, () -> registry.lookup(token, owner, "a"));
            assertEquals(0, registry.liveTableCount());
            var second = registry.<String, String>create(owner, Access.PRIVATE, 1, 4);
            assertNotSame(token, second);
            assertTrue(registry.insertNew(second, owner, "a", "b"));
            assertEquals(1, registry.liveTableCount());
        }
    }

    @Test
    void ownerStopRevokesAllAccessAndReclaimsTableOnHost() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, String>create(owner, Access.PUBLIC, 1, 128);
            registry.insert(id, owner, "key", "value");
            owner.stop();
            assertFalse(owner.isAlive());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
            // The daemon reaper may have already reclaimed it: neither
            // scheduling order should make this test nondeterministic.
            registry.reap();
            assertEquals(0, registry.liveTableCount());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
            var replacement = registry.<String, String>create(peer, Access.PRIVATE, 1, 128);
            assertNotSame(id, replacement);
        }
    }

    @Test
    void actorCarrierCannotCallHostEtsOrReaper() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Integer>create(owner, Access.PUBLIC, 1, 128);
            var checked = new CountDownLatch(1);
            var rejected = new AtomicBoolean();
            var worker = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    boolean ops = assertThrows(IllegalStateException.class,
                            () -> registry.lookup(id, context.self(), "key")) != null;
                    boolean cleanup = assertThrows(IllegalStateException.class,
                            registry::reap) != null;
                    rejected.set(ops && cleanup);
                } finally {
                    checked.countDown();
                }
            });
            worker.send("ping");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(rejected.get());
        }
    }

    @Test
    void closedRegistryRevokesEveryHandle() {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var registry = new OresEtsActorRegistry(runtime, 1);
            var id = registry.<String, String>create(owner, Access.PUBLIC, 2, 64);
            registry.close();
            registry.close();
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, owner, "key"));
            assertThrows(IllegalStateException.class, () -> registry.create(owner, Access.PUBLIC, 1, 64));
        }
    }

    @Test
    void publicWriteDoesNotGrantLifecycleAuthority() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 2)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Integer>create(owner, Access.PUBLIC, 2, 128);
            registry.insert(id, peer, "key", 1);
            assertThrows(SecurityException.class, () -> registry.retire(id, peer));
            assertEquals(1, registry.lookup(id, owner, "key").orElseThrow());
            assertEquals(java.util.Map.of("key", 1), registry.snapshot(id, peer));
            registry.retire(id, owner);
            assertThrows(IllegalStateException.class, () -> registry.retire(id, owner));
        }
    }

    @Test
    void concurrentTableCreationIsBoundedAndCannotReuseRetiredIdentity() throws Exception {
        final int attempts = 32;
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 3)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(attempts);
            var created = new java.util.concurrent.ConcurrentLinkedQueue<OresEtsActorRegistry.TableId<String, String>>();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int n = 0; n < attempts; n++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        created.add(registry.<String, String>create(owner, Access.PRIVATE, 1, 64));
                    } catch (IllegalStateException full) {
                        // Table count quota reached, fail closed.
                    } catch (Throwable error) {
                        failures.add(error);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(3, created.size());
            assertEquals(3, registry.liveTableCount());
            var previous = created.peek();
            registry.retire(previous, owner);
            var next = registry.<String, String>create(owner, Access.PRIVATE, 1, 64);
            assertNotSame(previous, next);
            assertThrows(IllegalStateException.class, () -> registry.lookup(previous, owner, "key"));
            assertEquals(3, registry.liveTableCount());
        }
    }

    /**
     * Test-only inspection of the production pin state. This deliberately
     * does not introduce a guest-visible or public host lease API.
     */
    @Test
    void tableRetirementWaitsForAdmittedHostPinBeforePhysicalReclaim() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, String>create(owner, Access.PUBLIC, 1, 64);
            registry.insert(id, owner, "key", "value");

            var entriesField = OresEtsActorRegistry.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            var entries = (java.util.Map<?, ?>) entriesField.get(registry);
            Object entry = entries.get(id);
            assertNotNull(entry);
            var pin = entry.getClass().getDeclaredMethod("pin");
            var unpin = entry.getClass().getDeclaredMethod("unpin");
            pin.setAccessible(true);
            unpin.setAccessible(true);

            pin.invoke(entry); // Mimic an already-authorized in-flight host call.
            try {
                owner.stop();
                assertFalse(owner.isAlive());
                assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
                assertEquals(0, registry.reap(), "must retain physical storage while pinned");
                assertSame(entry, entries.get(id));
                assertEquals(1, registry.liveTableCount(), "a pinned retired table still occupies quota");
                var denied = assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> pin.invoke(entry),
                        "retired tables must reject new admission");
                assertInstanceOf(IllegalStateException.class, denied.getCause());
            } finally {
                assertEquals(Boolean.TRUE, unpin.invoke(entry));
            }
            registry.reap(); // A scheduler reaper may already have reclaimed.
            assertEquals(0, registry.liveTableCount());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
        }
    }

    @Test
    void retiredTableDoesNotReopenAfterSlotReuseOrConcurrentReaders() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 2)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var reader = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Long>create(owner, Access.PUBLIC, 1, 128);
            registry.insert(id, owner, "value", 10L);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(16);
            var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < 16; i++) {
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 200; j++) {
                            try {
                                var observed = registry.lookup(id, reader, "value");
                                if (observed.isPresent() && observed.get() != 10L) {
                                    throw new AssertionError("corrupt value");
                                }
                            } catch (IllegalStateException retired) {
                                break; // post-retirement operations fail closed.
                            }
                        }
                    } catch (Throwable error) {
                        unexpected.add(error);
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            registry.retire(id, owner);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(unexpected.isEmpty(), unexpected.toString());
            registry.reap();
            var newer = registry.<String, Long>create(owner, Access.PUBLIC, 1, 128);
            registry.insert(newer, owner, "value", 99L);
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, reader, "value"));
            assertEquals(99L, registry.lookup(newer, reader, "value").orElseThrow());
        }
    }

    @Test
    void unbalancedPinCannotReopenRetiredCapability() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, String>create(owner, Access.PRIVATE, 1, 128);
            var mapField = OresEtsActorRegistry.class.getDeclaredField("entries");
            mapField.setAccessible(true);
            var map = (java.util.Map<?, ?>) mapField.get(registry);
            Object entry = map.get(id);
            var retire = entry.getClass().getDeclaredMethod("retire");
            var pin = entry.getClass().getDeclaredMethod("pin");
            var unpin = entry.getClass().getDeclaredMethod("unpin");
            retire.setAccessible(true);
            pin.setAccessible(true);
            unpin.setAccessible(true);
            retire.invoke(entry);
            var underflow = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> unpin.invoke(entry));
            assertInstanceOf(AssertionError.class, underflow.getCause());
            var denied = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> pin.invoke(entry));
            assertInstanceOf(IllegalStateException.class, denied.getCause());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, owner, "key"));
        }
    }

    @Test
    void actualActorTurnSelfStopRevokesPublicTableWithoutCarrierParking() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 2)) {
            var actorTurnExited = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                // Actors must not enter this host reference, but may stop
                // themselves without waiting for the host-owned reaper.
                context.self().stop();
                actorTurnExited.countDown();
            });
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, String>create(actor, Access.PUBLIC, 4, 128);
            registry.insert(id, peer, "status", "alive");
            actor.send("stop");
            assertTrue(actorTurnExited.await(5, TimeUnit.SECONDS));
            assertFalse(actor.isAlive());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "status"));
            registry.reap();
            assertEquals(0, registry.liveTableCount());
        }
    }

    @Test
    void actualActorCancellationRevokesOwnerTableAndDoesNotReuseCapability() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 2)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Long>create(owner, Access.PUBLIC, 2, 128);
            assertTrue(registry.insertNew(id, peer, "generation", 1L));
            assertTrue(owner.cancel());
            assertFalse(owner.isAlive());
            assertThrows(IllegalStateException.class, () -> registry.insert(id, peer, "generation", 2L));
            registry.reap();
            var replacement = registry.<String, Long>create(peer, Access.PRIVATE, 2, 128);
            assertTrue(registry.insertNew(replacement, peer, "generation", 3L));
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "generation"));
        }
    }

    @Test
    void registryShutdownCannotReclaimStorageUntilAdmittedPinIsReleased() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var registry = new OresEtsActorRegistry(runtime, 1);
            var id = registry.<String, String>create(owner, Access.PRIVATE, 2, 128);
            var mapField = OresEtsActorRegistry.class.getDeclaredField("entries");
            mapField.setAccessible(true);
            var map = (java.util.Map<?, ?>) mapField.get(registry);
            Object entry = map.get(id);
            var pin = entry.getClass().getDeclaredMethod("pin");
            var unpin = entry.getClass().getDeclaredMethod("unpin");
            pin.setAccessible(true);
            unpin.setAccessible(true);
            pin.invoke(entry);
            try {
                registry.close();
                assertSame(entry, map.get(id), "shutdown must preserve pinned storage");
                assertThrows(IllegalStateException.class, () -> registry.lookup(id, owner, "x"));
                var denied = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> pin.invoke(entry));
                assertInstanceOf(IllegalStateException.class, denied.getCause());
            } finally {
                assertEquals(Boolean.TRUE, unpin.invoke(entry));
            }
            // Production unpin is followed by the registry's owner Pin.close()
            // reaper callback. This reflected pin must instead be explicitly
            // swept by a non-carrier host check while close() stays idempotent.
            registry.close();
        }
    }
}
