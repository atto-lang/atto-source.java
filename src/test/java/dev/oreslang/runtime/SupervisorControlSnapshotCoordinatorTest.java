package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable host/control-admission tests against a REAL ActorRuntime registry.
 * A fake VM CONTROL dispatcher is explicitly injected; this is not end-to-end
 * OresVM priority/CONTROL delivery or guest-code compilation proof.
 */
@Timeout(20)
final class SupervisorControlSnapshotCoordinatorTest {
    private static final String TENANT = "tenant-1";
    private static final long TTL = TimeUnit.SECONDS.toNanos(20);

    private static ActorRuntime runtime(ActorRuntime.ControlDispatcher dispatcher) {
        return new ActorRuntime(IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(), dispatcher);
    }

    private static CompletionStage<SupervisorRegistryObservation.Snapshot> read(
            SupervisorControlSnapshotCoordinator service,
            SupervisorRegistryObservation.Grant grant, Object session) {
        return service.request(grant, session, TENANT, 1, 16, 8,
                TimeUnit.SECONDS.toNanos(2));
    }

    private static SupervisorRegistryObservation.Grant grant(
            SupervisorControlSnapshotCoordinator service,
            ActorRuntime runtime, Object session) {
        return service.issueGrant(session, TENANT, runtime.rootSupervisor(), TTL);
    }

    private static void assertCode(CompletionStage<?> future, String expected)
            throws InterruptedException {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(SupervisorControlSnapshotCoordinator.SnapshotFailure.class,
                failure.getCause());
        assertEquals(expected,
                ((SupervisorControlSnapshotCoordinator.SnapshotFailure) failure.getCause()).code());
    }

    private static final class QueuedControl {
        private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        private final AtomicInteger submitted = new AtomicInteger();
        ActorRuntime.ControlDispatcher dispatcher() {
            return turn -> OresFuture.fromCallback(callback -> {
                submitted.incrementAndGet();
                jobs.addLast(() -> {
                    try {
                        turn.run();
                        callback.resolve(null);
                    } catch (Throwable failure) {
                        callback.reject(failure);
                    }
                });
            });
        }
        Runnable next() { return jobs.removeFirst(); }
    }

    @Test
    void boundControlWorkerExecutesRealRegistryObservation() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var count = new AtomicInteger();
            ActorRuntime.ControlDispatcher dispatcher = turn -> OresFuture.fromCallback(done -> {
                count.incrementAndGet();
                pool.execute(() -> {
                    try {
                        turn.run();
                        done.resolve(null);
                    } catch (Throwable error) {
                        done.reject(error);
                    }
                });
            });
            try (var runtime = runtime(dispatcher);
                 var service = new SupervisorControlSnapshotCoordinator(runtime)) {
                var root = runtime.rootSupervisor();
                var child = runtime.createChildSupervisor(root);
                Object session = new Object();
                var result = read(service, grant(service, runtime, session), session)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("partial", result.status());
                assertEquals(2, result.supervisors().size());
                assertTrue(result.supervisors().stream().anyMatch(n ->
                        n.id().equals(child.id().value().toString())));
                assertTrue(result.actors().isEmpty());
                assertEquals(1, count.get());
                assertEquals(0, service.activeControlRequests());
            }
        }
    }

    @Test
    void missingVmControlDispatcherFailsClosed() throws Exception {
        try (var runtime = new ActorRuntime();
             var service = new SupervisorControlSnapshotCoordinator(runtime)) {
            Object session = new Object();
            assertCode(read(service, grant(service, runtime, session), session),
                    "CONTROL_UNAVAILABLE");
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void failedAuthorizationNeverSubmitsControlWork() {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime)) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            assertThrows(SecurityException.class, () ->
                    read(service, g, new Object()));
            assertThrows(SecurityException.class, () ->
                    service.request(g, session, "other", 1, 16, 8, 1_000));
            assertThrows(SecurityException.class, () ->
                    service.request(g, session, TENANT, 2, 16, 8, 1_000));
            assertEquals(0, queue.submitted.get());
        }
    }

    @Test
    void queuedDeadlineRetainsCapacityUntilPhysicalTurnFinishes() throws Exception {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = service.request(g, session, TENANT, 1, 16, 8,
                    TimeUnit.MILLISECONDS.toNanos(20));
            assertThrows(ExecutionException.class,
                    () -> pending.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, service.activeControlRequests());
            assertThrows(SupervisorControlSnapshotCoordinator.SnapshotFailure.class,
                    () -> read(service, g, session));
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void revocationAfterAdmissionBlocksLateObservedResults() throws Exception {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            assertEquals(1, service.activeControlRequests());
            service.revoke(g);
            assertCode(pending, "DENIED");
            assertEquals(1, service.activeControlRequests());
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
            assertThrows(SecurityException.class, () -> read(service, g, session));
        }
    }

    @Test
    void globalEightSlotBudgetBoundsQueuedControl() throws Exception {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            for (int i = 0; i < 8; i++)
                read(service, grant(service, runtime, session), session);
            assertEquals(8, service.activeControlRequests());
            var extra = grant(service, runtime, session);
            var error = assertThrows(SupervisorControlSnapshotCoordinator.SnapshotFailure.class,
                    () -> read(service, extra, session));
            assertEquals("CONTROL_OBSERVATION_BUSY", error.code());
            for (int i = 0; i < 8; i++) worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void inlineDispatcherCannotPassForControlWorker() throws Exception {
        ActorRuntime.ControlDispatcher inline = turn -> {
            turn.run();
            return OresFuture.completed(null);
        };
        try (var runtime = runtime(inline);
             var service = new SupervisorControlSnapshotCoordinator(runtime)) {
            Object session = new Object();
            assertCode(read(service, grant(service, runtime, session), session),
                    "INLINE_CONTROL_DISPATCH_FORBIDDEN");
        }
    }

    @Test
    void guestActorCannotMintOrScheduleControlSnapshots() throws Exception {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime)) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var actor = runtime.spawnPrivateTrusted(factory ->
                (message, context) -> {
                    try {
                        service.issueGrant(session, TENANT, runtime.rootSupervisor(), TTL);
                        unexpected.set(new AssertionError("actor minted CONTROL observation"));
                    } catch (SecurityException expected) {
                        // Host-only admission.
                    } catch (Throwable error) {
                        unexpected.set(error);
                    }
                    try {
                        read(service, g, session);
                        unexpected.set(new AssertionError("actor scheduled CONTROL read"));
                    } catch (SecurityException expected) {
                        // Host-only admission.
                    } catch (Throwable error) {
                        unexpected.set(error);
                    } finally {
                        done.countDown();
                    }
                });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("try");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get());
            assertEquals(0, queue.submitted.get());
            actor.stop();
            actor.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void coordinatorCloseRejectsQueuedRequestsWithoutPretendingRetirement() throws Exception {
        var queue = new QueuedControl();
        try (var runtime = runtime(queue.dispatcher());
             var service = new SupervisorControlSnapshotCoordinator(runtime);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            service.close();
            assertCode(pending, "COORDINATOR_CLOSED");
            assertEquals(1, service.activeControlRequests());
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
        }
    }
}
