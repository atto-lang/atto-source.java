package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** HOST-ONLY tests of the real ActorRuntime supervisor storage authority. */
@Timeout(15)
final class SupervisorStorageEpochAuthorityTest {
    private static volatile CountDownLatch enteredActor;
    private static volatile CountDownLatch leaveActor;
    private static final AtomicInteger actualActorMessages = new AtomicInteger();

    /** Factory intentionally capture-free: actor mail still executes in ActorRuntime. */
    private static ActorRuntime.BehaviorFactory<Integer> actorFactory() {
        return context -> (message, actorContext) -> {
            actualActorMessages.incrementAndGet();
            CountDownLatch entered = enteredActor;
            if (entered != null) entered.countDown();
            CountDownLatch leave = leaveActor;
            if (leave != null) {
                try {
                    if (!leave.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("actor turn test deadline");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
    }

    private static final class Store
            implements SupervisorStorageEpochAuthority.DurableGenerationStore {
        private long epoch;
        private boolean fail;
        private boolean wrongGeneration;
        private int fsyncReservations;

        @Override public synchronized long currentGeneration() { return epoch; }
        @Override public synchronized long reserveAndSyncNext(long expected, String token) {
            if (token == null || !token.matches("[0-9a-f]{32}"))
                throw new SecurityException("runtime-issued activation token required");
            if (fail) throw new IllegalStateException("injected ambiguous fsync");
            if (expected != epoch) throw new SecurityException("stale durable epoch compare");
            if (wrongGeneration) return epoch;
            fsyncReservations++;
            return ++epoch;
        }
    }

    @Test
    void actualActorCarrierPinsGenerationThroughPhysicalMailTurn() throws Exception {
        enteredActor = new CountDownLatch(1);
        leaveActor = new CountDownLatch(1);
        actualActorMessages.set(0);
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            var group = runtime.createActorGroup(supervisor);
            var gate = supervisor.registerStoragePartition("actual-actor", new Store());
            var activation = gate.activate();
            var actor = supervisor.startStorageChildHost(group,
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    actorFactory(), gate, activation);
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(41);
            try {
                assertTrue(enteredActor.await(5, TimeUnit.SECONDS),
                        "actual ActorCell must execute mailbox work");
                assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                        gate::activate, "generation cannot advance across a running actor turn");
                assertEquals(1, actualActorMessages.get());
            } finally {
                leaveActor.countDown();
            }
            // No new actor work is allowed after activation revocation:
            // actor is retired by the runtime on its next scheduler admission.
            // Latch release only allows the actor callback to finish; the
            // scheduler still has to leave the physical carrier and retire
            // its generation lease. Do not race the activation with that exit.
            long drainedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!gate.physicallyQuiescent() && System.nanoTime() < drainedBy) {
                Thread.sleep(2);
            }
            assertTrue(gate.physicallyQuiescent(),
                    "physical ActorCell turn must drain before next generation");
            var next = gate.activate();
            assertEquals(2L, next.generation());
            actor.send(42);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "stale actor mailbox/continuation turn must fail closed");
            assertEquals(1, actualActorMessages.get(),
                    "generation 41 must never execute a post-takeover message");
        } finally {
            enteredActor = null;
            leaveActor = null;
        }
    }

    @Test
    void foreignSupervisorGroupCannotBindStorageActor() {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var wrong = runtime.createSupervisor();
            var group = runtime.createActorGroup(wrong);
            var gate = owner.registerStoragePartition("private", new Store());
            var activation = gate.activate();
            assertThrows(SecurityException.class, () ->
                    owner.startStorageChildHost(group, ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(), actorFactory(), gate, activation));
            assertEquals(0, group.workerCount());
        }
    }

    @Test void supervisorExclusivelyBindsOnePartitionAndRejectsForeignRuntime() {
        try (var runtime = new ActorRuntime(); var foreign = new ActorRuntime()) {
            var parent = runtime.createSupervisor();
            var competing = runtime.createSupervisor();
            var store = new Store();
            var gate = parent.registerStoragePartition("tenant/kafka/events/0", store);
            assertEquals(parent.id(), gate.supervisorId());
            assertEquals("tenant/kafka/events/0", gate.partition());
            assertThrows(IllegalStateException.class, () ->
                    competing.registerStoragePartition("tenant/kafka/events/0", new Store()));
            assertThrows(IllegalStateException.class, () ->
                    parent.registerStoragePartition("tenant/kafka/events/0", new Store()));
            assertThrows(IllegalArgumentException.class, () ->
                    parent.registerStoragePartition("../invalid", new Store()));
            assertThrows(IllegalArgumentException.class, () ->
                    parent.registerStoragePartition("", new Store()));
            var otherGate = foreign.createSupervisor()
                    .registerStoragePartition("tenant/kafka/events/0", new Store());
            assertNotEquals(gate.supervisorId(), otherGate.supervisorId());
        }
    }

    @Test void generationAndOneShotCommitFencingAcrossActorReplacement() {
        var store = new Store();
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor().registerStoragePartition("events", store);
            var a = gate.activate();
            assertEquals(1, a.generation());
            assertTrue(a.authority().matches("[0-9a-f]{32}:1"));
            var aIo = gate.beginIo(a);
            assertTrue(gate.permits(aIo));

            // Takeover revokes A immediately, then refuses new admission
            // until A's physical fsync loan is returned.
            assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                    gate::activate);
            assertFalse(gate.permits(aIo));
            aIo.close();
            assertTrue(gate.physicallyQuiescent());
            var b = gate.activate();
            assertEquals(2, b.generation());
            assertEquals(2, store.fsyncReservations);
            assertFalse(gate.permits(aIo));
            AtomicInteger enqueued = new AtomicInteger();
            assertFalse(gate.enqueueCommitIfCurrent(aIo, enqueued::incrementAndGet));
            assertEquals(0, enqueued.get());
            assertThrows(SecurityException.class, () -> gate.beginIo(a));

            var bIo = gate.beginIo(b);
            assertTrue(gate.enqueueCommitIfCurrent(bIo, enqueued::incrementAndGet));
            assertFalse(gate.enqueueCommitIfCurrent(bIo, enqueued::incrementAndGet));
            assertFalse(gate.permits(bIo));
            assertEquals(1, enqueued.get());
            bIo.close();
            gate.cancel(b);
            assertThrows(SecurityException.class, () -> gate.beginIo(b));

            var c = gate.activate();
            assertEquals(3, c.generation());
            assertThrows(SecurityException.class, () -> gate.cancel(b));
            assertTrue(gate.beginIo(c) != null);
        }
    }

    @Test void delayedIoCompletionCannotRaceWithTakeoverCommitLinearization() throws Exception {
        var store = new Store();
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor().registerStoragePartition("wal", store);
            var a = gate.activate();
            var pending = gate.beginIo(a);
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger enqueues = new AtomicInteger();
            Thread oldCompletion = new Thread(() -> {
                try {
                    ready.countDown();
                    finish.await();
                    // Runs after B activation. No old-generation COMMIT.
                    if (gate.enqueueCommitIfCurrent(pending, enqueues::incrementAndGet))
                        throw new AssertionError("stale completion accepted");
                } catch (Throwable e) { failure.set(e); }
            }, "delayed-stale-fsync");
            oldCompletion.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            pending.close(); // fsync completed; callback remains queued
            var b = gate.activate();
            assertEquals(2, b.generation());
            finish.countDown();
            oldCompletion.join(5000);
            assertFalse(oldCompletion.isAlive());
            assertNull(failure.get());
            assertEquals(0, enqueues.get());
            var current = gate.beginIo(b);
            assertTrue(gate.enqueueCommitIfCurrent(current, enqueues::incrementAndGet));
            current.close();
            assertEquals(1, enqueues.get());
        }
    }

    @Test void queuedGeneration41CommitIsRecheckedAfterGeneration42Takeover() {
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor().registerStoragePartition("queue", new Store());
            var first = gate.activate();
            var io = gate.beginIo(first);
            AtomicReference<Runnable> queued = new AtomicReference<>();
            assertTrue(gate.enqueueCommitIfCurrent(io, () ->
                    queued.set(() -> fail("stale generation 41 guest mutation"))));
            io.close(); // physical fsync has finished, but the task is still queued
            var second = gate.activate();
            assertEquals(2L, second.generation());
            assertFalse(gate.dispatchCommitIfCurrent(io, queued.get()));
            assertFalse(gate.dispatchCommitIfCurrent(io, () -> fail("duplicate")));
            assertTrue(gate.physicallyQuiescent());
            var current = gate.beginIo(second);
            AtomicInteger executions = new AtomicInteger();
            assertTrue(gate.enqueueCommitIfCurrent(current, () -> { }));
            current.close();
            assertTrue(gate.dispatchCommitIfCurrent(current, executions::incrementAndGet));
            assertEquals(1, executions.get());
            assertFalse(gate.dispatchCommitIfCurrent(current, executions::incrementAndGet));
        }
    }

    @Test void physicalCommitExecutionBlocksNextGenerationUntilQuiescence() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor().registerStoragePartition("physical", new Store());
            var first = gate.activate();
            var io = gate.beginIo(first);
            assertTrue(gate.enqueueCommitIfCurrent(io, () -> { }));
            io.close(); // disk fsync is done; guest dispatch is not
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger executions = new AtomicInteger();
            Thread worker = new Thread(() -> {
                try {
                    assertTrue(gate.dispatchCommitIfCurrent(io, () -> {
                        entered.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("blocked test callback");
                            executions.incrementAndGet();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    }));
                } catch (Throwable error) { failure.set(error); }
            }, "physical-generation41-commit");
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                        gate::activate, "generation 42 cannot admit while 41 is physically dispatching");
                assertFalse(gate.physicallyQuiescent());
            } finally {
                finish.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(1, executions.get(), "generation 41 was authorized BEFORE takeover");
            assertTrue(gate.physicallyQuiescent());
            assertEquals(2L, gate.activate().generation(),
                    "generation 42 only admitted after 41 dispatch completed");
            assertFalse(gate.dispatchCommitIfCurrent(io, () -> fail("late stale dispatch")));
        }
    }

    @Test void cancellationRequiresOutstandingFsyncToDrainBeforeReplacement() {
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor().registerStoragePartition("cancel", new Store());
            var first = gate.activate();
            var io = gate.beginIo(first);
            gate.cancel(first);
            assertFalse(gate.permits(io));
            assertThrows(SupervisorStorageEpochAuthority.QuiescencePendingException.class,
                    gate::activate, "cancel must not silently turn in-flight fsync into quiescence");
            io.close();
            io.close(); // duplicate completion must not underflow
            assertTrue(gate.physicallyQuiescent());
            assertEquals(2L, gate.activate().generation());
            assertFalse(gate.enqueueCommitIfCurrent(io, () -> fail("cancelled")));
        }
    }

    @Test void failedOrWrongDurableReservationPoisonsBeforeReadiness() {
        for (boolean wrongEpoch : new boolean[]{false, true}) {
            var store = new Store();
            try (var runtime = new ActorRuntime()) {
                var gate = runtime.createSupervisor().registerStoragePartition("wal", store);
                var a = gate.activate();
                var op = gate.beginIo(a);
                if (wrongEpoch) store.wrongGeneration = true;
                else store.fail = true;
                op.close();
                assertThrows(RuntimeException.class, gate::activate);
                assertFalse(gate.permits(op), "ambiguous fsync must revoke old actor");
                assertFalse(gate.enqueueCommitIfCurrent(op, () -> fail("old actor resumed")));
                assertThrows(IllegalStateException.class, gate::activate);
            }
        }
    }

    @Test void throwingCommitPublishIsAmbiguousAndNeverRetried() {
        try (var runtime = new ActorRuntime()) {
            var gate = runtime.createSupervisor()
                    .registerStoragePartition("wal", new Store());
            var a = gate.activate();
            var op = gate.beginIo(a);
            assertThrows(IllegalArgumentException.class, () ->
                    gate.enqueueCommitIfCurrent(op,
                            () -> { throw new IllegalArgumentException("callback ambiguous"); }));
            assertFalse(gate.permits(op));
            op.close();
            assertThrows(IllegalStateException.class, gate::activate);
            assertFalse(gate.enqueueCommitIfCurrent(op, () -> fail("retry")));
        }
    }

    @Test void crossPartitionIoAndRuntimeShutdownCannotAuthorizeOldWork() {
        var runtime = new ActorRuntime();
        var s = runtime.createSupervisor();
        var alpha = s.registerStoragePartition("alpha", new Store());
        var beta = s.registerStoragePartition("beta", new Store());
        var a = alpha.activate();
        var b = beta.activate();
        var io = alpha.beginIo(a);
        assertThrows(SecurityException.class, () -> beta.beginIo(a));
        assertFalse(beta.enqueueCommitIfCurrent(io, () -> fail("foreign")));
        runtime.close();
        assertFalse(alpha.permits(io));
        assertFalse(alpha.physicallyQuiescent(), "unreleased I/O must stay accounted");
        io.close();
        assertTrue(alpha.physicallyQuiescent());
        assertThrows(IllegalStateException.class, alpha::activate);
        assertThrows(IllegalStateException.class, beta::activate);
        assertTrue(b.generation() >= 1);
    }
}
