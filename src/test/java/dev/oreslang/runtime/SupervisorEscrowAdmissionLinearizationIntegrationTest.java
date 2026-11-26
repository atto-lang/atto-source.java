package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real runtime mailbox/failure-race regression. The separate source-only
 * ledger stress is SupervisorEscrowDataAdmissionRaceStandalone. */
@Timeout(30)
final class SupervisorEscrowAdmissionLinearizationIntegrationTest {
    private static final AtomicReference<CountDownLatch> SPAWN_DONE =
            new AtomicReference<>();
    private static final AtomicReference<Throwable> SPAWN_DENIED =
            new AtomicReference<>();

    @Test
    void manyConcurrentSendsAreExactlyAccountedAcrossAncestorCrash()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("crash".equals(message)) {
                            throw new IllegalStateException("supervisor failure");
                        }
                    });
            ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            recovery.attach(supervisor, null);
            long childId = recovery.attach(child, supervisor);
            supervisor.getReady().get(5, TimeUnit.SECONDS);
            child.getReady().get(5, TimeUnit.SECONDS);
            child.send("pre-race");
            var accepted = new AtomicInteger(1);
            var rejected = new AtomicInteger();
            final int workers = 8;
            final int attemptsPerWorker = 128;
            var start = new CountDownLatch(1);
            ExecutorService senders = Executors.newFixedThreadPool(workers + 1);
            try {
                List<Future<?>> pending = new ArrayList<>();
                for (int w = 0; w < workers; w++) {
                    final int workerId = w;
                    pending.add(senders.submit(() -> {
                        start.await();
                        for (int attempt = 0; attempt < attemptsPerWorker; attempt++) {
                            try {
                                child.send("w" + workerId + "-" + attempt);
                                accepted.incrementAndGet();
                            } catch (IllegalStateException admissionDenied) {
                                // Mailbox FULL, SEALED, and TERMINATED are
                                // distinct expected causes in this stress.
                                if (!(admissionDenied instanceof ActorRuntime.SendError)) {
                                    throw admissionDenied;
                                }
                                rejected.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                pending.add(senders.submit(() -> {
                    start.await();
                    supervisor.send("crash");
                    return null;
                }));
                start.countDown();
                for (Future<?> task : pending) task.get(15, TimeUnit.SECONDS);
            } finally {
                senders.shutdownNow();
            }
            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                    "failed supervisor must retire the affected actor");
            assertEquals(workers * attemptsPerWorker,
                    accepted.get() + rejected.get() - 1,
                    "each attempted concurrent send must settle exactly once");
            var admission = bridge.dataAdmissionStatus(childId);
            assertEquals(accepted.get(), admission.committed(),
                    "every successfully returned mailbox send has one DATA permit");
            assertEquals(0, admission.inFlight(), "no permit may be stranded");
            assertEquals(0, bridge.dataInFlightInSubtree(childId));
            // Check the public runtime send path, not only a ledger helper:
            // the already-retired generation cannot accept another envelope.
            assertThrows(IllegalStateException.class, () -> child.send("post-fence"));
            var afterDenied = bridge.dataAdmissionStatus(childId);
            assertEquals(admission.committed(), afterDenied.committed(),
                    "post-fence runtime send improperly committed a DATA permit");
            assertEquals(0, afterDenied.inFlight());
            assertNull(bridge.beginDataAdmissionFromRuntime(childId, child.generation()),
                    "new DATA admission cannot cross completed supervisor fence");
        }
    }

    @Test
    void preFenceTicketBlocksRestartUntilSettledEvenAfterDoneAndAck()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            // Synthetic release oracle: validates the logical permit gate only.
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> true);
            ActorRuntime.ActorRef<String> failing = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("fail".equals(message)) throw new IllegalStateException("boom");
                    });
            long id = recovery.attach(failing, null);
            var outstanding = bridge.beginDataAdmissionFromRuntime(
                    id, failing.generation());
            assertNotNull(outstanding);
            failing.send("fail");
            assertThrows(ExecutionException.class,
                    () -> failing.done().get(5, TimeUnit.SECONDS));
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(id), () -> { }));
            assertEquals(
                    SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.DATA_ADMISSIONS_IN_FLIGHT,
                    recovery.restartBlocker(id));
            bridge.finishDataAdmissionFromRuntime(outstanding, false);
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.READY,
                    recovery.restartBlocker(id));
        }
    }

    private static final AtomicReference<ActorRuntime.ActorRef<String>> SPAWNED_CHILD =
            new AtomicReference<>();

    @Test
    void nestedSpawnPublishesControlIdentityBeforeActorVisibilityAndRetiresOnCrash()
            throws Exception {
        var signal = new CountDownLatch(1);
        SPAWN_DONE.set(signal);
        SPAWN_DENIED.set(null);
        SPAWNED_CHILD.set(null);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("crash".equals(message)) {
                            throw new IllegalStateException("supervisor failure");
                        }
                        if ("spawn".equals(message)) {
                            try {
                                ActorRuntime.ActorRef<String> child =
                                        turn.runtime().spawnPrivate(
                                                nested -> (item, context) -> { });
                                SPAWNED_CHILD.set(child);
                            } catch (RuntimeException | Error denied) {
                                SPAWN_DENIED.set(denied);
                            } finally {
                                SPAWN_DONE.get().countDown();
                            }
                        }
                    });
            long supervisorId = recovery.attach(supervisor, null);
            supervisor.send("spawn");
            assertTrue(signal.await(5, TimeUnit.SECONDS), "nested spawn did not settle");
            assertNull(SPAWN_DENIED.get(), "coordinator-backed nested spawn must succeed");
            ActorRuntime.ActorRef<String> child = SPAWNED_CHILD.get();
            assertNotNull(child, "spawn returned without child");
            long childId = bridge.nodeId(child);
            assertEquals(supervisorId, bridge.snapshot().get(childId).parentId(),
                    "child must inherit exact CONTROL supervisor generation");
            assertEquals(2, runtime.actorCount(), "one supervisor and one child");
            child.send("ping");
            supervisor.send("crash");
            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                    "CONTROL cancellation must retire the newly published child");
            assertNull(bridge.beginDataAdmissionFromRuntime(childId, child.generation()),
                    "fenced child must reject new DATA");
            assertEquals(0, bridge.dataInFlightInSubtree(supervisorId));
            assertNull(recovery.controlFault(), "CONTROL coordinator must not fault");
        } finally {
            SPAWNED_CHILD.set(null);
            SPAWN_DONE.compareAndSet(signal, null);
            SPAWN_DENIED.set(null);
        }
    }

    @Test
    void failedChildPublicationRollsBackEscrowNodeWithoutOrphanReservation() {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1L, 1L);
        ledger.registerChild(2L, 1L, 2L);
        var applied = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> ledger.registerChildAtomically(3L, 2L, 3L, () -> {
                    applied.incrementAndGet();
                    throw new IllegalStateException("injected publication failure");
                }));
        assertEquals(1, applied.get(), "publisher must have run once");
        assertFalse(ledger.snapshot().containsKey(3L),
                "failed child must not survive as a CONTROL reservation");
        assertEquals(0, ledger.pendingCount(),
                "registration rollback must not forge a failure notice");
        ledger.fail(2L);
        assertThrows(IllegalStateException.class,
                () -> ledger.registerChildAtomically(4L, 2L, 4L,
                        () -> applied.incrementAndGet()));
        assertEquals(1, applied.get(), "fenced parent cannot run publisher");
        assertFalse(ledger.snapshot().containsKey(4L));
    }

    private static final AtomicReference<CountDownLatch> RACE_SPAWNING =
            new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> RACE_RELEASE =
            new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> RACE_FINISHED =
            new AtomicReference<>();
    private static final AtomicReference<ConcurrentLinkedQueue<ActorRuntime.ActorRef<String>>>
            RACE_CHILDREN = new AtomicReference<>();
    private static final AtomicReference<Throwable> RACE_REJECTED =
            new AtomicReference<>();

    @Test
    void cancelRacingSupervisedChildSpawnLeavesNoOrphanActorOrControlReservation()
            throws Exception {
        var spawned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var children = new ConcurrentLinkedQueue<ActorRuntime.ActorRef<String>>();
        RACE_SPAWNING.set(spawned);
        RACE_RELEASE.set(release);
        RACE_FINISHED.set(finished);
        RACE_CHILDREN.set(children);
        RACE_REJECTED.set(null);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        try {
                            for (int i = 0; i < 128; i++) {
                                if (i == 16) {
                                    RACE_SPAWNING.get().countDown();
                                    if (!RACE_RELEASE.get().await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("test cancellation signal timed out");
                                    }
                                }
                                try {
                                    ActorRuntime.ActorRef<String> child =
                                            turn.runtime().spawnPrivate(
                                                    nested -> (item, context) -> { });
                                    RACE_CHILDREN.get().add(child);
                                } catch (RuntimeException denied) {
                                    RACE_REJECTED.set(denied);
                                    break;
                                }
                            }
                        } finally {
                            RACE_FINISHED.get().countDown();
                        }
                    });
            recovery.attach(supervisor, null);
            supervisor.send("spawn");
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertEquals(16, children.size(), "expected the initial published cohort");
            assertTrue(runtime.cancel(supervisor), "cancellation must fence the parent");
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS),
                    "actor turn did not settle after supervisor cancellation");
            assertNotNull(RACE_REJECTED.get(),
                    "late spawn must fail after parent retirement fence");
            assertTrue(supervisor.awaitTermination(5, TimeUnit.SECONDS));
            for (ActorRuntime.ActorRef<String> child : children) {
                assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                        "published child must be physically retired by parent cancellation");
            }
            assertEquals(children.size() + 2, bridge.snapshot().size(),
                    "cancelled spawn must not leave unpublished CONTROL nodes");
            assertEquals(0, bridge.pendingCount(),
                    "structured cancellation must not forge failure CONTROL");
            assertNull(recovery.controlFault(), "CONTROL coordinator must survive race");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.actorCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0, runtime.actorCount(),
                    "cancelled supervision tree must release all actor slots");
        } finally {
            release.countDown();
            RACE_SPAWNING.set(null);
            RACE_RELEASE.set(null);
            RACE_FINISHED.set(null);
            RACE_CHILDREN.set(null);
            RACE_REJECTED.set(null);
        }
    }

    @Test
    void supervisedNestedSpawnFailsClosedUntilChildCanRegisterBeforePublication()
            throws Exception {
        var signal = new CountDownLatch(1);
        SPAWN_DONE.set(signal);
        SPAWN_DENIED.set(null);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        try {
                            turn.runtime().spawnPrivate(
                                    nested -> (item, context) -> { });
                        } catch (IllegalStateException denied) {
                            SPAWN_DENIED.set(denied);
                        } finally {
                            SPAWN_DONE.get().countDown();
                        }
                    });
            bridge.attach(supervisor, null);
            supervisor.send("spawn");
            assertTrue(signal.await(5, TimeUnit.SECONDS));
            Throwable failure = SPAWN_DENIED.get();
            assertNotNull(failure, "unregistered nested child should be rejected");
            assertTrue(failure.getMessage().contains("atomic CONTROL child registration"));
            assertEquals(1, runtime.actorCount(),
                    "nested spawn denial before ActorCell allocation must not leak actor slots");
            assertEquals(0, bridge.pendingCount(),
                    "denial must not falsely report supervisor panic");
            assertTrue(supervisor.isAlive());
        } finally {
            SPAWN_DONE.compareAndSet(signal, null);
            SPAWN_DENIED.set(null);
        }
    }
}
