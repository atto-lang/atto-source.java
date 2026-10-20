package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real compiler-linked tests of both source-router ownership handoffs. An
 * ActorCell becomes registered just before its spawn method throws; its first
 * rollback removal also throws BEFORE mutation. The supervisor must never
 * treat the absent return value as proof that the physical actor never existed.
 */
@Timeout(90)
final class LogicalSourceActorPreReturnHandoffTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String OLD = """
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
    private static final String NEW = OLD.replace(
            "self.count = self.count + mail.value;",
            "self.count = self.count + mail.value + 1;");

    private static Field reflect(Class<?> type, String field) throws Exception {
        Field result = type.getDeclaredField(field);
        result.setAccessible(true);
        return result;
    }

    private static final class AfterInsertAndFailedRemoval
            extends ConcurrentHashMap<Object, Object> {
        private final AtomicBoolean failPut = new AtomicBoolean(true);
        private final AtomicBoolean failRemove = new AtomicBoolean(true);

        @Override public Object put(Object key, Object value) {
            Object old = super.put(key, value);
            if (failPut.getAndSet(false))
                throw new OutOfMemoryError("injected post-registration failure");
            return old;
        }

        @Override public boolean remove(Object key, Object value) {
            if (failRemove.getAndSet(false))
                throw new OutOfMemoryError("injected pre-removal failure");
            return super.remove(key, value);
        }

        void requireFaultsFired() {
            assertFalse(failPut.get(), "fixture did not reach registry admission");
            assertFalse(failRemove.get(), "fixture did not reach failed rollback");
        }
    }

    private static void awaitRetirement(
            HotReloadManager.Generation generation,
            ActorRuntime runtime,
            AfterInsertAndFailedRemoval registry,
            Runnable reap) throws Exception {
        Field pinsField = reflect(HotReloadManager.Generation.class, "actorRoutePins");
        AtomicInteger actorCount = (AtomicInteger) reflect(
                ActorRuntime.class, "actorCount").get(runtime);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((pinsField.getInt(generation) != 0
                || actorCount.get() != 0
                || !registry.isEmpty())
                && System.nanoTime() < deadline) {
            reap.run();
            Thread.sleep(5);
        }
        assertEquals(0, actorCount.get(),
                "a failed pre-return actor must refund its physical runtime slot");
        assertTrue(registry.isEmpty(),
                "a failed pre-return actor must leave no ghost registered physical cell");
        assertEquals(0, pinsField.getInt(generation),
                "source supervisor must retain and eventually debit exactly one code lease");
    }

    @Test
    void initialSourceSpawnCanRetireActorWithNoReturnedPhysicalReference()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "pre-return-initial.ores", OLD);
            var runtime = generation.linkActorGeneration("Counter").runtime();
            Field field = reflect(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original = (Map<Object, Object>) field.get(runtime);
            assertTrue(original.isEmpty());
            var faulty = new AfterInsertAndFailedRemoval();
            field.set(runtime, faulty);
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> LogicalSourceActorRouterV1.spawn(
                                generation, ActorRuntime.ActorKind.SHARED,
                                "Counter", WAIT));
                faulty.requireFaultsFired();
                awaitRetirement(generation, runtime, faulty,
                        LogicalSourceActorRouterV1::reapFailedInitialSpawns);
                assertEquals(0, LogicalSourceActorRouterV1.reapFailedInitialSpawns(),
                        "the recovered initial generation cannot be retired twice");
            } finally {
                field.set(runtime, original);
            }

            try (var healthy = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                healthy.ref().send(41L);
                var output = healthy.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L,
                        assertInstanceOf(Long.class, output.value().value()));
            }
        }
    }

    @Test
    void failedCandidatePreReturnRegistrationKeepsOldRouteAndRetiresCandidatePin()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var originalGeneration = manager.stageActorGeneration(
                    "pre-return-upgrade-old.ores", OLD);
            var nextGeneration = manager.stageActorGeneration(
                    "pre-return-upgrade-new.ores", NEW);
            var candidateRuntime =
                    nextGeneration.linkActorGeneration("Counter").runtime();
            Field field = reflect(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) field.get(candidateRuntime);
            assertTrue(original.isEmpty());
            var faulty = new AfterInsertAndFailedRemoval();
            try (var router = LogicalSourceActorRouterV1.spawn(
                    originalGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                field.set(candidateRuntime, faulty);
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.upgradeTo(nextGeneration, WAIT));
                    faulty.requireFaultsFired();
                    assertEquals(1L, router.ref().routeEpoch(),
                            "candidate pre-return failure must not publish a new route");
                    awaitRetirement(nextGeneration, candidateRuntime, faulty,
                            router::reapRetired);
                    assertEquals(0, router.reapRetired(),
                            "stale candidate pin cannot be debited twice");
                } finally {
                    field.set(candidateRuntime, original);
                }

                router.ref().send(41L);
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L,
                        assertInstanceOf(Long.class, output.value().value()));
                assertTrue(router.upgradeTo(nextGeneration, WAIT),
                        "abandoned candidate must not prevent a later safe upgrade");
                assertEquals(2L, router.ref().routeEpoch());
            }
        }
    }

    @Test
    void preReturnCandidateKeepsOldTransferFreezeUntilPhysicalRemoval()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("freeze-pre-return-first.ores", OLD);
            var next = manager.stageActorGeneration("freeze-pre-return-next.ores", NEW);
            ActorRuntime candidateRuntime = next.linkActorGeneration("Counter").runtime();
            Field registryField = reflect(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) registryField.get(candidateRuntime);
            assertTrue(original.isEmpty());

            var failPut = new AtomicBoolean(true);
            var failInitialRemove = new AtomicBoolean(true);
            var enteredPhysicalRemoval = new java.util.concurrent.CountDownLatch(1);
            var permitPhysicalRemoval = new java.util.concurrent.CountDownLatch(1);
            var registry = new ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object old = super.put(key, value);
                    if (failPut.compareAndSet(true, false))
                        throw new OutOfMemoryError("injected candidate registration");
                    return old;
                }
                @Override public boolean remove(Object key, Object value) {
                    if (failInitialRemove.compareAndSet(true, false))
                        throw new OutOfMemoryError("injected rollback before removal");
                    enteredPhysicalRemoval.countDown();
                    try {
                        if (!permitPhysicalRemoval.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("physical finalization was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return super.remove(key, value);
                }
            };

            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                var priorPhysical = router.physicalRefForTesting();
                registryField.set(candidateRuntime, registry);
                var failure = new java.util.concurrent.FutureTask<Boolean>(() -> {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.upgradeTo(next, WAIT));
                    return true;
                });
                Thread upgrading = Thread.ofPlatform().start(failure);
                try {
                    assertTrue(enteredPhysicalRemoval.await(10, TimeUnit.SECONDS),
                            "candidate cleanup must reach physical actor finalization");
                    assertEquals(1, registry.size(),
                            "candidate is registered while physical removal is gated");
                    assertEquals(1, reflect(HotReloadManager.Generation.class,
                            "actorRoutePins").getInt(next),
                            "candidate image must remain pinned until physical removal");

                    Field txnField = reflect(
                            LogicalSourceActorRouterV1.class, "activeUpgradeTxn");
                    Object txn = txnField.get(router);
                    assertNotNull(txn, "supervisor must retain the failed staging owner");
                    Object transfer = reflect(txn.getClass(), "transfer").get(txn);
                    assertNotNull(transfer, "old ingress freeze must still be owned");
                    Object transferLock = reflect(
                            transfer.getClass(), "transferLock").get(transfer);
                    synchronized (transferLock) {
                        assertEquals("CLAIMED", String.valueOf(
                                reflect(transfer.getClass(), "transferState")
                                        .get(transfer)),
                                "failed pre-return spawn must not close old freeze early");
                        assertNotNull(reflect(transfer.getClass(),
                                "stagedCandidate").get(transfer),
                                "transfer must own exact preallocated physical candidate");
                    }
                    assertSame(priorPhysical, router.physicalRefForTesting(),
                            "old physical actor must remain authoritative until rollback");
                } finally {
                    permitPhysicalRemoval.countDown();
                    upgrading.join(10_000);
                    registryField.set(candidateRuntime, original);
                }
                assertFalse(upgrading.isAlive(), "upgrade must complete cleanup");
                assertTrue(failure.get(2, TimeUnit.SECONDS));
                assertTrue(registry.isEmpty());
                assertEquals(1L, router.ref().routeEpoch(),
                        "failed pre-return upgrade cannot publish candidate route");
                awaitRetirement(next, candidateRuntime, registry,
                        router::reapRetired);
            }
        }
    }

}
