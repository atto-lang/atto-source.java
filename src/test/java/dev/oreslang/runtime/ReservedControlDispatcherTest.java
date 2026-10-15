package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Deterministic admission contract: a host executor records commands without
 * running them, so the test can saturate normal permits without timing races.
 */
final class ReservedControlDispatcherTest {

    @Test
    void laneControlHasSeparateBudgetAndPreservesLaneIdentity() {
        RecordingExecutor worker = new RecordingExecutor();
        ActorAffinityDispatcher lanes = new ActorAffinityDispatcher(
                1, 1, 1, new int[0], ignored -> worker);
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger control = new AtomicInteger();
        try {
            lanes.executeOnLane(0, ordinary::incrementAndGet);
            assertThrows(RejectedExecutionException.class,
                    () -> lanes.executeOnLane(0, ordinary::incrementAndGet));
            lanes.executeControlOnLane(0, control::incrementAndGet);
            assertThrows(RejectedExecutionException.class,
                    () -> lanes.executeControlOnLane(0, control::incrementAndGet));
            assertEquals(2, worker.pending());
            worker.runNext();
            assertEquals(1, ordinary.get());
            lanes.executeOnLane(0, ordinary::incrementAndGet);
            worker.runNext();
            worker.runNext();
            assertEquals(2, ordinary.get());
            assertEquals(1, control.get());
            // The separate CONTROL permit has been returned after execution.
            lanes.executeControlOnLane(0, control::incrementAndGet);
            worker.runNext();
            assertEquals(2, control.get());
        } finally {
            lanes.shutdownNow();
        }
    }

    @Test
    void nonAffinityControlHasSeparateBudgetAndShutdownIsFailClosed() {
        RecordingExecutor worker = new RecordingExecutor();
        ReservedActorDispatcher dispatcher = new ReservedActorDispatcher(worker, 1, 1);
        AtomicInteger delivered = new AtomicInteger();
        dispatcher.execute(delivered::incrementAndGet);
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.execute(delivered::incrementAndGet));
        dispatcher.executeControl(delivered::incrementAndGet);
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.executeControl(delivered::incrementAndGet));
        assertEquals(2, worker.pending());
        assertEquals(2, dispatcher.shutdownNow().size());
        assertEquals(0, delivered.get());
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.executeControl(delivered::incrementAndGet));
    }

    @Test
    void rejectedPhysicalSubmissionReleasesBothAdmissionPermits() {
        RecordingExecutor worker = new RecordingExecutor();
        ReservedActorDispatcher dispatcher = new ReservedActorDispatcher(worker, 1, 1);
        worker.reject(true);
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.execute(() -> {}));
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.executeControl(() -> {}));
        worker.reject(false);
        dispatcher.execute(() -> {});
        dispatcher.executeControl(() -> {});
        assertEquals(2, worker.pending());
        dispatcher.shutdownNow();
    }

    @Test
    void throwingControlTurnReturnsAdmissionWithoutLeakingIntoOrdinaryBudget() {
        RecordingExecutor worker = new RecordingExecutor();
        ReservedActorDispatcher dispatcher = new ReservedActorDispatcher(worker, 1, 1);
        AtomicInteger ordinary = new AtomicInteger();
        try {
            dispatcher.executeControl(() -> { throw new IllegalStateException("control failure"); });
            assertThrows(RejectedExecutionException.class,
                    () -> dispatcher.executeControl(() -> {}));
            assertThrows(IllegalStateException.class, worker::runNext);
            dispatcher.executeControl(() -> {});
            dispatcher.execute(ordinary::incrementAndGet);
            worker.runNext();
            worker.runNext();
            assertEquals(1, ordinary.get());
            assertEquals(0, worker.pending());
        } finally {
            dispatcher.shutdownNow();
        }
    }

    @Test
    void shutdownReturnsUnexecutedCommandsWithoutRunningTheirEffects() {
        RecordingExecutor worker = new RecordingExecutor();
        ReservedActorDispatcher dispatcher = new ReservedActorDispatcher(worker, 1, 1);
        AtomicInteger calls = new AtomicInteger();
        Runnable ordinary = calls::incrementAndGet;
        Runnable control = calls::incrementAndGet;
        dispatcher.execute(ordinary);
        dispatcher.executeControl(control);
        List<Runnable> abandoned = dispatcher.shutdownNow();
        assertEquals(2, abandoned.size());
        assertSame(ordinary, abandoned.get(0));
        assertSame(control, abandoned.get(1));
        assertEquals(0, calls.get());
        assertEquals(0, worker.pending());
        assertThrows(RejectedExecutionException.class, () -> dispatcher.execute(() -> {}));
        assertThrows(RejectedExecutionException.class,
                () -> dispatcher.executeControl(() -> {}));
    }

    @Test
    void affinityShutdownToleratesForeignHostPendingRunnable() {
        RecordingExecutor worker = new RecordingExecutor();
        ActorAffinityDispatcher lanes = new ActorAffinityDispatcher(
                1, 1, 1, new int[0], ignored -> worker);
        AtomicInteger effects = new AtomicInteger();
        Runnable foreign = effects::incrementAndGet;
        worker.enqueueForeign(foreign);
        lanes.executeOnLane(0, effects::incrementAndGet);
        List<Runnable> abandoned = lanes.shutdownNow();
        assertEquals(2, abandoned.size());
        assertSame(foreign, abandoned.get(0));
        assertEquals(0, effects.get());
        assertThrows(RejectedExecutionException.class,
                () -> lanes.executeControlOnLane(0, effects::incrementAndGet));
    }

    /**
     * Minimal deterministic executor. Never creates threads; submissions are
     * invoked explicitly, and shutdown returns queued unexecuted runnables.
     */
    private static final class RecordingExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean stopped;
        private boolean rejected;

        synchronized void reject(boolean value) { rejected = value; }
        synchronized void enqueueForeign(Runnable runnable) { pending.addLast(runnable); }
        synchronized int pending() { return pending.size(); }

        synchronized void runNext() {
            Runnable next = pending.removeFirst();
            next.run();
        }

        @Override public synchronized void execute(Runnable command) {
            if (stopped || rejected) {
                throw new RejectedExecutionException("injected rejection");
            }
            pending.addLast(command);
        }

        @Override public void shutdown() { shutdownNow(); }

        @Override public synchronized List<Runnable> shutdownNow() {
            stopped = true;
            List<Runnable> result = new ArrayList<>(pending);
            pending.clear();
            return result;
        }

        @Override public synchronized boolean isShutdown() { return stopped; }
        @Override public synchronized boolean isTerminated() {
            return stopped && pending.isEmpty();
        }
        @Override public boolean awaitTermination(long time, TimeUnit unit) {
            return isTerminated();
        }
    }
}
