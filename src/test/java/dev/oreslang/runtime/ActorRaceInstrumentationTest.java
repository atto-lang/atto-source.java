package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Host-only foundational conformance. Guest-language compiler access probes,
 * backend-owned reports, native LLVM parity and deterministic scheduler
 * integration remain separate release gates.
 */
final class ActorRaceInstrumentationTest {
    @Test
    void knownUnsynchronizedReadWriteRaceIsDetected() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object memory = new Object();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                probe.read(memory, "field");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                probe.write(memory, "field");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        reader.join();
        writer.join();
        if (failure.get() != null) fail(failure.get());
        assertEquals(2, probe.snapshot().accesses());
        assertTrue(probe.snapshot().memoryRaces() >= 1);
    }

    @Test
    void actorSerialAdmissionCreatesHappensBeforeAcrossCarrierMigration() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object actor = new Object();
        Object actorState = new Object();
        CountDownLatch released = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> {
            probe.enterActor(actor);
            try {
                probe.write(actorState, "balance");
            } finally {
                probe.exitActor(actor);
                released.countDown();
            }
        });
        Thread second = Thread.ofPlatform().start(() -> {
            try {
                if (!released.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("actor turn did not release");
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            probe.enterActor(actor);
            try {
                probe.read(actorState, "balance");
            } finally {
                probe.exitActor(actor);
            }
        });
        first.join(5000);
        second.join(5000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(0, probe.snapshot().memoryRaces());
        assertEquals(0, probe.snapshot().actorOverlapViolations());
        assertEquals(4, probe.snapshot().actorChecks());
    }

    @Test
    void duplicatePhysicalAdmissionFailsClosedAndIsRecorded() {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object actor = new Object();
        probe.enterActor(actor);
        try {
            assertThrows(IllegalStateException.class, () -> probe.enterActor(actor));
            assertEquals(1, probe.snapshot().actorOverlapViolations());
        } finally {
            probe.exitActor(actor);
        }
    }

    @Test
    void unrelatedActorTurnDoesNotSynchronizeAnotherActorMemory() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object sharedMemory = new Object();
        Object firstActor = new Object();
        Object secondActor = new Object();
        CountDownLatch start = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            } catch (InterruptedException error) { throw new AssertionError(error); }
            probe.enterActor(firstActor);
            try {
                probe.write(sharedMemory, "counter");
            } finally {
                probe.exitActor(firstActor);
            }
        });
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                if (!start.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("start timeout");
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            probe.enterActor(secondActor);
            try {
                probe.read(sharedMemory, "counter");
            } finally {
                probe.exitActor(secondActor);
            }
        });
        start.countDown();
        writer.join(5000);
        reader.join(5000);
        assertEquals(1, probe.snapshot().memoryRaces(),
                "distinct actor turn leases do not synchronize shared memory");
    }

    @Test
    void explicitSynchronizationEdgeAvoidsFalseRaceReport() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object owner = new Object();
        Object mutex = new Object();
        CountDownLatch released = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() -> {
            probe.write(owner, "value");
            probe.release(mutex);
            released.countDown();
        });
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                if (!released.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            probe.acquire(mutex);
            probe.read(owner, "value");
        });
        writer.join(5000);
        reader.join(5000);
        assertEquals(0, probe.snapshot().memoryRaces());
    }

    @Test
    void readReadWithoutWriteIsNotARace() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object owner = new Object();
        Thread a = Thread.ofPlatform().start(() -> probe.read(owner, "value"));
        Thread b = Thread.ofPlatform().start(() -> probe.read(owner, "value"));
        a.join(5000);
        b.join(5000);
        assertEquals(0, probe.snapshot().memoryRaces());
    }

    @Test
    void completedActorAndGuestMemoryCanBeRetired() {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object actor = new Object();
        Object memory = new Object();
        probe.enterActor(actor);
        assertThrows(IllegalStateException.class, () -> probe.retireActor(actor));
        probe.write(memory, "counter");
        probe.exitActor(actor);
        probe.retireActor(actor);
        probe.retireMemoryDomain(memory);
        // A fresh incarnation must reestablish its own clock and physical lease.
        Object newIncarnation = new Object();
        probe.enterActor(newIncarnation);
        probe.exitActor(newIncarnation);
        probe.retireActor(newIncarnation);
        assertEquals(1, probe.snapshot().actorOverlapViolations());
    }

    @Test
    void rejectedStaleOptimisticCommitIsNotAnUnprotectedWrite() {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        probe.observeCheckedCommit(false);  // Actual CAS succeeded.
        probe.observeCheckedCommit(true);   // Stale version was safely rejected.
        assertEquals(2, probe.snapshot().checkedCommitAttempts());
        assertEquals(1, probe.snapshot().rejectedStaleCommits());
        assertEquals(0, probe.snapshot().memoryRaces());
    }

    @Test
    void exitByNonownerMustNotReleaseLease() throws Exception {
        ActorRaceInstrumentation probe = ActorRaceInstrumentation.forTesting();
        Object actor = new Object();
        probe.enterActor(actor);
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        Thread imposter = Thread.ofPlatform().start(() -> {
            try {
                probe.exitActor(actor);
            } catch (Throwable error) {
                rejection.set(error);
            }
        });
        imposter.join(5000);
        assertInstanceOf(IllegalStateException.class, rejection.get());
        probe.exitActor(actor);
        assertEquals(1, probe.snapshot().actorOverlapViolations());
    }
}
