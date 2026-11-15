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
    void failedDispatchAfterQueueCannotRunALateControlTurn() throws Exception {
        var lateTurn = new AtomicReference<Runnable>();
        ActorRuntime.ControlDispatcher dispatcher = turn -> {
            lateTurn.set(turn);
            throw new IllegalStateException("dispatcher failed after enqueue");
        };
        try (var runtime = runtime(dispatcher);
             var service = new SupervisorControlSnapshotCoordinator(runtime);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var failed = read(service, g, session);
            assertCode(failed, "CONTROL_UNAVAILABLE");
            assertEquals(0, service.activeControlRequests());
            assertNotNull(lateTurn.get());
            // An old queued command is no longer authorized to start after
            // its dispatch-failure path won the physical start/abandon gate.
            worker.submit(lateTurn.get()).get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void queuedDispatchFailureNotifiesOnlyAfterSlotRetirement() throws Exception {
        var queuedTurn = new AtomicReference<Runnable>();
        var dispatcherCompletion = new AtomicReference<OresFuture.Callback<Void>>();
        var realCaptureCalls = new AtomicInteger();
        ActorRuntime.ControlDispatcher dispatcher = turn ->
                OresFuture.<Void>fromCallback(done -> {
                    queuedTurn.set(turn);
                    dispatcherCompletion.set(done);
                });
        try (var runtime = runtime(dispatcher);
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         realCaptureCalls.incrementAndGet();
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            assertEquals(1, service.activeControlRequests());
            assertNotNull(queuedTurn.get());
            assertNotNull(dispatcherCompletion.get());
            // Inspect accounting *in* the completion notification, not only
            // after callback.reject() returns. Previously the dispatcher
            // reported failure before releasing an unstarted physical slot.
            var slotsAtFailure = new CompletableFuture<Integer>();
            pending.whenComplete((ignored, failure) ->
                    slotsAtFailure.complete(service.activeControlRequests()));
            dispatcherCompletion.get().reject(
                    new IllegalStateException("queued dispatcher rejected work"));
            assertCode(pending, "CONTROL_DISPATCH_FAILED");
            assertEquals(0, slotsAtFailure.get(5, TimeUnit.SECONDS).intValue(),
                    "CONTROL_DISPATCH_FAILED was published before slot retirement");
            assertEquals(0, service.activeControlRequests());
            // A buggy dispatcher can still run its stale queued Runnable;
            // the CAS-abandoned observation must neither capture nor free
            // any subsequently admitted request's reservation.
            Runnable stale = queuedTurn.get();
            var replacement = read(service, grant(service, runtime, session), session);
            assertEquals(1, service.activeControlRequests());
            worker.submit(stale).get(5, TimeUnit.SECONDS);
            assertEquals(0, realCaptureCalls.get());
            assertEquals(1, service.activeControlRequests());
            // The dispatcher has replaced its queued pointer by the second
            // request: execute that real turn and settle the receipt.
            worker.submit(queuedTurn.get()).get(5, TimeUnit.SECONDS);
            assertEquals("partial",
                    replacement.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void dispatchFailureWhileControlIsRunningRetainsPhysicalSlotUntilExit() throws Exception {
        var turn = new AtomicReference<Runnable>();
        var completion = new AtomicReference<OresFuture.Callback<Void>>();
        var captureEntered = new CountDownLatch(1);
        var finishCapture = new CountDownLatch(1);
        ActorRuntime.ControlDispatcher dispatcher = task ->
                OresFuture.<Void>fromCallback(done -> {
                    turn.set(task);
                    completion.set(done);
                });
        try (var runtime = runtime(dispatcher);
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         captureEntered.countDown();
                         try {
                             if (!finishCapture.await(5, TimeUnit.SECONDS))
                                 throw new IllegalStateException("test capture timed out");
                         } catch (InterruptedException interrupted) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("test interrupted", interrupted);
                         }
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            var job = worker.submit(turn.get());
            try {
                assertTrue(captureEntered.await(2, TimeUnit.SECONDS),
                        "physical CONTROL turn never entered observation");
                completion.get().reject(new IllegalStateException("dispatcher completion failed"));
                assertCode(pending, "CONTROL_DISPATCH_FAILED");
                assertEquals(1, service.activeControlRequests(),
                        "dispatch failure freed an actively executing CONTROL slot");
            } finally {
                finishCapture.countDown();
            }
            job.get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void revokeAndCloseCannotReleaseRunningControlTurn() throws Exception {
        var submitted = new AtomicReference<Runnable>();
        var dispatchDone = new AtomicReference<OresFuture.Callback<Void>>();
        var captureEntered = new CountDownLatch(1);
        var finishCapture = new CountDownLatch(1);
        ActorRuntime.ControlDispatcher dispatcher = turn ->
                OresFuture.<Void>fromCallback(done -> {
                    submitted.set(turn);
                    dispatchDone.set(done);
                });
        try (var runtime = runtime(dispatcher);
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         captureEntered.countDown();
                         try {
                             if (!finishCapture.await(5, TimeUnit.SECONDS))
                                 throw new IllegalStateException("test capture timed out");
                         } catch (InterruptedException interrupted) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("test interrupted", interrupted);
                         }
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            assertNotNull(submitted.get());
            var physicalTurn = worker.submit(submitted.get());
            try {
                assertTrue(captureEntered.await(2, TimeUnit.SECONDS),
                        "the legitimate observation did not enter its physical turn");
                service.revoke(g);
                assertCode(pending, "DENIED");
                assertEquals(1, service.activeControlRequests(),
                        "revocation must never free an actively running CONTROL turn");
                service.close();
                assertEquals(1, service.activeControlRequests(),
                        "coordinator close must wait for physical turn exit");
            } finally {
                finishCapture.countDown();
            }
            physicalTurn.get(5, TimeUnit.SECONDS);
            dispatchDone.get().resolve(null);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void duplicateControlRunnableCannotFailItsOriginalInFlightRead() throws Exception {
        var submitted = new AtomicReference<Runnable>();
        var completion = new AtomicReference<OresFuture.Callback<Void>>();
        var captureEntered = new CountDownLatch(1);
        var finishCapture = new CountDownLatch(1);
        ActorRuntime.ControlDispatcher dispatcher = task ->
                OresFuture.<Void>fromCallback(done -> {
                    submitted.set(task);
                    completion.set(done);
                });
        try (var runtime = runtime(dispatcher);
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         captureEntered.countDown();
                         try {
                             if (!finishCapture.await(5, TimeUnit.SECONDS))
                                 throw new IllegalStateException("test capture timed out");
                         } catch (InterruptedException interrupted) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("test interrupted", interrupted);
                         }
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newFixedThreadPool(2)) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            assertNotNull(submitted.get());
            var original = worker.submit(submitted.get());
            try {
                assertTrue(captureEntered.await(2, TimeUnit.SECONDS),
                        "original physical CONTROL turn never started");
                // The same callback is run a second time by a broken
                // dispatcher while the original retains its actor lease.
                worker.submit(submitted.get()).get(5, TimeUnit.SECONDS);
                assertFalse(pending.toCompletableFuture().isDone(),
                        "duplicate command corrupted the original read result");
                assertEquals(1, service.activeControlRequests(),
                        "duplicate command released the original physical lease");
            } finally {
                finishCapture.countDown();
            }
            original.get(5, TimeUnit.SECONDS);
            assertEquals("partial",
                    pending.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            completion.get().resolve(null);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void queuedDeadlineAbandonsUnstartedTurnAndReclaimsCapacity() throws Exception {
        var queue = new QueuedControl();
        var realCaptureCalls = new AtomicInteger();
        try (var runtime = runtime(queue.dispatcher());
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         realCaptureCalls.incrementAndGet();
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = service.request(g, session, TENANT, 1, 16, 8,
                    TimeUnit.MILLISECONDS.toNanos(20));
            ExecutionException expired = assertThrows(ExecutionException.class,
                    () -> pending.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, expired.getCause());
            assertEquals(0, service.activeControlRequests(),
                    "expired queued read must atomically release its slot");

            // The abandoned dispatcher job still exists, but cannot run.
            // New work may be admitted before that stale job is drained.
            var replacement = read(service, grant(service, runtime, session), session);
            assertEquals(1, service.activeControlRequests());
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, realCaptureCalls.get(),
                    "late physical turn must be denied after queued timeout");
            assertEquals(1, service.activeControlRequests(),
                    "stale dispatcher cannot release the replacement's lease");
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals("partial",
                    replacement.toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void runningDeadlineKeepsSlotUntilPhysicalControlExit() throws Exception {
        var turn = new AtomicReference<Runnable>();
        var dispatchDone = new AtomicReference<OresFuture.Callback<Void>>();
        var captureEntered = new CountDownLatch(1);
        var finishCapture = new CountDownLatch(1);
        ActorRuntime.ControlDispatcher dispatcher = work ->
                OresFuture.<Void>fromCallback(done -> {
                    turn.set(work);
                    dispatchDone.set(done);
                });
        try (var runtime = runtime(dispatcher);
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         captureEntered.countDown();
                         try {
                             if (!finishCapture.await(5, TimeUnit.SECONDS))
                                 throw new IllegalStateException("test capture timed out");
                         } catch (InterruptedException interrupted) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("test interrupted", interrupted);
                         }
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var grant = grant(service, runtime, session);
            var result = service.request(grant, session, TENANT, 1, 16, 8,
                    TimeUnit.SECONDS.toNanos(3));
            assertNotNull(turn.get());
            var physical = worker.submit(turn.get());
            try {
                assertTrue(captureEntered.await(2, TimeUnit.SECONDS),
                        "observation must begin before its deadline");
                ExecutionException expired = assertThrows(ExecutionException.class,
                        () -> result.toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.TimeoutException.class, expired.getCause());
                assertEquals(1, service.activeControlRequests(),
                        "deadline must not free physically running CONTROL lease");
            } finally {
                finishCapture.countDown();
            }
            physical.get(5, TimeUnit.SECONDS);
            dispatchDone.get().resolve(null);
            assertEquals(0, service.activeControlRequests());
        }
    }

    @Test
    void revocationAfterAdmissionBlocksLateObservedResults() throws Exception {
        var queue = new QueuedControl();
        var realCaptureCalls = new AtomicInteger();
        try (var runtime = runtime(queue.dispatcher());
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         realCaptureCalls.incrementAndGet();
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            assertEquals(1, service.activeControlRequests());
            // A future observer may attempt new admission *as soon as* the
            // denial is published. It must see the reclaimed queued slot,
            // not a stale reservation released after notification.
            var slotsAtDenial = new CompletableFuture<Integer>();
            pending.whenComplete((ignored, failure) ->
                    slotsAtDenial.complete(service.activeControlRequests()));
            service.revoke(g);
            assertCode(pending, "DENIED");
            assertEquals(0, slotsAtDenial.get(5, TimeUnit.SECONDS).intValue(),
                    "DENIED completion preceded queued slot cleanup");
            assertEquals(0, service.activeControlRequests(),
                    "revoked queued request must release via atomic abandon");
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, realCaptureCalls.get(),
                    "late dispatcher must not enter observation after revoke");
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
            var first = grant(service, runtime, session);
            read(service, first, session);
            for (int i = 1; i < 8; i++)
                read(service, grant(service, runtime, session), session);
            assertEquals(8, service.activeControlRequests());
            var extra = grant(service, runtime, session);
            var error = assertThrows(SupervisorControlSnapshotCoordinator.SnapshotFailure.class,
                    () -> read(service, extra, session));
            assertEquals("CONTROL_OBSERVATION_BUSY", error.code());

            // The first queued observation has never started. Revocation
            // must atomically release its lease and make room for a ninth
            // (distinct) grant without trusting queue execution or timeout.
            service.revoke(first);
            assertEquals(7, service.activeControlRequests());
            read(service, extra, session);
            assertEquals(8, service.activeControlRequests());

            // Nine dispatcher callbacks remain queued: the revoked one must
            // be rejected by tryStart, and the other eight retire normally.
            for (int i = 0; i < 9; i++)
                worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
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
        var realCaptureCalls = new AtomicInteger();
        try (var runtime = runtime(queue.dispatcher());
             var observer = new SupervisorRegistryObservation(
                     runtime, System::nanoTime, () -> {
                         realCaptureCalls.incrementAndGet();
                         return java.time.Instant.now();
                     });
             var service = new SupervisorControlSnapshotCoordinator(runtime, observer);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Object session = new Object();
            var g = grant(service, runtime, session);
            var pending = read(service, g, session);
            var slotsAtClose = new CompletableFuture<Integer>();
            pending.whenComplete((ignored, failure) ->
                    slotsAtClose.complete(service.activeControlRequests()));
            service.close();
            assertCode(pending, "COORDINATOR_CLOSED");
            assertEquals(0, slotsAtClose.get(5, TimeUnit.SECONDS).intValue(),
                    "CLOSED completion preceded queued slot cleanup");
            assertEquals(0, service.activeControlRequests(),
                    "closing queued request must release via atomic abandon");
            worker.submit(queue.next()).get(5, TimeUnit.SECONDS);
            assertEquals(0, realCaptureCalls.get(),
                    "late dispatcher must not enter observation after close");
            assertEquals(0, service.activeControlRequests());
        }
    }
}
