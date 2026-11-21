package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-free, exact-source CI smoke for the nonparking gate.
 * Runs on a JDK 21+ without Maven/JUnit or native-carrier allocation.
 *
 * THIS IS NOT an ActorRuntime/GraalVM integration test.
 */
public final class ActorGroupTurnGateStandaloneSmoke {
    private ActorGroupTurnGateStandaloneSmoke() { }

    private static void require(boolean ok, String assertion) {
        if (!ok) throw new AssertionError(assertion);
    }

    public static void main(String[] args) throws Exception {
        fifoAndBoundedQueue();
        cancellationRaces();
        dispatcherFailureDoesNotRecurse();
        cancellationObserverFailureStillDrains();
        parallelismOnEightOsThreads();
        System.out.println("PASS ActorGroupTurnGateStandaloneSmoke");
    }

    private static void fifoAndBoundedQueue() {
        try (var gate = new ActorGroupTurnGate(2, 4)) {
            List<Integer> dispatched = new ArrayList<>();
            List<ActorGroupTurnGate.Ticket> tickets = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final int sequence = i;
                tickets.add(gate.submit(ticket -> dispatched.add(sequence),
                        () -> { throw new AssertionError("unexpected cancellation"); }));
            }
            require(dispatched.equals(List.of(0, 1)), "two initial permits");
            require(gate.activeTurns() == 2 && gate.queuedTurns() == 4,
                    "bounded queue");
            try {
                gate.submit(ticket -> { }, () -> { });
                throw new AssertionError("queue overflow admitted");
            } catch (RejectedExecutionException expected) { }
            for (var ticket : tickets) ticket.release();
            require(dispatched.equals(List.of(0, 1, 2, 3, 4, 5)), "FIFO order");
            require(gate.activeTurns() == 0 && gate.queuedTurns() == 0,
                    "permits reclaimed");
        }
    }

    private static void cancellationRaces() throws Exception {
        for (int i = 0; i < 2_000; i++) {
            try (var gate = new ActorGroupTurnGate(1, 1)) {
                var first = gate.submit(ticket -> { }, () -> {
                    throw new AssertionError("active cancelled");
                });
                AtomicInteger started = new AtomicInteger();
                AtomicInteger cancelled = new AtomicInteger();
                AtomicReference<ActorGroupTurnGate.Ticket> promoted =
                        new AtomicReference<>();
                var queued = gate.submit(ticket -> {
                    promoted.set(ticket);
                    started.incrementAndGet();
                }, cancelled::incrementAndGet);
                CountDownLatch begin = new CountDownLatch(1);
                Thread canceller = new Thread(() -> {
                    try {
                        begin.await();
                        queued.cancelWaiting();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                canceller.start();
                begin.countDown();
                first.release();
                canceller.join(3_000);
                require(!canceller.isAlive(), "cancel joined");
                require(started.get() + cancelled.get() == 1, "single winner");
                if (promoted.get() != null) promoted.get().release();
                require(gate.activeTurns() == 0 && gate.queuedTurns() == 0,
                        "race did not strand a permit");
            }
        }
        System.out.println("PASS 2,000 simultaneous cancel/release races");
    }

    private static void dispatcherFailureDoesNotRecurse() {
        final int pending = 20_000;
        try (var gate = new ActorGroupTurnGate(1, pending + 1)) {
            var first = gate.submit(ticket -> { }, () -> {
                throw new AssertionError("active cancelled");
            });
            gate.submit(ticket -> {
                throw new RejectedExecutionException("injected downstream failure");
            }, () -> {
                throw new AssertionError("granted turn cancelled as waiter");
            });
            AtomicInteger cancelled = new AtomicInteger();
            for (int i = 0; i < pending; i++) {
                gate.submit(ticket -> {
                    throw new AssertionError("unfair or unsafe promotion after failure");
                }, cancelled::incrementAndGet);
            }
            try {
                first.release();
                throw new AssertionError("rejection did not propagate");
            } catch (RejectedExecutionException expected) { }
            require(gate.isClosed(), "fatal downstream rejection closes gate");
            require(cancelled.get() == pending, "all queued tickets cancelled");
            require(gate.activeTurns() == 0 && gate.queuedTurns() == 0,
                    "fatal rejection released reservations");
        }
        System.out.println("PASS 20,000 waiting turns cancelled after executor rejection");
    }

    private static void cancellationObserverFailureStillDrains() {
        try (var gate = new ActorGroupTurnGate(1, 2)) {
            var first = gate.submit(ticket -> { }, () -> { });
            gate.submit(ticket -> { }, () -> {
                throw new IllegalStateException("injected observer fault");
            });
            AtomicInteger cancelled = new AtomicInteger();
            gate.submit(ticket -> { }, cancelled::incrementAndGet);
            try {
                gate.close();
                throw new AssertionError("observer fault not reported");
            } catch (IllegalStateException expected) { }
            require(cancelled.get() == 1, "remaining cancelled observer notified");
            require(first.release(), "active physical turn release");
            require(gate.activeTurns() == 0 && gate.queuedTurns() == 0,
                    "failed observer did not leak a ticket");
        }
    }

    private static void parallelismOnEightOsThreads() throws Exception {
        final int total = 12_000;
        try (var pool = Executors.newFixedThreadPool(8);
             var gate = new ActorGroupTurnGate(3, total)) {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(total);
            for (int i = 0; i < total; i++) {
                gate.submit(ticket -> pool.execute(() -> {
                    int active = running.incrementAndGet();
                    peak.accumulateAndGet(active, Math::max);
                    try {
                        if (active > 3) {
                            error.compareAndSet(null,
                                    new AssertionError("exceeded maxParallelTurns"));
                        }
                        Thread.onSpinWait();
                    } finally {
                        running.decrementAndGet();
                        try {
                            ticket.release();
                        } finally {
                            completed.incrementAndGet();
                            finished.countDown();
                        }
                    }
                }), () -> error.compareAndSet(null,
                        new AssertionError("unexpected wait cancellation")));
            }
            require(finished.await(25, TimeUnit.SECONDS), "parallel test timed out");
            require(error.get() == null, "concurrent execution failure: " + error.get());
            require(completed.get() == total, "all turns completed");
            require(peak.get() <= 3 && peak.get() >= 1,
                    "peak remained within the cap");
            require(gate.activeTurns() == 0 && gate.queuedTurns() == 0,
                    "no permit leak");
            System.out.println("PASS " + completed.get()
                    + " asynchronous tasks on eight OS threads; peak=" + peak.get());
        }
    }
}
