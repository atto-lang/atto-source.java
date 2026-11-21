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

            // A fsync may finish after the second activation publishes. A's
            // ticket can never enqueue a stale actor COMMIT.
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
            var b = gate.activate();
            assertEquals(2, b.generation());
            finish.countDown();
            oldCompletion.join(5000);
            assertFalse(oldCompletion.isAlive());
            assertNull(failure.get());
            assertEquals(0, enqueues.get());
            var current = gate.beginIo(b);
            assertTrue(gate.enqueueCommitIfCurrent(current, enqueues::incrementAndGet));
            assertEquals(1, enqueues.get());
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
        assertThrows(IllegalStateException.class, alpha::activate);
        assertThrows(IllegalStateException.class, beta::activate);
        assertTrue(b.generation() >= 1);
    }
}
