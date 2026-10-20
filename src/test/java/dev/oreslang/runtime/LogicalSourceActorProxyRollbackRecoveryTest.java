package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An unsuccessful explicit proxy rollback must leave a durable freeze owner
 * until trusted host reconciliation, independently of physical route reaping.
 */
@Timeout(45)
final class LogicalSourceActorProxyRollbackRecoveryTest {
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

    private static Field reflect(Class<?> clazz, String name) throws Exception {
        Field result = clazz.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    @Test
    void retainedProxyTicketBlocksRouteUntilExplicitSuccessfulRetry()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var source = manager.stageActorGeneration(
                    "proxy-rollback-reservation.ores", SOURCE);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    source, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var ticket = mailbox.freezeDispatchForUpgrade();
                assertTrue(ticket.drained());
                Field retained = reflect(LogicalActorProxyMailboxV1.class,
                        "retainedMessages");
                retained.setInt(mailbox, -1);
                assertThrows(IllegalStateException.class, ticket::rollback,
                        "structurally invalid mailbox must retain its freeze");

                // Emulate the exact state handed off by the transaction's
                // rollback catch: the failed ticket remains the sole owner.
                Field pending = reflect(LogicalSourceActorRouterV1.class,
                        "pendingProxyRollbackTicket");
                Field quarantine = reflect(LogicalSourceActorRouterV1.class,
                        "retirementQuarantined");
                synchronized (reflect(LogicalSourceActorRouterV1.class,
                        "routeLock").get(router)) {
                    pending.set(router, ticket);
                    quarantine.setBoolean(router, true);
                }
                try {
                    assertThrows(IllegalStateException.class,
                            router::reconcileFailedProxyRollback,
                            "invalid mailbox cannot be silently thawed");
                    assertSame(ticket, pending.get(router),
                            "failed retry must retain the exact ticket");
                    assertTrue(quarantine.getBoolean(router));
                    assertEquals(1L, router.ref().routeEpoch(),
                            "failed rollback retry must not publish another epoch");
                    assertTrue(mailbox.snapshot().dispatchFrozen(),
                            "failed rollback retry must not reopen dispatch");
                    assertThrows(IllegalStateException.class,
                            router::reconcileFailedProxyRollback,
                            "repeated failed reconciliation must remain fail-closed");
                    assertSame(ticket, pending.get(router),
                            "repeated failure must never replace the freeze owner");
                    assertThrows(IllegalStateException.class, router::close,
                            "a pending mailbox freeze cannot be abandoned by close");

                    // An unrelated host reaper must never remove the proxy
                    // freeze owner just because no old routes are retired.
                    retained.setInt(mailbox, 0);
                    assertEquals(0, router.reapRetired());
                    assertSame(ticket, pending.get(router));
                    assertTrue(quarantine.getBoolean(router));
                    assertTrue(mailbox.snapshot().dispatchFrozen());

                    assertTrue(router.reconcileFailedProxyRollback(),
                            "repaired mailbox must permit explicit old-route thaw");
                    assertNull(pending.get(router));
                    assertFalse(quarantine.getBoolean(router));
                    assertFalse(mailbox.snapshot().dispatchFrozen());
                    assertEquals(1L, router.ref().routeEpoch());
                    assertEquals(0, router.reapRetired(),
                            "consumed freeze must not be reconciled twice");
                    assertTrue(router.reconcileFailedProxyRollback());
                } finally {
                    retained.setInt(mailbox, 0);
                    if (pending.get(router) == ticket) {
                        // Keep failed test cleanup deterministic without
                        // certifying this owner was safely reconciled.
                        ticket.rollback();
                        pending.set(router, null);
                        quarantine.setBoolean(router, false);
                    }
                }
            }
        }
    }

    @Test
    void liveCandidatePreventsProxyFreezeReconciliation() throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = manager.stageActorGeneration(
                    "proxy-rollback-candidate-v1.ores", SOURCE);
            var newer = manager.stageActorGeneration(
                    "proxy-rollback-candidate-v2.ores",
                    SOURCE.replace("self.count + mail.value;",
                            "self.count + mail.value + 1;"));
            var candidate = newer.linkActorGeneration("Counter");
            var generationLease = newer.pinActorRoute();
            var physical = candidate.runtime().spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, candidate.policy(),
                    candidate.executor(), "Counter");
            physical.getReady().get(5, TimeUnit.SECONDS);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    original, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var ticket = mailbox.freezeDispatchForUpgrade();
                Class<?> type = Class.forName(
                        "dev.oreslang.runtime.LogicalSourceActorRouterV1$CandidateRetirement");
                var constructor = type.getDeclaredConstructor(
                        HotReloadManager.Generation.class,
                        HotReloadManager.Generation.ActorRouteLease.class,
                        HotReloadManager.ActorGenerationView.class,
                        long.class, long.class,
                        LogicalSourceActorRouterV1.ImageReceipts.class);
                constructor.setAccessible(true);
                Object candidateOwner = constructor.newInstance(
                        newer, generationLease, candidate, 1L, 1L,
                        LogicalSourceActorRouterV1.ImageReceipts.from(
                                candidate, "Counter"));
                reflect(type, "physical").set(candidateOwner, physical);
                reflect(type, "retirementEligible").setBoolean(candidateOwner, true);
                Field pending = reflect(LogicalSourceActorRouterV1.class,
                        "pendingProxyRollbackTicket");
                Field quarantine = reflect(LogicalSourceActorRouterV1.class,
                        "retirementQuarantined");
                Field candidateLedger = reflect(LogicalSourceActorRouterV1.class,
                        "retiredCandidates");
                @SuppressWarnings("unchecked")
                var list = (java.util.ArrayList<Object>) candidateLedger.get(router);
                synchronized (reflect(LogicalSourceActorRouterV1.class,
                        "routeLock").get(router)) {
                    list.add(candidateOwner);
                    pending.set(router, ticket);
                    quarantine.setBoolean(router, true);
                }

                assertFalse(router.reconcileFailedProxyRollback(),
                        "unfinalized candidate must keep old mailbox ingress frozen");
                assertTrue(mailbox.snapshot().dispatchFrozen());
                assertSame(ticket, pending.get(router));
                physical.stop();
                assertTrue(physical.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(1, router.reapRetired());
                assertFalse(list.contains(candidateOwner));
                assertTrue(router.reconcileFailedProxyRollback());
                assertFalse(mailbox.snapshot().dispatchFrozen());
                assertFalse(quarantine.getBoolean(router));
            }
        }
    }

    @Test
    void supervisorRetainsRealFailedProxyRollbackTicketAfterCandidateTimeout()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var old = manager.stageActorGeneration(
                    "proxy-real-rollback-first.ores", SOURCE);
            var next = manager.stageActorGeneration(
                    "proxy-real-rollback-next.ores",
                    SOURCE.replace("self.count + mail.value;",
                            "self.count + mail.value + 1;"));
            ActorRuntime candidateRuntime =
                    next.linkActorGeneration("Counter").runtime();
            Field dispatcher = reflect(ActorRuntime.class, "sharedDispatcher");
            java.util.concurrent.ExecutorService original =
                    (java.util.concurrent.ExecutorService) dispatcher.get(candidateRuntime);
            var scheduled = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            var silent = new java.util.concurrent.AbstractExecutorService() {
                private volatile boolean shutdown;
                @Override public void execute(Runnable task) {
                    if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
                    if (!scheduled.compareAndSet(null, task))
                        throw new AssertionError("unexpected second candidate schedule");
                }
                @Override public void shutdown() { shutdown = true; }
                @Override public java.util.List<Runnable> shutdownNow() {
                    shutdown = true;
                    return java.util.List.of();
                }
                @Override public boolean isShutdown() { return shutdown; }
                @Override public boolean isTerminated() { return shutdown; }
                @Override public boolean awaitTermination(
                        long time, TimeUnit unit) { return shutdown; }
            };
            dispatcher.set(candidateRuntime, silent);
            try (var router = LogicalSourceActorRouterV1.spawn(
                    old, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread upgrade = Thread.ofPlatform().start(() -> {
                    try {
                        router.upgradeToForProxy(
                                next, Duration.ofMillis(250), mailbox);
                        result.set(new AssertionError("candidate unexpectedly upgraded"));
                    } catch (Throwable failed) {
                        result.set(failed);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scheduled.get() == null && System.nanoTime() < deadline)
                    Thread.sleep(2);
                assertNotNull(scheduled.get(),
                        "candidate must have reached fallible physical admission");
                Field retained = reflect(LogicalActorProxyMailboxV1.class,
                        "retainedMessages");
                Field pending = reflect(LogicalSourceActorRouterV1.class,
                        "pendingProxyRollbackTicket");
                Field pendingTransfer = reflect(LogicalSourceActorRouterV1.class,
                        "pendingRollbackTransfer");
                retained.setInt(mailbox, -1);
                try {
                    upgrade.join(12_000);
                    assertFalse(upgrade.isAlive(), "failed candidate must exit transaction");
                    assertNotNull(result.get());
                    assertNotNull(pending.get(router),
                            "actual failed transaction must register its exact proxy ticket");
                } finally {
                    retained.setInt(mailbox, 0);
                    if (upgrade.isAlive()) upgrade.interrupt();
                }
                assertTrue(mailbox.snapshot().dispatchFrozen());
                assertEquals(1L, router.ref().routeEpoch(),
                        "unpublished candidate cannot change logical route");

                // Candidate stop was requested while its scheduler was
                // captured. Execute the stale scheduling token so physical
                // finalization can complete before rollback reconciliation.
                scheduled.get().run();
                long recoveryDeadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < recoveryDeadline) {
                    router.reapRetired();
                    if (pendingTransfer.get(router) != null) {
                        // Source-transfer rollback and proxy-ticket rollback are
                        // independent recovery owners. The source method can
                        // legitimately return false after clearing its owner
                        // while the proxy quarantine is still active, so its
                        // boolean must not gate the proxy retry.
                        router.reconcileFailedUpgradeRollback();
                    }
                    if (router.reconcileFailedProxyRollback()) break;
                    Thread.sleep(2);
                }
                assertNull(pending.get(router));
                assertFalse(mailbox.snapshot().dispatchFrozen());
                assertEquals(1L, router.ref().routeEpoch());
                assertTrue(router.reconcileFailedProxyRollback());
            } finally {
                dispatcher.set(candidateRuntime, original);
                silent.shutdownNow();
            }
        }
    }
}
