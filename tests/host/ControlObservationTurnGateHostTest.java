package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Source-free host-only race probe. Does not execute Atto guest code. */
public final class ControlObservationTurnGateHostTest {
    private static void check(boolean okay, String detail) {
        if (!okay) throw new AssertionError(detail);
    }

    public static void main(String[] args) throws Exception {
        ControlObservationTurnGate abandoned = new ControlObservationTurnGate();
        check(abandoned.abandonIfQueued(), "queued failure must abandon");
        check(!abandoned.tryStart(), "late executor ran after release");
        check(!abandoned.abandonIfQueued(), "abandon repeated");

        ControlObservationTurnGate running = new ControlObservationTurnGate();
        check(running.tryStart(), "first runner not admitted");
        check(!running.abandonIfQueued(), "running turn incorrectly released");
        check(!running.tryStart(), "duplicate execution admitted");

        for (int i = 0; i < 2000; i++) {
            ControlObservationTurnGate gate = new ControlObservationTurnGate();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean started = new AtomicBoolean();
            AtomicBoolean dropped = new AtomicBoolean();
            AtomicInteger errors = new AtomicInteger();
            Thread worker = new Thread(() -> {
                ready.countDown();
                try { go.await(); started.set(gate.tryStart()); }
                catch (InterruptedException interrupted) { errors.incrementAndGet(); }
            });
            Thread failed = new Thread(() -> {
                ready.countDown();
                try { go.await(); dropped.set(gate.abandonIfQueued()); }
                catch (InterruptedException interrupted) { errors.incrementAndGet(); }
            });
            worker.start(); failed.start();
            check(ready.await(10, TimeUnit.SECONDS), "test workers never started");
            go.countDown();
            worker.join(10000); failed.join(10000);
            check(!worker.isAlive() && !failed.isAlive(), "deadlock in start/abandon race");
            check(errors.get() == 0, "interrupted test workers");
            check(started.get() != dropped.get(), "neither/both physical owners acquired");
            check(!gate.tryStart() && !gate.abandonIfQueued(), "terminal state reopened");
        }
        System.out.println("ATTO_CONTROL_OBSERVATION_TURN_GATE_HOST_PASS rounds=2000");
    }
}
