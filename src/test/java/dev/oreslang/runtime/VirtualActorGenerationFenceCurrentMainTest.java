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
            group.reserveVirtualActorGeneration(ID, 1L);
            assertTrue(group.isCurrentVirtualActorGeneration(ID, 1L));
            group.abortVirtualActorGeneration(ID, 1L);
            assertThrows(IllegalStateException.class,
                    () -> group.reserveVirtualActorGeneration(ID, 1L));
            group.reserveVirtualActorGeneration(ID, 2L);
            assertFalse(group.isCurrentVirtualActorGeneration(ID, 1L));
            assertTrue(group.isCurrentVirtualActorGeneration(ID, 2L));
            group.abortVirtualActorGeneration(ID, 2L);
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
            first.close(); // Burns, never erases, its pending generation.
            next.reserveVirtualActorGeneration(ID, 2L);
            assertFalse(first.isCurrentVirtualActorGeneration(ID, 1L));
            assertTrue(next.isCurrentVirtualActorGeneration(ID, 2L));
            next.abortVirtualActorGeneration(ID, 2L);
            assertThrows(SecurityException.class,
                    () -> foreign.reserveVirtualActorGeneration(ID, 3L));
            next.close();
            foreign.close();
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
            } finally {
                allowCleanup.countDown();
            }
            stopped.get(5, TimeUnit.SECONDS);
            old.getDone().get(5, TimeUnit.SECONDS);

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
