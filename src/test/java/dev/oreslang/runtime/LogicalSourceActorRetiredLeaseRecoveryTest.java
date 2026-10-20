package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An irreversible route commit must retain old code retirement ownership even
 * when the subsequent generation-lease debit fails. The supervisor can retry
 * only if the retired physical route is still tracked.
 */
@Timeout(60)
final class LogicalSourceActorRetiredLeaseRecoveryTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String SOURCE = """
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
    void postPublicationOldGenerationReleaseFaultRetainsRetryableRetirement()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "retired-pin-old.ores", SOURCE);
            var newGeneration = manager.stageActorGeneration(
                    "retired-pin-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                Field pins = HotReloadManager.Generation.class
                        .getDeclaredField("actorRoutePins");
                pins.setAccessible(true);
                assertEquals(1, pins.getInt(oldGeneration),
                        "the physical old route must own one code-generation pin");

                // Force a trusted lease-accounting failure AFTER route commit.
                // The old physical actor must remain in the retirement
                // registry instead of disappearing on a failed pin debit.
                pins.setInt(oldGeneration, 0);
                try {
                    IllegalStateException failure =
                            assertThrows(IllegalStateException.class,
                                    () -> router.upgradeTo(newGeneration, WAIT));
                    assertTrue(failure.getMessage().contains("route-pin underflow"),
                            "inject the known post-publication release fault");
                    // After an irreversible publication, the old generation
                    // has NOT been retired successfully. The new route may
                    // be observed for diagnosis, but neither further
                    // upgrades nor physical admissions may proceed.
                    Field quarantine = LogicalSourceActorRouterV1.class
                            .getDeclaredField("retirementQuarantined");
                    quarantine.setAccessible(true);
                    assertTrue(quarantine.getBoolean(router),
                            "postcommit lease failure must quarantine route admission");
                    assertFalse(router.reconcileFailedUpgradeRollback(),
                            "no unpublished rollback owner cannot falsely certify "
                                    + "unreaped postpublication retirement");
                    assertThrows(IllegalStateException.class,
                            () -> router.upgradeTo(newGeneration, WAIT),
                            "quarantined supervisor must refuse a second upgrade");
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().send(4L),
                            "new physical delivery must not bypass pending retirement");
                } finally {
                    // Restore the damaged lease accounting before invoking
                    // any route observation that may opportunistically reap.
                    pins.setInt(oldGeneration, 1);
                }

                assertEquals(1, router.reapRetired(),
                        "published old route must remain discoverable and retryable");
                Field quarantineAfterReap = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retirementQuarantined");
                quarantineAfterReap.setAccessible(true);
                assertFalse(quarantineAfterReap.getBoolean(router),
                        "verified retirement and lease debit must clear quarantine");
                assertEquals(0, pins.getInt(oldGeneration),
                        "the recovered retirement must debit the old pin once");
                assertEquals(2L, router.ref().routeEpoch(),
                        "postcommit release failure may not roll back routing");
                assertEquals(0, router.reapRetired(),
                        "a successful lease release must not replay");
            }
        }
    }
    @Test
    void postPublicationFailedStopCanBeRetriedWithoutRevertingRoute()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "retire-stop-retry-old.ores", SOURCE);
            var second = manager.stageActorGeneration(
                    "retire-stop-retry-next.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            ActorRuntime oldRuntime = first.linkActorGeneration("Counter").runtime();
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                var actorRegistry = ActorRuntime.class.getDeclaredField("actors");
                actorRegistry.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<Object, Object> original =
                        (java.util.Map<Object, Object>) actorRegistry.get(oldRuntime);
                var failed = new java.util.concurrent.atomic.AtomicBoolean(false);
                var faulted = new java.util.concurrent.ConcurrentHashMap<Object, Object>() {
                    @Override public Object get(Object key) {
                        // Only interrupt the physical stop AFTER route commit.
                        // Check runtime stop's actual stack frame, so the earlier
                        // checkpoint/freeze lookups are not mistaken for stop.
                        boolean stopping = false;
                        for (StackTraceElement frame
                                : Thread.currentThread().getStackTrace()) {
                            if (ActorRuntime.class.getName().equals(
                                    frame.getClassName())
                                    && "stop".equals(frame.getMethodName())) {
                                stopping = true;
                                break;
                            }
                        }
                        if (stopping && failed.compareAndSet(false, true))
                            throw new OutOfMemoryError(
                                    "injected old actor stop before registry lookup");
                        return super.get(key);
                    }
                };
                faulted.putAll(original);
                actorRegistry.set(oldRuntime, faulted);
                try {
                    var error = assertThrows(
                            OutOfMemoryError.class,
                            () -> router.upgradeTo(second, WAIT));
                    assertEquals("injected old actor stop before registry lookup",
                            error.getMessage());
                    assertTrue(failed.get(),
                            "fault must occur during old physical stop");
                    assertEquals(2L, router.ref().routeEpoch(),
                            "postcommit physical stop failure cannot unpublish new route");
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().send(3L),
                            "pending old actor retirement must quarantine new ingress");
                    assertEquals(0, router.reapRetired(),
                            "mere reaping cannot terminate a still-live old actor");
                    assertEquals(1, router.retryRetiredPhysicalStops(),
                            "explicit host retry must stop and debit old physical owner");
                    assertEquals(0, router.retryRetiredPhysicalStops(),
                            "completed old retirement must be one-shot");
                    assertEquals(2L, router.ref().routeEpoch(),
                            "retirement repair cannot revert published route epoch");
                    Field pins = HotReloadManager.Generation.class
                            .getDeclaredField("actorRoutePins");
                    pins.setAccessible(true);
                    assertEquals(0, pins.getInt(first),
                            "old generation pin must close exactly once");
                    assertDoesNotThrow(() -> router.ref().send(3L),
                            "verified retirement releases host ingress quarantine");
                } finally {
                    // Restore the latest entries, never resurrect a cell
                    // that the fault-injected registry already unregistered.
                    original.clear();
                    original.putAll(faulted);
                    actorRegistry.set(oldRuntime, original);
                }
            }
        }
    }

    @Test
    void pendingRetiredPhysicalActorFencesSendsOutputsTasksAndUpgrade()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "retired-quarantine-pending.ores", SOURCE);
            var second = manager.stageActorGeneration(
                    "retired-quarantine-candidate.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                Field active = LogicalSourceActorRouterV1.class
                        .getDeclaredField("active");
                Field retired = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredRoutes");
                Field quarantine = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retirementQuarantined");
                active.setAccessible(true);
                retired.setAccessible(true);
                quarantine.setAccessible(true);

                // Test-only synthetic pending retirement: leave this live
                // physical actor and its generation pin untouched. This
                // injects the post-publication *fence state*, not an upgrade
                // transaction; the real postcommit path is tested above.
                Object stillExecuting = active.get(router);
                @SuppressWarnings("unchecked")
                var owners = (List<Object>) retired.get(router);
                owners.add(stillExecuting);
                quarantine.setBoolean(router, true);
                try {
                    assertEquals(0, router.reapRetired(),
                            "a live actor is not physical finalization");
                    assertTrue(quarantine.getBoolean(router));
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().send(10L));
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().nextOutput(WAIT));
                    try (var scheduler = new OresScheduler(1);
                         var scope = OresTaskScope.compilerOwned()) {
                        assertThrows(IllegalStateException.class,
                                () -> router.startCompilerTaskForUpgrade(
                                        scope, scheduler,
                                        resume -> OresScheduler.done(1), 1L),
                                "a quarantined route cannot launch a new code-pinned Task");
                    }
                    assertThrows(IllegalStateException.class,
                            () -> router.upgradeTo(second, WAIT));
                    assertEquals(1, owners.size(),
                            "failed admission must retain retirement ownership");
                } finally {
                    // Restore synthetic test ownership before try-with-resources
                    // invokes real actor stop / generation-pin retirement.
                    owners.remove(stillExecuting);
                    quarantine.setBoolean(router, false);
                }
                assertDoesNotThrow(() -> router.ref().send(10L),
                        "restored live source actor may accept work");
            }
        }
    }

    @Test
    void retirementReservationOomeCannotPublishRouteOrMailbox()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "retire-reserve-old.ores", SOURCE);
            var newGeneration = manager.stageActorGeneration(
                    "retire-reserve-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                var retirement = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredRoutes");
                retirement.setAccessible(true);
                Object original = retirement.get(router);
                retirement.set(router, new java.util.ArrayList<Object>() {
                    @Override public boolean add(Object value) {
                        throw new OutOfMemoryError(
                                "injected retirement record allocation before commit");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.upgradeTo(newGeneration, WAIT));
                } finally {
                    retirement.set(router, original);
                }
                assertEquals(1L, router.ref().routeEpoch(),
                        "failed retirement ownership reservation cannot publish candidate");
                assertEquals(0, router.reapRetired());
                assertTrue(router.upgradeTo(newGeneration, WAIT),
                        "failed precommit retirement record allocation must allow safe retry");
                assertEquals(2L, router.ref().routeEpoch());
            }
        }
    }

    @Test
    void unpublishedCandidateOwnsGenerationBeforePhysicalSpawnAndRetainsItAcrossTimeout()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "candidate-owner-old.ores", SOURCE);
            var candidateGeneration = manager.stageActorGeneration(
                    "candidate-owner-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));

            var candidateView =
                    candidateGeneration.linkActorGeneration("Counter");
            ActorRuntime candidateRuntime = candidateView.runtime();

            Field dispatcherField =
                    ActorRuntime.class.getDeclaredField("sharedDispatcher");
            dispatcherField.setAccessible(true);
            ExecutorService originalDispatcher =
                    (ExecutorService) dispatcherField.get(candidateRuntime);
            AtomicReference<Runnable> captured = new AtomicReference<>();
            ExecutorService blackHole = new AbstractExecutorService() {
                private volatile boolean shutdown;

                @Override public void execute(Runnable command) {
                    if (shutdown) {
                        throw new java.util.concurrent.RejectedExecutionException();
                    }
                    if (!captured.compareAndSet(null, command)) {
                        throw new AssertionError(
                                "candidate unexpectedly submitted multiple initial dispatches");
                    }
                }

                @Override public void shutdown() { shutdown = true; }
                @Override public List<Runnable> shutdownNow() {
                    shutdown = true;
                    return List.of();
                }
                @Override public boolean isShutdown() { return shutdown; }
                @Override public boolean isTerminated() { return shutdown; }
                @Override public boolean awaitTermination(
                        long timeout, TimeUnit unit) {
                    return shutdown;
                }
            };

            Field pins = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            pins.setAccessible(true);
            Field retiredCandidates = LogicalSourceActorRouterV1.class
                    .getDeclaredField("retiredCandidates");
            retiredCandidates.setAccessible(true);

            dispatcherField.set(candidateRuntime, blackHole);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                try {
                    var upgradeFailure = new AtomicReference<Throwable>();
                    Thread upgrader = Thread.ofPlatform().start(() -> {
                        try {
                            router.upgradeTo(
                                    candidateGeneration,
                                    Duration.ofMillis(250));
                            upgradeFailure.set(new AssertionError(
                                    "candidate that never reaches ready unexpectedly published"));
                        } catch (Throwable failure) {
                            upgradeFailure.set(failure);
                        }
                    });

                    long admittedBy = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
                    while (captured.get() == null
                            && System.nanoTime() < admittedBy) {
                        Thread.sleep(2);
                    }
                    assertNotNull(captured.get(),
                            "candidate physical spawn must have reached dispatcher admission");

                    @SuppressWarnings("unchecked")
                    var owners = (java.util.ArrayList<Object>)
                            retiredCandidates.get(router);
                    long ownerBy = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
                    while (owners.isEmpty() && System.nanoTime() < ownerBy) {
                        Thread.sleep(2);
                    }
                    assertEquals(1, owners.size(),
                            "candidate owner must be reserved before physical readiness");

                    Object owner = owners.getFirst();
                    Field eligible = owner.getClass()
                            .getDeclaredField("retirementEligible");
                    eligible.setAccessible(true);
                    assertFalse(eligible.getBoolean(owner),
                            "staging candidate cannot be retirement-eligible");

                    // routeEpoch() opportunistically calls reapRetiredLocked().
                    // This observation must not consume a candidate whose
                    // originating upgrade can still publish it.
                    assertEquals(1L, router.ref().routeEpoch());
                    assertEquals(1, owners.size(),
                            "staging observation must not reap candidate owner");
                    assertEquals(1, pins.getInt(candidateGeneration),
                            "staging observation must not release candidate pin");
                    assertEquals(0, router.reapRetired(),
                            "explicit reaper must skip staging candidate owner");

                    upgrader.join(10_000);
                    assertFalse(upgrader.isAlive(),
                            "timed-out candidate upgrade did not return");
                    assertInstanceOf(
                            java.util.concurrent.TimeoutException.class,
                            upgradeFailure.get(),
                            "candidate readiness timeout must remain the primary failure");
                    assertEquals(1L, router.ref().routeEpoch(),
                            "timed-out candidate must leave old route authoritative");
                    assertEquals(1, owners.size(),
                            "failed physical candidate must retain pre-reserved owner");
                    assertTrue(eligible.getBoolean(owner),
                            "abandoned candidate must become retirement-eligible");
                    assertEquals(1, pins.getInt(candidateGeneration),
                            "unpublished physical candidate must retain generation pin");
                    Field rollback = LogicalSourceActorRouterV1.class
                            .getDeclaredField("pendingRollbackTransfer");
                    Field quarantine = LogicalSourceActorRouterV1.class
                            .getDeclaredField("retirementQuarantined");
                    rollback.setAccessible(true);
                    quarantine.setAccessible(true);
                    assertNotNull(rollback.get(router),
                            "failed physical candidate stop must retain source freeze capability");
                    assertTrue(quarantine.getBoolean(router));
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().send(5L),
                            "old route must not reopen while staged candidate remains live");
                    assertThrows(IllegalStateException.class,
                            router::close,
                            "old actor close must not release code under a live rolled-back candidate");

                    captured.get().run();

                    long deadline = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
                    int reaped = 0;
                    while (reaped == 0 && System.nanoTime() < deadline) {
                        reaped += router.reapRetired();
                        if (reaped == 0) Thread.sleep(2);
                    }
                    assertEquals(1, reaped,
                            "pre-reserved unpublished candidate owner must be retry-reapable");
                    assertEquals(0, pins.getInt(candidateGeneration),
                            "reap must release candidate generation pin exactly once");
                    assertTrue(owners.isEmpty(),
                            "successful reap must forget candidate owner");
                    assertEquals(0, router.reapRetired(),
                            "candidate retirement cannot replay");
                    assertNotNull(rollback.get(router),
                            "candidate pin retirement is not proof old freeze was closed");
                    assertTrue(router.reconcileFailedUpgradeRollback());
                    assertNull(rollback.get(router));
                    assertFalse(quarantine.getBoolean(router));
                    assertEquals(1L, router.ref().routeEpoch());
                    assertDoesNotThrow(() -> router.ref().send(7L),
                            "verified candidate retirement reopens original route");
                } finally {
                    dispatcherField.set(candidateRuntime, originalDispatcher);
                    blackHole.shutdownNow();
                }
            }
        }
    }

    @Test
    void candidateOwnerReservationOomeOccursBeforePhysicalCandidateAdmission()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "candidate-owner-oome-old.ores", SOURCE);
            var candidateGeneration = manager.stageActorGeneration(
                    "candidate-owner-oome-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            Field pins = HotReloadManager.Generation.class
                    .getDeclaredField("actorRoutePins");
            pins.setAccessible(true);

            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                Field candidates = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredCandidates");
                candidates.setAccessible(true);
                Object original = candidates.get(router);
                candidates.set(router, new java.util.ArrayList<Object>() {
                    @Override public boolean add(Object value) {
                        throw new OutOfMemoryError(
                                "injected candidate owner reservation failure");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.upgradeTo(candidateGeneration, WAIT));
                    assertEquals(1L, router.ref().routeEpoch(),
                            "owner reservation failure is pre-publication");
                    assertEquals(0, pins.getInt(candidateGeneration),
                            "pre-spawn owner reservation failure must release candidate pin");
                } finally {
                    candidates.set(router, original);
                }
                assertTrue(router.upgradeTo(candidateGeneration, WAIT),
                        "failed pre-admission owner reservation must remain safely retryable");
                assertEquals(2L, router.ref().routeEpoch());
            }
        }
    }


    @Test
    void activeCloseRetirementReservationOomeDoesNotSealOrStopActor()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "active-close-preflight-oome.ores", SOURCE);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                Field registry = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredRoutes");
                registry.setAccessible(true);
                Object original = registry.get(router);
                Field pins = HotReloadManager.Generation.class
                        .getDeclaredField("actorRoutePins");
                pins.setAccessible(true);
                registry.set(router, new java.util.ArrayList<Object>() {
                    @Override public boolean add(Object value) {
                        throw new OutOfMemoryError(
                                "injected close retirement pre-reservation OOME");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class, router::close);
                    assertEquals(1, pins.getInt(generation),
                            "reservation failure must not debit active code lease");
                    assertEquals(1L, router.ref().routeEpoch(),
                            "no actor stop or logical seal before retirement owner exists");
                    router.ref().send(41L);
                    var output = router.ref().nextOutput(WAIT);
                    assertFalse(output.done());
                    assertEquals(42L,
                            assertInstanceOf(Long.class, output.value().value()));
                } finally {
                    registry.set(router, original);
                }
                assertDoesNotThrow(router::close,
                        "an unsealed actor can retry after retirement allocation recovers");
                assertEquals(0, pins.getInt(generation));
                assertThrows(IllegalStateException.class, router.ref()::routeEpoch);
            }
        }
    }

    @Test
    void activeCloseLeaseDebitFailureRetainsRetryOwnerForSupervisorClose()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "active-close-debit-retry.ores", SOURCE);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                Field pins = HotReloadManager.Generation.class
                        .getDeclaredField("actorRoutePins");
                pins.setAccessible(true);
                Field retired = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredRoutes");
                retired.setAccessible(true);
                assertEquals(1, pins.getInt(generation));
                // Corrupt a trusted supervisor accounting counter. The actor
                // may finalize, but a failed pin debit must preserve its
                // reserved retry owner instead of silently discarding it.
                pins.setInt(generation, 0);
                try {
                    assertThrows(IllegalStateException.class, router::close);
                    assertFalse(((java.util.List<?>) retired.get(router)).isEmpty(),
                            "sealed actor still needs a retryable lease owner");
                    assertThrows(IllegalStateException.class, router.ref()::routeEpoch);
                } finally {
                    pins.setInt(generation, 1);
                }
                assertDoesNotThrow(router::close,
                        "supervisor retry must debit recovered pin exactly once");
                assertEquals(0, pins.getInt(generation));
                assertEquals(0, ((java.util.List<?>) retired.get(router)).size());
                assertDoesNotThrow(router::close);
            }
        }
    }


    @Test
    void candidateBookkeepingRemovalFaultCannotUndoPublishedRoute()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "candidate-commit-removal-v1.ores", SOURCE);
            var second = manager.stageActorGeneration(
                    "candidate-commit-removal-v2.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                var field = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredCandidates");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var original = (java.util.ArrayList<Object>) field.get(router);
                var removalAttempts = new java.util.concurrent.atomic.AtomicInteger();
                var throwingRemoval = new java.util.ArrayList<Object>() {
                    @Override public boolean remove(Object item) {
                        removalAttempts.incrementAndGet();
                        throw new OutOfMemoryError(
                                "injected candidate bookkeeping removal after commit");
                    }
                };
                field.set(router, throwingRemoval);
                try {
                    assertTrue(router.upgradeTo(second, WAIT),
                            "staging bookkeeping removal is not part of publication");
                    assertEquals(0, removalAttempts.get(),
                            "committing a new route must never call throwing remove(Object)");
                    assertEquals(1, throwingRemoval.size(),
                            "the published staging entry remains as a nonowning receipt");
                    assertEquals(2L, router.ref().routeEpoch());
                    // Reap discards the receipt using remove(index), without
                    // making another debit against the active generation.
                    assertEquals(0, router.reapRetired());
                    assertTrue(throwingRemoval.isEmpty());
                    var pins = HotReloadManager.Generation.class
                            .getDeclaredField("actorRoutePins");
                    pins.setAccessible(true);
                    assertEquals(1, pins.getInt(second),
                            "staging receipt cleanup cannot release active code pin");
                    assertEquals(0, pins.getInt(first));
                } finally {
                    original.addAll(throwingRemoval);
                    field.set(router, original);
                }
            }
        }
    }
}
