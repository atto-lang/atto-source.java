package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supervisor quarantine is an independent lifetime owner for an actor whose
 * initial readiness failed. A synthetic quarantine record uses an actual
 * source-compiled SHARED actor and a separate pinned generation lease so
 * accounting and physical finalization are checked by the real runtimes.
 */
@Timeout(60)
final class LogicalSourceActorInitialSpawnQuarantineTest {
    private static final String ACTOR = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void failedInitialSpawnQuarantineRetainsPinUntilVerifiedPhysicalExit()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "quarantine-source-actor.ores", ACTOR);
            var router = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED,
                    "Counter", Duration.ofSeconds(5));
            var extra = generation.pinActorRoute();
            var leaseInstalled = false;

            var kind = Class.forName(
                    "dev.oreslang.runtime.LogicalSourceActorRouterV1$FailedInitialSpawn");
            Constructor<?> constructor = kind.getDeclaredConstructor(
                    HotReloadManager.ActorGenerationView.class,
                    HotReloadManager.Generation.ActorRouteLease.class);
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(
                    generation.linkActorGeneration("Counter"), extra);
            Field physical = kind.getDeclaredField("physical");
            physical.setAccessible(true);
            physical.set(pending, router.physicalRefForTesting());

            Field ledgerField = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedInitialSpawns");
            ledgerField.setAccessible(true);
            List<Object> ledger = (List<Object>) ledgerField.get(null);
            Field lockField = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedSpawnLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(null);
            Field pinsField = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            pinsField.setAccessible(true);
            try {
                synchronized (lock) {
                    ledger.add(pending);
                    leaseInstalled = true;
                }
                assertEquals(2, pinsField.getInt(generation),
                        "live route and quarantined spawn require separate leases");
                assertTrue(router.physicalRefForTesting().isAlive(),
                        "simulated failed-spawn physical actor remains executing");

                // A pre-registered reservation is NOT an aborted spawn. The
                // reaper must not cancel this actor or refund its image pin
                // before the startup caller explicitly transfers ownership.
                LogicalSourceActorRouterV1.reapFailedInitialSpawns();
                assertTrue(router.physicalRefForTesting().isAlive(),
                        "pre-handoff reaper cannot cancel a valid startup");
                assertEquals(2, pinsField.getInt(generation),
                        "pre-handoff reaper cannot debit an executing image");
                synchronized (lock) {
                    assertTrue(ledger.contains(pending));
                }
                Field failureHandoff = kind.getDeclaredField("retryableFailure");
                failureHandoff.setAccessible(true);
                failureHandoff.setBoolean(pending, true);

                // The returned router owns its own route lease, not the
                // quarantine lease. Closing the physical actor must not
                // release the second ownership token prematurely.
                router.close();
                assertEquals(1, pinsField.getInt(generation));
                synchronized (lock) {
                    assertTrue(ledger.contains(pending));
                }

                // A corrupted pin debit must leave the quarantined owner
                // retryable instead of consuming its token on failure.
                pinsField.setInt(generation, 0);
                try {
                    assertThrows(IllegalStateException.class,
                            LogicalSourceActorRouterV1::reapFailedInitialSpawns);
                    synchronized (lock) {
                        assertTrue(ledger.contains(pending));
                    }
                } finally {
                    pinsField.setInt(generation, 1);
                }
                assertTrue(LogicalSourceActorRouterV1.reapFailedInitialSpawns() >= 1);
                synchronized (lock) {
                    assertFalse(ledger.contains(pending));
                }
                assertEquals(0, pinsField.getInt(generation));
            } finally {
                router.close();
                if (leaseInstalled) {
                    synchronized (lock) {
                        ledger.remove(pending);
                    }
                }
                extra.close();
            }
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void preregisteredSpawnWithoutPhysicalRefIsNeverMistakenForFailure()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "quarantine-unadmitted.ores", ACTOR);
            var view = generation.linkActorGeneration("Counter");
            var pin = generation.pinActorRoute();
            Class<?> kind = Class.forName(
                    "dev.oreslang.runtime.LogicalSourceActorRouterV1$FailedInitialSpawn");
            var ctor = kind.getDeclaredConstructor(
                    HotReloadManager.ActorGenerationView.class,
                    HotReloadManager.Generation.ActorRouteLease.class);
            ctor.setAccessible(true);
            Object pending = ctor.newInstance(view, pin);
            var field = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedInitialSpawns");
            field.setAccessible(true);
            List<Object> ledger = (List<Object>) field.get(null);
            var lockField = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedSpawnLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(null);
            var accounting = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            accounting.setAccessible(true);
            synchronized (lock) {
                ledger.add(pending);
            }
            try {
                // Pre-admission: the registered record has no physical handle,
                // but the startup caller still owns the generation lease.
                LogicalSourceActorRouterV1.reapFailedInitialSpawns();
                assertEquals(1, accounting.getInt(generation),
                        "unpublished startup reservation is never a reclaimable owner");
                synchronized (lock) {
                    assertTrue(ledger.contains(pending));
                }
                // Explicitly hand over the failed-startup owner. Only now is
                // a no-physical-handle record eligible for safe release.
                var failure = kind.getDeclaredField("retryableFailure");
                failure.setAccessible(true);
                failure.setBoolean(pending, true);
                assertTrue(LogicalSourceActorRouterV1.reapFailedInitialSpawns() >= 1);
                assertEquals(0, accounting.getInt(generation));
                synchronized (lock) {
                    assertFalse(ledger.contains(pending));
                }
            } finally {
                synchronized (lock) {
                    ledger.remove(pending);
                }
                pin.close();
            }
        }
    }


    @Test
    void realInitialSpawnReadyTimeoutDoesNotOrphanGenerationPin()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "timeout-real-initial-spawn.ores", ACTOR);
            var view = generation.linkActorGeneration("Counter");
            ActorRuntime runtime = view.runtime();
            Field dispatcherField =
                    ActorRuntime.class.getDeclaredField("sharedDispatcher");
            dispatcherField.setAccessible(true);
            var original = (java.util.concurrent.ExecutorService)
                    dispatcherField.get(runtime);
            var withheld = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
            var blackHole = new java.util.concurrent.AbstractExecutorService() {
                private volatile boolean shut;
                @Override public void execute(Runnable command) {
                    if (shut) throw new java.util.concurrent.RejectedExecutionException();
                    withheld.add(command);
                }
                @Override public void shutdown() { shut = true; }
                @Override public java.util.List<Runnable> shutdownNow() {
                    shut = true;
                    return java.util.List.of();
                }
                @Override public boolean isShutdown() { return shut; }
                @Override public boolean isTerminated() { return shut; }
                @Override public boolean awaitTermination(
                        long timeout, java.util.concurrent.TimeUnit unit) {
                    return shut;
                }
            };
            Field pinsField = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            pinsField.setAccessible(true);
            Field ledgerField = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedInitialSpawns");
            ledgerField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ledger = (java.util.List<Object>) ledgerField.get(null);
            Field lockField = LogicalSourceActorRouterV1.class
                    .getDeclaredField("failedSpawnLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(null);

            dispatcherField.set(runtime, blackHole);
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> LogicalSourceActorRouterV1.spawn(
                                generation, ActorRuntime.ActorKind.SHARED,
                                "Counter", Duration.ofMillis(100)));
                assertFalse(withheld.isEmpty(),
                        "real SHARED source actor dispatch must be withheld");
                synchronized (lock) {
                    for (Object owner : ledger) {
                        Field ownerView = owner.getClass().getDeclaredField("view");
                        ownerView.setAccessible(true);
                        if (!(ownerView.get(owner)
                                instanceof HotReloadManager.ActorGenerationView pendingView)
                                || pendingView.generation() != generation) continue;
                        Field eligible =
                                owner.getClass().getDeclaredField("retryableFailure");
                        eligible.setAccessible(true);
                        assertTrue(eligible.getBoolean(owner),
                                "a failed startup must hand physical cleanup to its reaper");
                        Field physical = owner.getClass().getDeclaredField("physical");
                        physical.setAccessible(true);
                        Object ref = physical.get(owner);
                        assertNotNull(ref,
                                "a timeout after runtime admission retains its physical identity");
                        assertEquals(1, pinsField.getInt(generation),
                                "unfinished physical actor must keep its executable generation pin");
                    }
                }
            } finally {
                // Restore the genuine dispatcher BEFORE draining any saved
                // work; teardown may schedule additional trusted callbacks.
                dispatcherField.set(runtime, original);
                blackHole.shutdownNow();
                Runnable pending;
                while ((pending = withheld.poll()) != null) pending.run();
            }

            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
            while (pinsField.getInt(generation) != 0
                    && System.nanoTime() < deadline) {
                LogicalSourceActorRouterV1.reapFailedInitialSpawns();
                Thread.sleep(5);
            }
            assertEquals(0, pinsField.getInt(generation),
                    "failed real initial spawn cannot permanently strand code lease");
            assertEquals(0, LogicalSourceActorRouterV1.reapFailedInitialSpawns(),
                    "a recovered generation pin cannot be refunded twice");
        }
    }


    @Test
    void realCompiledInitialSpawnRegistryFailureAfterInsertionRefundsGenerationPin()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "real-initial-post-register-fault.ores", ACTOR);
            var runtime = generation.linkActorGeneration("Counter").runtime();
            Field registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> original =
                    (java.util.Map<Object, Object>) registryField.get(runtime);
            assertTrue(original.isEmpty());
            var injected = new java.util.concurrent.atomic.AtomicBoolean(true);
            var mutated = new java.util.concurrent.ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object previous = super.put(key, value);
                    if (injected.getAndSet(false))
                        throw new OutOfMemoryError(
                                "source actor registry failure after insertion");
                    return previous;
                }
            };
            Field pins = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            pins.setAccessible(true);
            registryField.set(runtime, mutated);
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> LogicalSourceActorRouterV1.spawn(
                                generation, ActorRuntime.ActorKind.SHARED,
                                "Counter", Duration.ofSeconds(5)));
                assertTrue(mutated.isEmpty(),
                        "failed compiler-linked physical actor must be unregistered");
                assertEquals(0, pins.getInt(generation),
                        "failed pre-return physical admission must not strand a route pin");
                assertEquals(0, LogicalSourceActorRouterV1.reapFailedInitialSpawns(),
                        "a finished failed construction cannot be retired twice");
            } finally {
                registryField.set(runtime, original);
            }
            try (var recovered = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED,
                    "Counter", Duration.ofSeconds(5))) {
                recovered.ref().send(41L);
                var output = recovered.ref().nextOutput(Duration.ofSeconds(5));
                assertFalse(output.done());
                assertEquals(42L,
                        assertInstanceOf(Long.class, output.value().value()));
            }
            assertEquals(0, pins.getInt(generation));
        }
    }
}
