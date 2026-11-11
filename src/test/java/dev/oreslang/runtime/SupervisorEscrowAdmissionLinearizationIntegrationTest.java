package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
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
            assertNull(bridge.beginDataAdmissionFromRuntime(childId, child.generation()),
                    "new DATA admission cannot cross completed supervisor fence");
            // Check the public runtime send path, not only a ledger helper:
            // the already-retired generation cannot accept another envelope.
            assertThrows(IllegalStateException.class, () -> child.send("post-fence"));
            var afterDenied = bridge.dataAdmissionStatus(childId);
            assertEquals(admission.committed(), afterDenied.committed(),
                    "post-fence runtime send improperly committed a DATA permit");
            assertEquals(0, afterDenied.inFlight());
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
