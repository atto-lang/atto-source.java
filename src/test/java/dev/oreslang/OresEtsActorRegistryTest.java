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
            // The reflected pin bypasses the registry's Pin.close() sweep;
            // explicitly trigger the same host-only reclamation path.
            // A reaper already in flight may have reclaimed it; avoid
            // asserting a schedule-dependent number of removed entries.
            registry.reap();
            assertTrue(map.isEmpty());
            registry.close();
        }
    }

    @Test
    void registryChurnUsesOneDaemonReaperAndDoesNotRetainCanceledTasks() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var executorField = OresEtsActorRegistry.class.getDeclaredField("HOST_REAPER");
            executorField.setAccessible(true);
            var executor = (java.util.concurrent.ScheduledThreadPoolExecutor)
                    executorField.get(null);
            var taskField = OresEtsActorRegistry.class.getDeclaredField("reaperTask");
            taskField.setAccessible(true);
            var countField = OresEtsActorRegistry.class.getDeclaredField("ACTIVE_HOST_REGISTRIES");
            countField.setAccessible(true);
            var active = (java.util.concurrent.atomic.AtomicInteger) countField.get(null);
            int baseline = active.get();
            for (int i = 0; i < 48; i++) {
                var registry = new OresEtsActorRegistry(runtime, 1);
                var task = (java.util.concurrent.ScheduledFuture<?>) taskField.get(registry);
                try {
                    assertEquals(baseline + 1, active.get());
                    assertEquals(0, registry.liveTableCount());
                } finally {
                    registry.close();
                }
                registry.close(); // idempotent, must not double-refund quota
                assertEquals(baseline, active.get());
                assertTrue(task.isCancelled());
                assertFalse(executor.getQueue().contains(task),
                        "closed registry must not leave a scheduled task in the shared queue");
            }
            assertTrue(executor.getPoolSize() <= 1,
                    "host ETS retirement must not allocate a thread per registry");
            assertTrue(executor.getRemoveOnCancelPolicy());
        }
    }

    @Test
    void tableChurnDoesNotAccumulateOwnerDoneFutureSubscribers() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 128)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var waitersField = dev.oreslang.runtime.OresFuture.class.getDeclaredField("waiters");
            waitersField.setAccessible(true);
            var doneWaiters = (java.util.Collection<?>) waitersField.get(owner.done());
            int before = doneWaiters.size();
            for (int i = 0; i < 96; i++) {
                var id = registry.<String, Long>create(owner, Access.PUBLIC, 1, 64);
                assertTrue(registry.insertNew(id, owner, "sequence", (long) i));
                registry.retire(id, owner);
            }
            assertEquals(before, doneWaiters.size(),
                    "retired tables must not pin owner completion listeners");
            assertEquals(0, registry.liveTableCount());
            var live = registry.<String, Long>create(owner, Access.PUBLIC, 1, 64);
            owner.stop();
            assertThrows(IllegalStateException.class,
                    () -> registry.lookup(live, owner, "sequence"));
            registry.reap();
            assertEquals(0, registry.liveTableCount());
        }
    }

    @Test
    void actorTerminationReapsWithoutPerTableCompletionCallbacks() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 64)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var handles = new java.util.ArrayList<OresEtsActorRegistry.TableId<String, Integer>>();
            for (int i = 0; i < 48; i++) {
                var id = registry.<String, Integer>create(owner, Access.PUBLIC, 1, 64);
                registry.insert(id, peer, "k", i);
                handles.add(id);
            }
            assertEquals(48, registry.liveTableCount());
            owner.stop();
            for (var id : handles) {
                assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "k"));
            }
            registry.reap();
            assertEquals(0, registry.liveTableCount());
        }
    }

    @Test
    void perTableOperationAdmissionIsBoundedWithoutLeakingRetirementPins() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Long>create(owner, Access.PRIVATE, 2, 128);
            var entriesField = OresEtsActorRegistry.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            var entries = (java.util.Map<?, ?>) entriesField.get(registry);
            Object entry = entries.get(id);
            var pin = entry.getClass().getDeclaredMethod("pin");
            var unpin = entry.getClass().getDeclaredMethod("unpin");
            var retire = entry.getClass().getDeclaredMethod("retire");
            pin.setAccessible(true);
            unpin.setAccessible(true);
            retire.setAccessible(true);

            for (int i = 0; i < 256; i++) pin.invoke(entry);
            var exhausted = assertThrows(
                    java.lang.reflect.InvocationTargetException.class, () -> pin.invoke(entry));
            assertInstanceOf(IllegalStateException.class, exhausted.getCause());
            assertEquals(0, registry.reap(), "in-flight table must not be reclaimed");
            retire.invoke(entry);
            assertEquals(0, registry.reap(), "retired table must wait for all admitted pins");
            assertSame(entry, entries.get(id));
            for (int i = 0; i < 255; i++) assertEquals(Boolean.FALSE, unpin.invoke(entry));
            assertEquals(0, registry.reap());
            assertEquals(Boolean.TRUE, unpin.invoke(entry));
            registry.reap(); // may already have been reclaimed by the daemon.
            assertTrue(entries.isEmpty());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, owner, "k"));
        }
    }

    @Test
    void actualActorStopRejectsHostOperationsUnderOneTenAndHundredWorkerContention()
            throws Exception {
        for (int workerCount : new int[]{1, 10, 100}) {
            try (var runtime = new ActorRuntime();
                 var registry = new OresEtsActorRegistry(runtime, 2)) {
                var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
                var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
                var id = registry.<String, Long>create(owner, Access.PUBLIC, 1, 128);
                registry.insert(id, owner, "counter", 0L);
                var begin = new CountDownLatch(1);
                var finished = new CountDownLatch(workerCount);
                var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
                for (int i = 0; i < workerCount; i++) {
                    Thread.ofPlatform().start(() -> {
                        try {
                            begin.await();
                            for (int j = 0; j < 50; j++) {
                                try {
                                    long value = registry.lookup(id, peer, "counter").orElseThrow();
                                    registry.compareExchange(id, peer, "counter", value, value + 1L);
                                } catch (IllegalStateException retired) {
                                    // STOP closed admission, or admission hit
                                    // the bounded host-operation quota.
                                    break;
                                }
                            }
                        } catch (Throwable failure) {
                            unexpected.add(failure);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                begin.countDown();
                owner.stop();
                assertTrue(finished.await(15, TimeUnit.SECONDS),
                        "host workers did not finish at contention " + workerCount);
                assertTrue(unexpected.isEmpty(), unexpected.toString());
                assertFalse(owner.isAlive());
                assertThrows(IllegalStateException.class,
                        () -> registry.lookup(id, peer, "counter"));
                registry.reap();
                assertEquals(0, registry.liveTableCount());
            }
        }
    }
}
