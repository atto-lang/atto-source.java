package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only current-main directory projection. Never count these as compiled
 * guest .atto or Graal Native Image parity evidence.
 */
@Timeout(30)
final class VirtualActorCurrentMainDirectoryTest {
    private static final String TENANT = "tenant-a";

    private static ActorRuntime.ActorRef<String> worker(ActorRuntime.ActorGroup group)
            throws Exception {
        var ref = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                IsolatePolicy.developer(),
                factory -> (message, context) -> { });
        ref.getReady().get(5, TimeUnit.SECONDS);
        return ref;
    }

    @Test
    void canonicalLogicalAddressAndLookupNeverAllocateAnActivation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var logical = directory.resolve("7:defaultCart", "42");
                assertEquals("atto-va1.dGVuYW50LWE.NzpkZWZhdWx0Q2FydA.NDI",
                        directory.address(logical));
                assertEquals(logical, VirtualActorAddressV1.decode(directory.address(logical)));
                assertTrue(directory.inspect(grant, session, logical).isEmpty(),
                        "lookup alone must not reserve or activate a physical actor");
                assertTrue(directory.inspectAddress(grant, session, directory.address(logical)).isEmpty());
                assertTrue(directory.list(grant, session, 10, 0L, null).entries().isEmpty());

                assertEquals(1L, group.reserveNextVirtualActorGeneration(logical));
                var activating = directory.inspect(grant, session, logical).orElseThrow();
                assertEquals(1L, activating.activationGeneration());
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.ACTIVATING,
                        activating.lifecycle());
                assertTrue(activating.physicalActorId().isEmpty());
                assertTrue(activating.partial());
                assertEquals(group.supervisorId(), activating.supervisorId());
                group.abortVirtualActorGeneration(logical, 1L);
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.ABORTED,
                        directory.inspect(grant, session, logical).orElseThrow().lifecycle());
            }
            group.close();
        }
    }

    @Test
    void directoryReportsPhysicalOwnershipUntilActualRetirement() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var id = directory.resolve("Cart", "owner");
                var actor = worker(group);
                assertEquals(1L, group.reserveNextVirtualActorGeneration(id));
                group.bindVirtualActorIdentity(actor, id, 1L);

                var bound = directory.inspect(grant, session, id).orElseThrow();
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PHYSICAL_OWNERSHIP_HELD,
                        bound.lifecycle());
                assertEquals(actor.id(), bound.physicalActorId().orElseThrow());

                actor.stop();
                actor.getDone().get(5, TimeUnit.SECONDS);
                var retired = directory.inspectAddress(
                        grant, session, directory.address(id)).orElseThrow();
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PASSIVATED,
                        retired.lifecycle());
                assertEquals(1L, retired.activationGeneration());
                assertTrue(retired.physicalActorId().isEmpty());

                assertEquals(2L, group.reserveNextVirtualActorGeneration(id));
                assertEquals(2L, directory.inspect(grant, session, id)
                        .orElseThrow().activationGeneration());
                group.abortVirtualActorGeneration(id, 2L);
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.ABORTED,
                        directory.inspect(grant, session, id).orElseThrow().lifecycle(),
                        "newer failed initialization must not be masked by old passivation");
            }
            group.close();
        }
    }

    @Test
    void pagesAreBoundedSortedAndRevisionFencedAcrossLifecycleMutations()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var alpha = directory.resolve("Cart", "alpha");
                var beta = directory.resolve("Cart", "beta");
                var zeta = directory.resolve("Cart", "zeta");
                group.reserveVirtualActorGeneration(zeta, 1L);
                group.reserveVirtualActorGeneration(alpha, 1L);
                group.reserveVirtualActorGeneration(beta, 1L);

                var first = directory.list(grant, session, 1, 0L, null);
                assertEquals(1, first.entries().size());
                assertEquals(alpha, first.entries().getFirst().identity());
                assertEquals(alpha, first.nextAfter().orElseThrow());
                assertThrows(IllegalArgumentException.class,
                        () -> directory.list(grant, session, 1, 0L,
                                first.nextAfter().orElseThrow()),
                        "a cursor must never bypass stale-page validation with revision zero");
                var second = directory.list(
                        grant, session, 1, first.revision(), first.nextAfter().orElseThrow());
                assertEquals(beta, second.entries().getFirst().identity());
                assertEquals(beta, second.nextAfter().orElseThrow());
                var third = directory.list(
                        grant, session, 1, second.revision(), second.nextAfter().orElseThrow());
                assertEquals(zeta, third.entries().getFirst().identity());
                assertTrue(third.nextAfter().isEmpty());

                group.abortVirtualActorGeneration(beta, 1L);
                assertThrows(VirtualActorRuntimeGenerationFence.StaleDirectoryPageException.class,
                        () -> directory.list(grant, session, 1,
                                first.revision(), first.nextAfter().orElseThrow()));
                var refreshed = directory.list(grant, session, 10, 0L, null);
                assertTrue(refreshed.revision() > first.revision());
                assertEquals(3, refreshed.entries().size());
                assertThrows(IllegalArgumentException.class,
                        () -> directory.list(grant, session, 65, 0L, null));
                group.abortVirtualActorGeneration(alpha, 1L);
                group.abortVirtualActorGeneration(zeta, 1L);
            }
            group.close();
        }
    }

    @Test
    void grantsAuthenticateBeforeAddressParsingAndRejectCrossTenantOrSession()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var a = new VirtualActorCurrentMainDirectory(group, TENANT);
                 var b = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                Object foreign = new Object();
                var grant = a.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var identity = a.resolve("Cart", "42");

                assertThrows(SecurityException.class,
                        () -> a.inspectAddress(grant, foreign, "not-a-valid-address"));
                assertThrows(SecurityException.class,
                        () -> a.inspectAddress(null, session, "not-a-valid-address"));
                assertThrows(SecurityException.class,
                        () -> b.inspect(grant, session, identity));
                assertThrows(SecurityException.class,
                        () -> a.inspect(grant, session,
                                new VirtualActorActivationRegistry.Identity("other-tenant", "Cart", "42")));
                assertThrows(SecurityException.class,
                        () -> a.list(grant, session, 1, 0L,
                                new VirtualActorActivationRegistry.Identity("other-tenant", "Cart", "42")));
                assertThrows(IllegalArgumentException.class,
                        () -> a.inspectAddress(grant, session, "not-a-valid-address"));
                assertThrows(IllegalArgumentException.class,
                        () -> a.address(new VirtualActorActivationRegistry.Identity(
                                TENANT, "Cart", "cafe\u0301")));
                a.revoke(grant);
                assertThrows(SecurityException.class,
                        () -> a.inspect(grant, session, identity));

                var shortLived = a.issueGrant(session, 1L);
                assertThrows(SecurityException.class,
                        () -> a.inspect(shortLived, session, identity));
            }
            group.close();
        }
    }

    @Test
    void foreignSupervisorCannotInspectOrListOtherSupervisorIdentity()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.createActorGroup(runtime.createSupervisor());
            var foreign = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(first, TENANT);
                 var other = new VirtualActorCurrentMainDirectory(foreign, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var outsiderGrant = other.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var id = directory.resolve("Cart", "private-one");
                first.reserveVirtualActorGeneration(id, 1L);
                assertEquals(1L, directory.inspect(grant, session, id)
                        .orElseThrow().activationGeneration());
                assertTrue(other.inspect(outsiderGrant, session, id).isEmpty());
                assertTrue(other.list(outsiderGrant, session, 10, 0L, null)
                        .entries().isEmpty());
                first.abortVirtualActorGeneration(id, 1L);
            }
            first.close();
            foreign.close();
        }
    }

    @Test
    void actorGuestCannotUseSupervisorInspectionGrant() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var logical = directory.resolve("Cart", "private");
                CountDownLatch attempted = new CountDownLatch(1);
                AtomicReference<Throwable> error = new AtomicReference<>();
                var guest = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), factory -> (message, context) -> {
                            try {
                                directory.inspect(grant, session, logical);
                            } catch (Throwable failure) {
                                error.set(failure);
                            } finally {
                                attempted.countDown();
                            }
                        });
                guest.getReady().get(5, TimeUnit.SECONDS);
                guest.send("probe");
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, error.get());
                guest.stop();
                guest.getDone().get(5, TimeUnit.SECONDS);
            }
            group.close();
        }
    }

    @Test
    @Timeout(40)
    void heldPhysicalCleanupDoesNotInventPassivation() throws Exception {
        try (ExecutorService stopper = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            runtime.setActorExitHook(domain -> {
                inside.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("retirement hook timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("retirement interrupted", interrupted);
                }
            });
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var id = directory.resolve("Cart", "held");
                var actor = worker(group);
                group.reserveVirtualActorGeneration(id, 1L);
                group.bindVirtualActorIdentity(actor, id, 1L);
                var stopping = stopper.submit(() -> { actor.stop(); return null; });
                try {
                    assertTrue(inside.await(5, TimeUnit.SECONDS));
                    assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PHYSICAL_OWNERSHIP_HELD,
                            directory.inspect(grant, session, id).orElseThrow().lifecycle());
                } finally {
                    release.countDown();
                }
                stopping.get(5, TimeUnit.SECONDS);
                actor.getDone().get(5, TimeUnit.SECONDS);
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PASSIVATED,
                        directory.inspect(grant, session, id).orElseThrow().lifecycle());
            }
            group.close();
        }
    }

    @Test
    void failedExitHookAppearsQuarantinedAndCannotBeMistakenForPassivation()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var identity = directory.resolve("Cart", "failed-cleanup");
                var physical = worker(group);
                group.reserveVirtualActorGeneration(identity, 1L);
                group.bindVirtualActorIdentity(physical, identity, 1L);
                runtime.setActorExitHook(domain -> {
                    throw new IllegalStateException("intentional physical exit failure");
                });
                physical.stop();
                assertThrows(ExecutionException.class,
                        () -> physical.getDone().get(5, TimeUnit.SECONDS));
                var snapshot = directory.inspect(grant, session, identity).orElseThrow();
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.QUARANTINED,
                        snapshot.lifecycle());
                assertEquals(physical.id(), snapshot.physicalActorId().orElseThrow(),
                        "failed cleanup must retain exact physical ownership");
                assertThrows(IllegalStateException.class,
                        () -> group.reserveNextVirtualActorGeneration(identity));
            }
            group.close();
        }
    }

    @Test
    void terminalControlAsyncPendingAndFailureAreProjectedFromSameGenerationLedger()
            throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(),
                     ActorRuntime.TurnExecutor.direct(),
                     turn -> {
                         var receipt = new OresFuture<Void>();
                         control.execute(() -> {
                             try {
                                 turn.run();
                                 receipt.completeFromRuntime(null);
                             } catch (Throwable failure) {
                                 receipt.failFromRuntime(failure);
                             }
                         });
                         return receipt;
                     })) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, TENANT)) {
                Object session = new Object();
                var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
                var id = directory.resolve("Cart", "terminal-pending");
                CompletableFuture<Void> pendingPolicy = new CompletableFuture<>();
                CountDownLatch admitted = new CountDownLatch(1);
                group.installVirtualActorTerminalHandler(notice -> {
                    admitted.countDown();
                    return pendingPolicy;
                });
                var physical = group.spawnWorker(
                        ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                        factory -> (message, context) -> {
                            throw new IllegalStateException("PRIVATE STATE NEVER EXPOSED");
                        });
                physical.getReady().get(5, TimeUnit.SECONDS);
                group.reserveVirtualActorGeneration(id, 1L);
                group.bindVirtualActorIdentity(physical, id, 1L);
                physical.send("fail");
                assertThrows(ExecutionException.class,
                        () -> physical.getDone().get(5, TimeUnit.SECONDS));
                assertTrue(admitted.await(5, TimeUnit.SECONDS));
                var waiting = directory.inspect(grant, session, id).orElseThrow();
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.TERMINAL_PENDING,
                        waiting.lifecycle());
                assertTrue(waiting.physicalActorId().isEmpty());
                assertFalse(waiting.toString().contains("PRIVATE STATE"));
                assertThrows(IllegalStateException.class,
                        () -> group.reserveNextVirtualActorGeneration(id));
                pendingPolicy.completeExceptionally(
                        new IllegalStateException("supervisor policy did not commit"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline &&
                        directory.inspect(grant, session, id).orElseThrow().lifecycle()
                                != VirtualActorCurrentMainDirectory.Lifecycle.QUARANTINED) {
                    Thread.sleep(10);
                }
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.QUARANTINED,
                        directory.inspect(grant, session, id).orElseThrow().lifecycle());
                assertThrows(IllegalStateException.class,
                        () -> group.reserveNextVirtualActorGeneration(id));
            }
            group.close();
        }
    }

    @Test
    void directoryCloseRevokesFutureInspectionsWithoutClosingActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var directory = new VirtualActorCurrentMainDirectory(group, TENANT);
            Object session = new Object();
            var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
            var id = directory.resolve("Cart", "stay-alive");
            directory.close();
            assertThrows(SecurityException.class, () -> directory.inspect(grant, session, id));
            assertFalse(group.closed());
            group.close();
        }
    }
}
