package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Current-main host-runtime generation/physical-ownership tests.
 * These are NOT executable .atto guest acceptance or durable epoch receipts.
 */
@Timeout(30)
final class VirtualActorGenerationFenceCurrentMainTest {
    private static final VirtualActorActivationRegistry.Identity ID =
            new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "cart-42");

    private static ActorRuntime.BehaviorFactory<String> behavior() {
        return factory -> (message, context) -> { };
    }

    private static ActorRuntime.ActorRef<String> worker(ActorRuntime.ActorGroup group)
            throws Exception {
        var ref = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                IsolatePolicy.developer(), behavior());
        ref.getReady().get(5, TimeUnit.SECONDS);
        return ref;
    }

    @Test
    void abortedReservationBurnsItsGenerationWithoutResurrectingOldReceipt()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            assertEquals(1L, group.reserveNextVirtualActorGeneration(ID));
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 1L),
                    "unbound reservation must not authorize a terminal receipt");
            group.abortVirtualActorGeneration(ID, 1L);
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 1L));
            assertThrows(IllegalStateException.class,
                    () -> group.reserveVirtualActorGeneration(ID, 1L));
            assertEquals(2L, group.reserveNextVirtualActorGeneration(ID));
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 1L));
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 2L),
                    "failed initializer has no terminal authority");
            group.abortVirtualActorGeneration(ID, 2L);
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 2L));
            assertThrows(IllegalStateException.class,
                    () -> group.reserveVirtualActorGeneration(ID, 2L));
            group.close();
        }
    }

    @Test
    void stableSupervisorMayRehomeAfterGroupCloseButForeignSupervisorCannotSteal()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            var first = runtime.createActorGroup(supervisor);
            var next = runtime.createActorGroup(supervisor);
            var foreign = runtime.createActorGroup(runtime.createSupervisor());
            first.reserveVirtualActorGeneration(ID, 1L);
            assertThrows(SecurityException.class,
                    () -> foreign.reserveVirtualActorGeneration(ID, 2L));
            assertThrows(IllegalStateException.class,
                    () -> next.reserveVirtualActorGeneration(ID, 2L),
                    "same supervisor must not steal a live group's logical ownership");
            first.close(); // Burns, never erases, its pending generation.
            next.reserveVirtualActorGeneration(ID, 2L);
            assertFalse(first.isCurrentVirtualActorGeneration(ID, 1L));
            assertFalse(next.isCurrentVirtualActorGeneration(ID, 2L));
            var replacement = worker(next);
            next.bindVirtualActorIdentity(replacement, ID, 2L);
            assertTrue(next.isCurrentVirtualActorGeneration(ID, 2L));
            replacement.stop();
            replacement.getDone().get(5, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> foreign.reserveVirtualActorGeneration(ID, 3L));
            next.close();
            assertFalse(next.isCurrentVirtualActorGeneration(ID, 2L));
            foreign.close();
        }
    }

    @Test
    void passivatedActorStillBelongsToOpenGroupUntilExplicitClose()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            var oldGroup = runtime.createActorGroup(supervisor);
            var newGroup = runtime.createActorGroup(supervisor);
            var old = worker(oldGroup);
            oldGroup.reserveVirtualActorGeneration(ID, 1L);
            oldGroup.bindVirtualActorIdentity(old, ID, 1L);
            old.stop();
            old.getDone().get(5, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class,
                    () -> newGroup.reserveNextVirtualActorGeneration(ID),
                    "physical retirement alone does not transfer group ownership");
            assertTrue(oldGroup.isCurrentVirtualActorTerminal(ID, 1L, old.id()));
            oldGroup.close();
            assertFalse(oldGroup.isCurrentVirtualActorTerminal(ID, 1L, old.id()));

            assertEquals(2L, newGroup.reserveNextVirtualActorGeneration(ID));
            var replacement = worker(newGroup);
            newGroup.bindVirtualActorIdentity(replacement, ID, 2L);
            assertFalse(newGroup.isCurrentVirtualActorTerminal(ID, 2L, replacement.id()));
            replacement.stop();
            replacement.getDone().get(5, TimeUnit.SECONDS);
            assertTrue(newGroup.isCurrentVirtualActorTerminal(ID, 2L, replacement.id()));
            newGroup.close();
        }
    }

    @Test
    @Timeout(40)
    void physicalRetirementMustCompleteBeforeReplacementCanBeReserved()
            throws Exception {
        try (ExecutorService stopper = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch retiredHook = new CountDownLatch(1);
            CountDownLatch allowCleanup = new CountDownLatch(1);
            AtomicBoolean firstExit = new AtomicBoolean();
            runtime.setActorExitHook(domain -> {
                if (firstExit.compareAndSet(false, true)) {
                    retiredHook.countDown();
                    try {
                        if (!allowCleanup.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("retirement hook timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("retirement hook interrupted", interrupted);
                    }
                }
            });

            var group = runtime.createActorGroup(runtime.createSupervisor());
            var old = worker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(old, ID, 1L);
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 1L, old.id()),
                    "a live physical worker must never authorize a terminal receipt");
            assertThrows(IllegalStateException.class, () -> group.releaseWorker(old));

            var stopped = stopper.submit(() -> {
                old.stop();
                return null;
            });
            try {
                assertTrue(retiredHook.await(5, TimeUnit.SECONDS),
                        "STOP must reach its host physical-cleanup hook");
                assertThrows(IllegalStateException.class,
                        () -> group.reserveVirtualActorGeneration(ID, 2L));
                assertFalse(group.isCurrentVirtualActorTerminal(ID, 1L, old.id()),
                        "host physical-retirement hook has not yet quiesced");
            } finally {
                allowCleanup.countDown();
            }
            stopped.get(5, TimeUnit.SECONDS);
            old.getDone().get(5, TimeUnit.SECONDS);
            assertTrue(group.isCurrentVirtualActorTerminal(ID, 1L, old.id()),
                    "a retired worker may now produce an exact terminal receipt");

            group.reserveVirtualActorGeneration(ID, 2L);
            var replacement = worker(group);
            group.bindVirtualActorIdentity(replacement, ID, 2L);
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 1L));
            assertTrue(group.isCurrentVirtualActorGeneration(ID, 2L));
            assertThrows(IllegalStateException.class,
                    () -> group.bindVirtualActorIdentity(old, ID, 2L));
            replacement.stop();
            replacement.getDone().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }

    @Test
    void failedPhysicalCleanupMustQuarantineLogicalActivation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var first = worker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(first, ID, 1L);

            // A trusted native/host exit hook may fail after guest STOP.
            // This must not be interpreted as successful physical retirement.
            runtime.setActorExitHook(domain -> {
                throw new IllegalStateException("deliberate physical cleanup failure");
            });
            first.stop();
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> first.getDone().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorFailureSnapshotException.class, error.getCause());
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 1L, first.id()),
                    "no terminal decision may use a failed physical retirement gate");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID),
                    "failed exit hook must quarantine old physical ownership");
            group.close();
        }
    }

    @Test
    @Timeout(60)
    void stopVsBindCannotStrandPhysicalOwnershipOrAdmitStaleGeneration()
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2);
             ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            for (int i = 0; i < 12; i++) {
                var identity = new VirtualActorActivationRegistry.Identity(
                        "tenant-a", "Cart", "race-" + i);
                var old = worker(group);
                group.reserveVirtualActorGeneration(identity, 1L);
                CountDownLatch start = new CountDownLatch(1);
                var binder = pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        group.bindVirtualActorIdentity(old, identity, 1L);
                        return true;
                    } catch (IllegalStateException stoppedFirst) {
                        return false;
                    }
                });
                var stopper = pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    old.stop();
                    return null;
                });
                start.countDown();
                stopper.get(5, TimeUnit.SECONDS);
                boolean bound = binder.get(5, TimeUnit.SECONDS);
                old.getDone().get(5, TimeUnit.SECONDS);
                if (!bound) group.abortVirtualActorGeneration(identity, 1L);

                group.reserveVirtualActorGeneration(identity, 2L);
                var replacement = worker(group);
                group.bindVirtualActorIdentity(replacement, identity, 2L);
                assertFalse(group.isCurrentVirtualActorGeneration(identity, 1L));
                replacement.stop();
                replacement.getDone().get(5, TimeUnit.SECONDS);
            }
            group.close();
        }
    }

    @Test
    void delayedTerminalReceiptMustMatchExactPhysicalActorAndBurnedGeneration()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var old = worker(group);
            group.reserveVirtualActorGeneration(ID, 10L);
            group.bindVirtualActorIdentity(old, ID, 10L);
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 10L, old.id()),
                    "the exact ActorId is insufficient before physical retirement");
            old.stop();
            old.getDone().get(5, TimeUnit.SECONDS);

            assertTrue(group.isCurrentVirtualActorTerminal(ID, 10L, old.id()));
            assertFalse(group.isCurrentVirtualActorTerminal(
                    ID, 10L, ActorRuntime.ActorId.create()),
                    "wrong physical worker must never authorize terminal policy");

            group.reserveVirtualActorGeneration(ID, 11L);
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 10L, old.id()),
                    "new reservation fences any delayed old terminal receipt");
            group.abortVirtualActorGeneration(ID, 11L);
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 10L, old.id()),
                    "abort may not re-authorize the superseded physical actor");

            group.reserveVirtualActorGeneration(ID, 12L);
            var next = worker(group);
            group.bindVirtualActorIdentity(next, ID, 12L);
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 12L, old.id()));
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 12L, next.id()),
                    "a newly bound but still-live generation cannot claim retirement");
            next.stop();
            next.getDone().get(5, TimeUnit.SECONDS);
            assertTrue(group.isCurrentVirtualActorTerminal(ID, 12L, next.id()));
            group.close();
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 12L, next.id()));
        }
    }

    @Test
    void terminalDecisionLeaseIsOneShotAndBlocksReplacementUntilSettled()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var worker = worker(group);
            assertEquals(1L, group.reserveNextVirtualActorGeneration(ID));
            group.bindVirtualActorIdentity(worker, ID, 1L);

            assertThrows(IllegalStateException.class,
                    () -> group.beginVirtualActorTerminalDecision(ID, 1L, worker.id()),
                    "a live worker cannot claim a terminal decision");
            worker.stop();
            worker.getDone().get(5, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class,
                    () -> group.beginVirtualActorTerminalDecision(
                            ID, 1L, ActorRuntime.ActorId.create()),
                    "a wrong physical ActorId cannot claim the decision");
            var lease = group.beginVirtualActorTerminalDecision(ID, 1L, worker.id());
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 1L, worker.id()),
                    "claimed receipt is no longer available for replay");
            assertThrows(IllegalStateException.class,
                    () -> group.beginVirtualActorTerminalDecision(ID, 1L, worker.id()));
            try {
                assertThrows(IllegalStateException.class,
                        () -> group.reserveNextVirtualActorGeneration(ID),
                        "no successor may overtake an in-flight terminal policy");
                // Unrelated identities can still progress: callbacks do not
                // run while holding the runtime ledger monitor.
                var other = new VirtualActorActivationRegistry.Identity(
                        "tenant-a", "Cart", "unrelated");
                assertEquals(1L, group.reserveNextVirtualActorGeneration(other));
                group.abortVirtualActorGeneration(other, 1L);
            } finally {
                lease.close();
                lease.close();
            }
            assertThrows(IllegalStateException.class,
                    () -> group.beginVirtualActorTerminalDecision(ID, 1L, worker.id()),
                    "terminal decisions are exactly once, including after release");
            assertEquals(2L, group.reserveNextVirtualActorGeneration(ID));
            group.abortVirtualActorGeneration(ID, 2L);
            group.close();
        }
    }

    @Test
    void groupCloseCannotReleaseAnInFlightTerminalDecision()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            var first = runtime.createActorGroup(supervisor);
            var next = runtime.createActorGroup(supervisor);
            var actor = worker(first);
            first.reserveVirtualActorGeneration(ID, 1L);
            first.bindVirtualActorIdentity(actor, ID, 1L);
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);

            var lease = first.beginVirtualActorTerminalDecision(ID, 1L, actor.id());
            first.close();
            assertThrows(IllegalStateException.class,
                    () -> next.reserveNextVirtualActorGeneration(ID),
                    "group close must not bypass an outstanding terminal lease");
            lease.close();
            assertEquals(2L, next.reserveNextVirtualActorGeneration(ID));
            next.abortVirtualActorGeneration(ID, 2L);
            next.close();
        }
    }

    @Test
    void concurrentTerminalClaimersCannotBothExecuteOneReceipt() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2);
             ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var physical = worker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(physical, ID, 1L);
            physical.stop();
            physical.getDone().get(5, TimeUnit.SECONDS);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            var winners = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.Callable<Void> claim = () -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try (var ignored = group.beginVirtualActorTerminalDecision(
                        ID, 1L, physical.id())) {
                    winners.incrementAndGet();
                } catch (IllegalStateException alreadyClaimed) {
                    // Expected for precisely one of the concurrent attempts.
                }
                return null;
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, winners.get(), "one terminal receipt has one policy owner");
            assertFalse(group.isCurrentVirtualActorTerminal(ID, 1L, physical.id()));
            assertEquals(2L, group.reserveNextVirtualActorGeneration(ID));
            group.abortVirtualActorGeneration(ID, 2L);
            group.close();
        }
    }

    @Test
    void physicalWorkerCannotOwnMultipleLogicalIdentities() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var secondIdentity = new VirtualActorActivationRegistry.Identity(
                    "tenant-a", "Cart", "second-cart");
            var actor = worker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.reserveVirtualActorGeneration(secondIdentity, 1L);
            group.bindVirtualActorIdentity(actor, ID, 1L);
            assertThrows(IllegalStateException.class,
                    () -> group.bindVirtualActorIdentity(actor, secondIdentity, 1L));
            group.abortVirtualActorGeneration(secondIdentity, 1L);
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }

    @Test
    void noncanonicalAndOversizedLogicalIdentitiesAreRejectedBeforeReservation()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var oversized = new VirtualActorActivationRegistry.Identity(
                    "tenant-a", "Cart", "x".repeat(4096));
            var decomposed = new VirtualActorActivationRegistry.Identity(
                    "tenant-a", "Cart", "cafe\u0301");
            var invalid = new VirtualActorActivationRegistry.Identity(
                    "tenant-a", "Bad\nName", "42");
            assertThrows(IllegalArgumentException.class,
                    () -> group.reserveVirtualActorGeneration(oversized, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> group.reserveVirtualActorGeneration(decomposed, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> group.reserveVirtualActorGeneration(invalid, 1L));
            group.reserveVirtualActorGeneration(ID, 1L);
            group.abortVirtualActorGeneration(ID, 1L);
            group.close();
        }
    }
}
