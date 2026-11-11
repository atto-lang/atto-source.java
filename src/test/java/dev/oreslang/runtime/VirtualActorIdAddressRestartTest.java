package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host CONTROL and logical-identity regressions. This is NOT a compiler-backed
 * .atto guest parity test; the native/Graal guest gate remains open.
 */
@Timeout(20)
final class VirtualActorIdAddressRestartTest {
    private static final String TENANT = "tenant-virtual-restart";

    private static ActorRuntime.ControlDispatcher control(ExecutorService pool) {
        return turn -> OresFuture.fromCallback(done -> pool.execute(() -> {
            try {
                turn.run();
                done.resolve(null);
            } catch (Throwable failure) {
                done.reject(failure);
            }
        }));
    }

    @Test
    void chainedInspectionReusesGrantOnlyAfterPreviousPhysicalControlTurnExits()
            throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     ActorRuntime.DispatcherConfig.defaults(),
                     ActorRuntime.TurnExecutor.direct(), control(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var directory = supervisor.virtualActorDirectory(TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "same-grant");
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(5));
                CompletionStage<Optional<VirtualActorSupervisorDirectory.Snapshot>> chain =
                        directory.requestInspect(grant, session, logical.identity(),
                                TimeUnit.SECONDS.toNanos(3));
                // CompletableFuture dependents may execute *inside* complete().
                // Before the fix, completion was published while grant.reading
                // and global pending CONTROL capacity were still held.
                for (int i = 0; i < 32; i++) {
                    chain = chain.thenCompose(previous -> {
                        assertTrue(previous.isPresent());
                        return directory.requestInspect(grant, session, logical.identity(),
                                TimeUnit.SECONDS.toNanos(3));
                    });
                }
                var last = chain.toCompletableFuture().get(10, TimeUnit.SECONDS).orElseThrow();
                assertEquals(logical.identity(), last.identity());
                assertEquals(VirtualActorSupervisorDirectory.Lifecycle.NEVER_ACTIVATED,
                        last.lifecycle());
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }

    @Test
    void idAndAddressLookupKeepLogicalIdentityButNeverRetargetOldPhysicalActor()
            throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     ActorRuntime.DispatcherConfig.defaults(),
                     ActorRuntime.TurnExecutor.direct(), control(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = supervisor.virtualActorDirectory(TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "restart-42");
                ActorRuntime.BehaviorFactory<String> behavior =
                        factory -> (message, context) -> { };
                var oldActor = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), behavior);
                oldActor.getReady().get(5, TimeUnit.SECONDS);
                long oldGeneration = directory.bindActivation(logical, oldActor);

                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(5));
                var oldView = directory.requestInspect(grant, session, logical.identity(),
                        TimeUnit.SECONDS.toNanos(2)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).orElseThrow();
                String address = oldView.address();
                assertEquals(oldActor.id(), oldView.actorId().orElseThrow());

                oldActor.stop();
                oldActor.done().get(5, TimeUnit.SECONDS);

                var nextActor = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), behavior);
                nextActor.getReady().get(5, TimeUnit.SECONDS);
                long nextGeneration = directory.bindActivation(logical, nextActor);
                assertTrue(nextGeneration > oldGeneration);
                assertNotEquals(oldActor.id(), nextActor.id());

                var byAddress = directory.requestInspectAddress(grant, session, address,
                        TimeUnit.SECONDS.toNanos(2)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).orElseThrow();
                var byId = directory.requestInspect(grant, session, logical.identity(),
                        TimeUnit.SECONDS.toNanos(2)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals(address, byAddress.address());
                assertEquals(logical.identity(), byAddress.identity());
                assertEquals(byId.identity(), byAddress.identity());
                assertEquals(nextGeneration, byAddress.activationGeneration());
                assertEquals(nextGeneration, byId.activationGeneration());
                assertEquals(nextActor.id(), byAddress.actorId().orElseThrow());
                assertEquals(nextActor.id(), byId.actorId().orElseThrow());
                assertNotEquals(oldView.actorId(), byAddress.actorId());
                assertEquals(0, directory.pendingInspectionCount());

                nextActor.stop();
                nextActor.done().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void queuedTimeoutReleasesGrantButRetainsSlotUntilPhysicalControlDrain() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            try (ExecutorService pool = Executors.newSingleThreadExecutor();
                 var runtime = new ActorRuntime(IsolatePolicy.developer(),
                         ActorRuntime.DispatcherConfig.defaults(),
                         ActorRuntime.TurnExecutor.direct(), control(pool))) {
                var supervisor = runtime.createSupervisor();
                try (var directory = supervisor.virtualActorDirectory(TENANT, 8, 4)) {
                    var logical = directory.resolve("Cart", "queued-timeout");
                    // Park the CONTROL carrier before admission so both
                    // requests are queued; avoid sleeps and timing assumptions.
                    pool.execute(() -> {
                        started.countDown();
                        try { blocked.await(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    Object session = new Object();
                    var grant = directory.issueInspectionGrant(
                            session, TimeUnit.SECONDS.toNanos(5));
                    var first = directory.requestInspect(grant, session, logical.identity(),
                            TimeUnit.MILLISECONDS.toNanos(100));
                    // One grant never authorizes concurrent observation turns.
                    assertThrows(IllegalStateException.class,
                            () -> directory.requestInspect(grant, session, logical.identity(),
                                    TimeUnit.SECONDS.toNanos(2)));
                    ExecutionException timedOut = assertThrows(ExecutionException.class,
                            () -> first.toCompletableFuture().get(2, TimeUnit.SECONDS));
                    assertInstanceOf(TimeoutException.class, timedOut.getCause());
                    // The grant is reusable after queued cancellation, but the
                    // undispatched CONTROL callback still consumes global credit.
                    assertEquals(1, directory.pendingInspectionCount());
                    var second = directory.requestInspect(grant, session, logical.identity(),
                            TimeUnit.SECONDS.toNanos(2));
                    assertEquals(2, directory.pendingInspectionCount());
                    blocked.countDown();
                    var observed = second.toCompletableFuture().get(5, TimeUnit.SECONDS)
                            .orElseThrow();
                    assertEquals(logical.identity(), observed.identity());
                    assertEquals(0, directory.pendingInspectionCount());
                }
            }
        } finally {
            blocked.countDown(); // Never strand a self-hosted runner on assertion failure.
        }
    }

    @Test
    void inspectionGrantRejectsForeignSessionAndRevocationWithoutAdmission() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     ActorRuntime.DispatcherConfig.defaults(),
                     ActorRuntime.TurnExecutor.direct(), control(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var directory = supervisor.virtualActorDirectory(TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "grant-auth");
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(5));
                assertThrows(SecurityException.class,
                        () -> directory.requestInspect(grant, new Object(), logical.identity(),
                                TimeUnit.SECONDS.toNanos(1)));
                directory.revoke(grant);
                assertThrows(SecurityException.class,
                        () -> directory.requestInspect(grant, session, logical.identity(),
                                TimeUnit.SECONDS.toNanos(1)));
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }
}
