package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ControlObservationTurnGateTest {
    @Test
    void failedDispatchNeverAdmitsALatePhysicalTurn() {
        var gate = new ControlObservationTurnGate();
        assertTrue(gate.abandonIfQueued());
        assertFalse(gate.tryStart());
        assertFalse(gate.abandonIfQueued());
    }

    @Test
    void runningTurnRetainsThePhysicalAccountingLease() {
        var gate = new ControlObservationTurnGate();
        assertTrue(gate.tryStart());
        assertFalse(gate.abandonIfQueued());
        assertFalse(gate.tryStart());
    }

    @Test
    void concurrentStartAndFailedDispatchHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 200; round++) {
            var gate = new ControlObservationTurnGate();
            var ready = new CountDownLatch(2);
            var race = new CountDownLatch(1);
            var started = new AtomicBoolean();
            var abandoned = new AtomicBoolean();
            Thread worker = Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    race.await();
                    started.set(gate.tryStart());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread failure = Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    race.await();
                    abandoned.set(gate.abandonIfQueued());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(ready.await(2, TimeUnit.SECONDS), "test workers never started");
            race.countDown();
            worker.join(2000);
            failure.join(2000);
            assertFalse(worker.isAlive(), "worker was never released");
            assertFalse(failure.isAlive(), "failure observer never completed");
            assertTrue(started.get() != abandoned.get(),
                    "neither/both dispatch and physical turn acquired ownership");
            assertFalse(gate.tryStart());
            assertFalse(gate.abandonIfQueued());
        }
    }
}
