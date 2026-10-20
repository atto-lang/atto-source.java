package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused supervisor-transaction regressions for logical source actor upgrade.
 * These tests intentionally inspect private ownership ledgers only to inject
 * deterministic faults; guest code has no access to these capabilities.
 */
@Timeout(60)
final class LogicalSourceActorSupervisorUpgradeTxnTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String V1 = """
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

    private static final String V2 = V1.replace(
            "self.count = self.count + mail.value;",
            "self.count = self.count + mail.value + 1;");

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field generationPinsField() throws Exception {
        Field pins = HotReloadManager.Generation.class
                .getDeclaredField("actorRoutePins");
        pins.setAccessible(true);
        return pins;
    }

    @Test
    void proxyTransactionOwnsFreezeAndRollsItBackAfterReadyTimeout()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("txn-freeze-v1.ores", V1);
            var candidate =
                    manager.stageActorGeneration("txn-freeze-v2.ores", V2);
            var candidateView = candidate.linkActorGeneration("Counter");
            ActorRuntime candidateRuntime = candidateView.runtime();

            Field dispatcherField =
                    ActorRuntime.class.getDeclaredField("sharedDispatcher");
            dispatcherField.setAccessible(true);
            ExecutorService original =
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
                                "candidate submitted more than one initial schedule");
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

            dispatcherField.set(candidateRuntime, blackHole);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                Field txnField = LogicalSourceActorRouterV1.class
                        .getDeclaredField("activeUpgradeTxn");
                txnField.setAccessible(true);

                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread upgrader = Thread.ofPlatform().start(() -> {
                    try {
                        router.upgradeToForProxy(
                                candidate, Duration.ofMillis(300), mailbox);
                        failure.set(new AssertionError(
                                "black-holed candidate unexpectedly published"));
                    } catch (Throwable expected) {
                        failure.set(expected);
                    }
                });

                long admittedBy = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                while (captured.get() == null
                        && System.nanoTime() < admittedBy) {
                    Thread.sleep(2);
                }
                assertNotNull(captured.get(),
                        "candidate physical actor must reach dispatcher admission");

                Object txn = null;
                long txnBy = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                while (txn == null && System.nanoTime() < txnBy) {
                    txn = txnField.get(router);
                    if (txn == null) Thread.sleep(2);
                }
                assertNotNull(txn, "staging must publish one supervisor transaction");

                Field phase = txn.getClass().getDeclaredField("phase");
                phase.setAccessible(true);
                assertEquals("STAGING", phase.get(txn).toString());
                assertThrows(IllegalStateException.class,
                        router::reconcileFailedUpgradeRollback,
                        "a still-active supervisor transaction is not reconciled");
                assertTrue(mailbox.snapshot().dispatchFrozen(),
                        "transaction must own the authoritative proxy freeze");
                assertEquals(1L, mailbox.snapshot().routeEpoch());
                assertTrue(router.proxyRouteEpochAlignedForTesting(mailbox));
                assertEquals(0, router.reapRetired(),
                        "staging candidate cannot be reaped by host observation");

                upgrader.join(10_000);
                assertFalse(upgrader.isAlive());
                assertInstanceOf(
                        java.util.concurrent.TimeoutException.class,
                        failure.get());
                assertNull(txnField.get(router),
                        "rollback must clear the active supervisor transaction");
                assertFalse(mailbox.snapshot().dispatchFrozen(),
                        "prepublication timeout must rollback transaction freeze");
                assertEquals(1L, mailbox.snapshot().routeEpoch());
                assertEquals(1L, router.ref().routeEpoch());
                Field rollbackOwner = LogicalSourceActorRouterV1.class
                        .getDeclaredField("pendingRollbackTransfer");
                rollbackOwner.setAccessible(true);
                Field quarantine = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retirementQuarantined");
                quarantine.setAccessible(true);
                assertNotNull(rollbackOwner.get(router),
                        "failed candidate physical rollback must retain its old ingress freeze");
                assertTrue(quarantine.getBoolean(router),
                        "prepublication rollback failure must block future physical admissions");
                assertThrows(IllegalStateException.class,
                        () -> router.upgradeToForProxy(candidate, WAIT, mailbox),
                        "unreconciled rollback must reject a second publication");

                // Cleanup requested stop before the black-holed runnable ever
                // entered the actor carrier. Executing that stale schedule now
                // must be inert and permit later physical-finalization reap.
                captured.get().run();
                long reapedBy = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                int reaped = 0;
                while (reaped == 0 && System.nanoTime() < reapedBy) {
                    reaped += router.reapRetired();
                    if (reaped == 0) Thread.sleep(2);
                }
                assertEquals(1, reaped);
                assertEquals(0, router.reapRetired());
                assertNotNull(rollbackOwner.get(router),
                        "retiring candidate code alone must not release old ingress freeze");
                assertTrue(router.reconcileFailedUpgradeRollback(),
                        "supervisor must retry exact retained transfer rollback");
                assertNull(rollbackOwner.get(router));
                assertFalse(quarantine.getBoolean(router));
                assertFalse(mailbox.snapshot().dispatchFrozen());
            } finally {
                dispatcherField.set(candidateRuntime, original);
                blackHole.shutdownNow();
            }
        }
    }

    @Test
    void successfulProxyTransactionPublishesOneCoherentAuthority()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("txn-success-v1.ores", V1);
            var second = manager.stageActorGeneration("txn-success-v2.ores", V2);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                Field txnField = LogicalSourceActorRouterV1.class
                        .getDeclaredField("activeUpgradeTxn");
                txnField.setAccessible(true);
                assertNull(txnField.get(router));

                assertTrue(router.upgradeToForProxy(second, WAIT, mailbox));
                assertNull(txnField.get(router),
                        "completed publication cannot retain transaction authority");
                assertFalse(mailbox.snapshot().dispatchFrozen());
                assertEquals(2L, mailbox.snapshot().routeEpoch());
                assertEquals(2L, router.ref().routeEpoch());

                var witness = router.supervisorPublication(mailbox);
                assertEquals(second.id(), witness.generationId());
                assertEquals(2L, witness.address().routeEpoch());
                assertEquals(2L, witness.mailbox().routeEpoch());
                assertTrue(router.proxyRouteEpochAlignedForTesting(mailbox));
            }
        }
    }

    @Test
    void preSpawnCandidateLeaseDebitFailureKeepsRetryableOwner()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("txn-debit-v1.ores", V1);
            var candidate =
                    manager.stageActorGeneration("txn-debit-v2.ores", V2);
            Field pins = generationPinsField();

            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                ActorRuntime oldRuntime =
                        first.linkActorGeneration("Counter").runtime();
                var external = oldRuntime.pinExternalWorkForHotUpgrade(
                        router.physicalRefForTesting());

                Field candidates = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredCandidates");
                candidates.setAccessible(true);
                Object original = candidates.get(router);

                var injected = new ArrayList<Object>() {
                    @Override public boolean add(Object value) {
                        try {
                            // pinActorRoute() has already charged exactly one
                            // candidate pin before owner registration.
                            assertEquals(1, pins.getInt(candidate));
                            pins.setInt(candidate, 0);
                        } catch (IllegalAccessException failure) {
                            throw new AssertionError(failure);
                        }
                        return super.add(value);
                    }
                };
                candidates.set(router, injected);
                try {
                    var failure = assertThrows(
                            IllegalStateException.class,
                            () -> router.upgradeTo(candidate, WAIT));
                    assertTrue(
                            failure.getMessage().contains("route-pin underflow"),
                            failure::getMessage);

                    assertEquals(1, injected.size(),
                            "failed debit must retain candidate retirement owner");
                    Object owner = injected.getFirst();
                    Field physical = owner.getClass().getDeclaredField("physical");
                    physical.setAccessible(true);
                    assertNull(physical.get(owner),
                            "quiescence refusal occurred before candidate spawn");
                    Field eligible = owner.getClass()
                            .getDeclaredField("retirementEligible");
                    eligible.setAccessible(true);
                    assertTrue(eligible.getBoolean(owner),
                            "failed debit must transfer retry authority to reaper");

                    // Repair the injected accounting corruption. Reaper must
                    // close the same lease before forgetting its owner.
                    pins.setInt(candidate, 1);
                    assertEquals(1, router.reapRetired());
                    assertEquals(0, pins.getInt(candidate));
                    assertTrue(injected.isEmpty());
                    assertEquals(0, router.reapRetired());
                } finally {
                    try {
                        external.close();
                    } finally {
                        candidates.set(router, original);
                    }
                }
            }
        }
    }

    @Test
    void candidatePhysicalIsOwnedBeforePostSpawnInvariantValidation()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "txn-post-spawn-owner-v1.ores", V1);
            var candidate = manager.stageActorGeneration(
                    "txn-post-spawn-owner-v2.ores", V2);
            var candidateView = candidate.linkActorGeneration("Counter");
            ActorRuntime candidateRuntime = candidateView.runtime();

            Field dispatcherField =
                    ActorRuntime.class.getDeclaredField("sharedDispatcher");
            dispatcherField.setAccessible(true);
            ExecutorService original =
                    (ExecutorService) dispatcherField.get(candidateRuntime);
            AtomicReference<Runnable> captured = new AtomicReference<>();
            AtomicReference<LogicalSourceActorRouterV1> routerRef =
                    new AtomicReference<>();

            ExecutorService corrupting = new AbstractExecutorService() {
                private volatile boolean shutdown;

                @Override public void execute(Runnable command) {
                    if (shutdown) {
                        throw new java.util.concurrent.RejectedExecutionException();
                    }
                    if (!captured.compareAndSet(null, command)) {
                        throw new AssertionError(
                                "candidate unexpectedly submitted multiple schedules");
                    }
                    LogicalSourceActorRouterV1 router = routerRef.get();
                    if (router == null) {
                        throw new AssertionError("router not published to injector");
                    }
                    try {
                        Field txnField = LogicalSourceActorRouterV1.class
                                .getDeclaredField("activeUpgradeTxn");
                        txnField.setAccessible(true);
                        Object txn = txnField.get(router);
                        assertNotNull(txn, "candidate admission must be transaction-owned");
                        Field phase = txn.getClass().getDeclaredField("phase");
                        phase.setAccessible(true);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object abandoned = Enum.valueOf(
                                (Class<? extends Enum>) phase.getType(),
                                "ABANDONED");
                        // Inject corruption after the transaction has admitted
                        // candidate scheduling but before spawn returns to the
                        // router's post-spawn validation.
                        phase.set(txn, abandoned);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
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

            dispatcherField.set(candidateRuntime, corrupting);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                routerRef.set(router);

                var failure = assertThrows(
                        IllegalStateException.class,
                        () -> router.upgradeTo(candidate, WAIT));
                assertTrue(
                        failure.getMessage().contains(
                                "candidate physical publication outside staging transaction"),
                        failure::getMessage);
                assertEquals(1L, router.ref().routeEpoch(),
                        "post-spawn invariant failure cannot publish candidate route");
                assertNotNull(captured.get(),
                        "candidate physical scheduling must have occurred");

                Field txnField = LogicalSourceActorRouterV1.class
                        .getDeclaredField("activeUpgradeTxn");
                txnField.setAccessible(true);
                assertNull(txnField.get(router),
                        "failed transaction must release publication authority");

                Field candidates = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredCandidates");
                candidates.setAccessible(true);
                @SuppressWarnings("unchecked")
                var owners = (ArrayList<Object>) candidates.get(router);
                assertEquals(1, owners.size(),
                        "physically admitted candidate must remain retry-owned");
                Object owner = owners.getFirst();
                Field physical = owner.getClass().getDeclaredField("physical");
                physical.setAccessible(true);
                assertNotNull(physical.get(owner),
                        "candidate ref must be stored before invariant validation");
                Field eligible = owner.getClass()
                        .getDeclaredField("retirementEligible");
                eligible.setAccessible(true);
                assertTrue(eligible.getBoolean(owner),
                        "failed post-spawn transaction transfers retirement authority");

                // The queued carrier never entered guest execution. Once run
                // after stop, it is stale/inert and physical finalization can
                // be observed by the retry reaper.
                captured.get().run();
                long deadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                int reaped = 0;
                while (reaped == 0 && System.nanoTime() < deadline) {
                    reaped += router.reapRetired();
                    if (reaped == 0) Thread.sleep(2);
                }
                assertEquals(1, reaped);
                assertTrue(owners.isEmpty());
                assertEquals(0, router.reapRetired());
            } finally {
                dispatcherField.set(candidateRuntime, original);
                corrupting.shutdownNow();
            }
        }
    }


    @Test
    void setupFreezeRollbackFailurePreservesExactTicketForSupervisorRepair()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration(
                    "setup-freeze-failure-v1.ores", V1);
            var candidate = manager.stageActorGeneration(
                    "setup-freeze-failure-v2.ores", V2);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);

                Field messageCount = LogicalActorProxyMailboxV1.class
                        .getDeclaredField("retainedMessages");
                messageCount.setAccessible(true);
                Field outstandingField = LogicalActorProxyMailboxV1.class
                        .getDeclaredField("outstanding");
                outstandingField.setAccessible(true);
                Object original = outstandingField.get(mailbox);

                // The freeze's preflight sees a valid empty map. Once the
                // freshly minted ticket calls drained(), corrupt the
                // accounting and throw. Both subsequent rollback attempts
                // must reject thaw without losing that exact ticket.
                var corrupting = new java.util.HashMap<Long,
                        LogicalActorProxyMailboxV1.Envelope>() {
                    private final java.util.concurrent.atomic.AtomicBoolean
                            fired = new java.util.concurrent.atomic.AtomicBoolean();
                    @Override public java.util.Set<Long> keySet() {
                        if (fired.compareAndSet(false, true)) {
                            try {
                                messageCount.setInt(mailbox, -1);
                            } catch (IllegalAccessException error) {
                                throw new AssertionError(error);
                            }
                            throw new IllegalStateException(
                                    "injected post-freeze setup failure");
                        }
                        return super.keySet();
                    }
                };
                outstandingField.set(mailbox, corrupting);
                try {
                    var failure = assertThrows(
                            IllegalStateException.class,
                            () -> router.upgradeToForProxy(
                                    candidate, WAIT, mailbox));
                    assertTrue(failure.getMessage().contains(
                            "injected post-freeze setup failure"));
                    assertEquals(1L, router.ref().routeEpoch(),
                            "failed setup cannot publish a candidate");
                    Field pending = LogicalSourceActorRouterV1.class
                            .getDeclaredField("pendingProxyRollbackTicket");
                    pending.setAccessible(true);
                    Object ticket = pending.get(router);
                    assertNotNull(ticket,
                            "failed setup rollback must preserve the exact ticket");
                    Field frozen = LogicalActorProxyMailboxV1.class
                            .getDeclaredField("dispatchFrozen");
                    frozen.setAccessible(true);
                    assertTrue(frozen.getBoolean(mailbox),
                            "structural rollback failure cannot thaw dispatch");
                    Field active = LogicalActorProxyMailboxV1.class
                            .getDeclaredField("activeFreeze");
                    active.setAccessible(true);
                    assertSame(ticket, active.get(mailbox),
                            "recovery must own the mailbox's actual freeze ticket");
                    Field quarantine = LogicalSourceActorRouterV1.class
                            .getDeclaredField("retirementQuarantined");
                    quarantine.setAccessible(true);
                    assertTrue(quarantine.getBoolean(router));
                    assertThrows(IllegalStateException.class, router::close,
                            "unreconciled proxy freeze must prohibit actor retirement");
                    assertThrows(IllegalStateException.class,
                            router::reconcileFailedProxyRollback,
                            "unrepaired accounting cannot authorize thaw");
                    assertSame(ticket, pending.get(router),
                            "failed reconciliation must preserve retry owner");
                } finally {
                    // Repair test-only corruption before any host shutdown.
                    messageCount.setInt(mailbox, 0);
                    outstandingField.set(mailbox, original);
                }
                assertTrue(router.reconcileFailedProxyRollback(),
                        "explicit trusted reconciliation must thaw exact ticket");
                assertFalse(mailbox.snapshot().dispatchFrozen());
                assertEquals(1L, mailbox.snapshot().routeEpoch(),
                        "rollback never increments the physical route epoch");
                assertEquals(1L, router.ref().routeEpoch(),
                        "a failed setup must retain the original logical route");
                assertEquals(0, router.reapRetired(),
                        "candidate lease must be debited at most once");
            }
        }
    }

}
