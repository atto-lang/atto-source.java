package dev.oreslang.runtime;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only linearization tests for the exact production pooled Worker gate.
 * These do not substitute for the physical TurnExecutor and guest HTTP tests.
 */
@Timeout(15)
final class HttpPooledWorkerAdmissionFenceTest {
    @Test
    void noReservationAfterSealAndNoPrematureQuiescence() {
        var gate = new HttpPooledWorkerAdmissionFence();
        assertFalse(gate.quiescent(), "unsealed idle is not a shutdown receipt");
        assertTrue(gate.tryReserve());
        assertEquals(1, gate.outstanding());
        gate.seal();
        assertTrue(gate.sealed());
        assertFalse(gate.tryReserve());
        assertFalse(gate.quiescent(), "pre-seal handoff still owns the lease");
        gate.release();
        assertTrue(gate.quiescent());
        assertEquals(0, gate.outstanding());
        assertThrows(IllegalStateException.class, gate::release,
                "repeated completion cannot underflow a sealed worker");
        gate.seal();
        assertTrue(gate.quiescent(), "sealing twice cannot reopen admission");
    }

    @RepeatedTest(32)
    void concurrentSealJoinsEveryPreviouslyAdmittedHandoff() throws Exception {
        var gate = new HttpPooledWorkerAdmissionFence();
        final int admittedBeforeSeal = 24;
        for (int i=0; i<admittedBeforeSeal; i++) assertTrue(gate.tryReserve());
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> sealer = executor.submit(() -> {
                start.countDown();
                gate.seal();
                done.countDown();
            });
            assertTrue(start.await(5, TimeUnit.SECONDS));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertFalse(gate.tryReserve());
            assertFalse(gate.quiescent());
            assertEquals(admittedBeforeSeal, gate.outstanding());
            for (int i=0; i<admittedBeforeSeal-1; i++) {
                gate.release();
                assertFalse(gate.quiescent());
            }
            gate.release();
            sealer.get(5, TimeUnit.SECONDS);
            assertTrue(gate.quiescent());
        }
    }

    @RepeatedTest(24)
    void parallelDispatchRaceCannotAdmitAfterShutdownSeal() throws Exception {
        var gate = new HttpPooledWorkerAdmissionFence();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(64);
        var finish = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var denied = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var racers = new ArrayList<Future<?>>();
            for (int i=0; i<64; i++) {
                racers.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("dispatch start gate expired");
                    }
                    if (gate.tryReserve()) {
                        admitted.incrementAndGet();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("worker handoff expired");
                            }
                        } finally {
                            gate.release();
                        }
                    } else {
                        denied.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            gate.seal();
            assertFalse(gate.tryReserve(), "a sealed generation must reject new dispatches");
            assertFalse(gate.quiescent() && gate.outstanding()!=0,
                    "quiescence must not ignore in-flight reservations");
            finish.countDown();
            for (Future<?> future : racers) future.get(5, TimeUnit.SECONDS);
            assertEquals(64, admitted.get()+denied.get());
            assertEquals(0, gate.outstanding());
            assertTrue(gate.quiescent());
        }
    }

    @Test
    void anotherListenerMustReplaceTheSealedWorkerGeneration() {
        var oldWorker = new HttpPooledWorkerAdmissionFence();
        assertTrue(oldWorker.tryReserve(), "listener A admitted earlier work");
        oldWorker.seal();
        assertFalse(oldWorker.tryReserve(),
                "listener B must not reuse the worker that A is draining");
        var replacement = new HttpPooledWorkerAdmissionFence();
        assertTrue(replacement.tryReserve(), "listener B can use a distinct generation");
        assertFalse(oldWorker.quiescent());
        oldWorker.release();
        assertTrue(oldWorker.quiescent());
        assertFalse(replacement.quiescent(), "replacement remains open for listener B");
        replacement.release();
        assertFalse(replacement.quiescent(), "an unsealed generation is not a drain receipt");
    }
}
