package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executable, dependency-light *real* OresScheduler -> JNI -> C smoke.
 *
 * Compiles the production OresScheduler/OresFuture implementation without the
 * Truffle dependency graph. The temporary ActorRuntime class used by the
 * script is only an actor-thread guard stub: this is NOT a compiled .ores
 * actor, Truffle, Native Image or LLVM parity test.
 */
public final class NativeActorTurnSchedulerStandaloneSmoke {
    private NativeActorTurnSchedulerStandaloneSmoke() { }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static NativeActorTurnKernel.SourceTask sourceInCurrentTurn()
            throws ReflectiveOperationException {
        Object task = OresScheduler.currentTaskDomain();
        require(task != null, "no scheduler task domain in guest continuation");
        Field field = task.getClass().getDeclaredField("nativeSource");
        field.setAccessible(true);
        return (NativeActorTurnKernel.SourceTask) field.get(task);
    }

    private static void waitFor(long deadlineNanos, java.util.function.BooleanSupplier ready)
            throws InterruptedException {
        while (!ready.getAsBoolean() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(2);
        }
        require(ready.getAsBoolean(), "lifecycle condition did not become true");
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    /**
     * Reaching a guest latch does not prove the guest has returned Await.
     * Wait for the real TaskRunner WAITING phase and physical carrier exit
     * before triggering deterministic STOP or native cleanup races.
     */
    private static void awaitPhysicalSuspension(OresScheduler scheduler, int count)
            throws Exception {
        Field tasksField = OresScheduler.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        long until = deadline();
        while (System.nanoTime() < until) {
            var live = (java.util.Set<?>) tasksField.get(scheduler);
            int parked = 0;
            for (Object runner : live) {
                Field phaseField = runner.getClass().getDeclaredField("phase");
                Field executingField = runner.getClass().getDeclaredField("executing");
                phaseField.setAccessible(true);
                executingField.setAccessible(true);
                var phase = (java.util.concurrent.atomic.AtomicInteger) phaseField.get(runner);
                var executing = (java.util.concurrent.atomic.AtomicBoolean)
                        executingField.get(runner);
                if (phase.get() == 3 && !executing.get()) parked++;
            }
            if (live.size() == count && parked == count) return;
            Thread.sleep(2);
        }
        throw new AssertionError("logical SourceTask did not park and release its carrier");
    }

    private static void ordinarySuspendResume() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64);
                OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                        1, Runnable::run, kernel)) {
            var awaited = new OresFuture<Integer>();
            var started = new CountDownLatch(1);
            var steps = new AtomicInteger();
            OresFuture<Integer> terminal = scheduler.start(resume -> {
                int turn = steps.incrementAndGet();
                if (turn == 1) {
                    require(resume.initial(), "first turn not initial");
                    started.countDown();
                    return OresScheduler.<Integer>await(awaited);
                }
                require(!resume.initial(), "resumption incorrectly initial");
                return OresScheduler.done(42);
            });
            require(started.await(5, TimeUnit.SECONDS), "guest never entered");
            awaitPhysicalSuspension(scheduler, 1);
            awaited.completeFromRuntime(1);
            require(terminal.get(5, TimeUnit.SECONDS) == 42,
                    "suspended guest result incorrect");
            require(steps.get() == 2, "source task should run twice, not concurrently");
            kernel.stop();
        }
    }

    private static void stopDeniesUserResumption() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64);
                OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                        1, Runnable::run, kernel)) {
            var awaited = new OresFuture<Integer>();
            var started = new CountDownLatch(1);
            var steps = new AtomicInteger();
            OresFuture<Integer> terminal = scheduler.start(resume -> {
                steps.incrementAndGet();
                started.countDown();
                return OresScheduler.<Integer>await(awaited);
            });
            require(started.await(5, TimeUnit.SECONDS), "guest never entered");
            awaitPhysicalSuspension(scheduler, 1);
            kernel.stop();
            awaited.completeFromRuntime(1);
            try {
                terminal.get(5, TimeUnit.SECONDS);
                throw new AssertionError("STOP permitted ordinary guest continuation");
            } catch (ExecutionException expected) {
                require(expected.getCause() instanceof IllegalStateException,
                        "wrong native STOP failure type");
            }
            require(steps.get() == 1, "ordinary user callback resumed after STOP");
        }
    }

    private static void pendingCleanupBlocksNativeDoneUntilTicketClosed() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64);
                OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                        1, Runnable::run, kernel)) {
            var awaited = new OresFuture<Integer>();
            var source = new AtomicReference<NativeActorTurnKernel.SourceTask>();
            var started = new CountDownLatch(1);
            var steps = new AtomicInteger();
            OresFuture<Integer> terminal = scheduler.start(resume -> {
                if (steps.incrementAndGet() == 1) {
                    source.set(sourceInCurrentTurn());
                    started.countDown();
                    return OresScheduler.<Integer>await(awaited);
                }
                return OresScheduler.done(91);
            });
            require(started.await(5, TimeUnit.SECONDS), "guest never entered");
            awaitPhysicalSuspension(scheduler, 1);
            var cleanup = kernel.issueCleanup(source.get());
            awaited.completeFromRuntime(1);
            waitFor(deadline(), () -> scheduler.nativeRetirementFailures() > 0);
            require(!terminal.isDone(), "pending cleanup ticket published DONE");
            kernel.cancelCleanup(cleanup);
            require(scheduler.retryNativeQuiescentTasks() == 1,
                    "trusted retry did not retire source");
            require(terminal.get(5, TimeUnit.SECONDS) == 91,
                    "terminal value did not publish after cleanup ticket closure");
            require(steps.get() == 2, "retry reentered guest work");
            require(scheduler.retryNativeQuiescentTasks() == 0,
                    "clean scheduler still has native retirement pending");
            kernel.stop();
        }
    }

    private static void closeInsideQuiescenceDoesNotClaimDone() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel);
            var attempted = new CountDownLatch(1);
            var unexpected = new AtomicReference<Throwable>();
            OresFuture<Integer> terminal = scheduler.start(
                    resume -> OresScheduler.done(314), () -> {
                try {
                    scheduler.close();
                    unexpected.set(new AssertionError(
                            "close reported success while trusted callback was still running"));
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().contains("unresolved SourceTasks"),
                            "close refused for the wrong reason");
                } catch (Throwable failure) {
                    unexpected.set(failure);
                } finally {
                    attempted.countDown();
                }
            });
            require(attempted.await(5, TimeUnit.SECONDS), "callback did not execute");
            if (unexpected.get() != null) throw new AssertionError(unexpected.get());
            // Reentrant close must have rejected BEFORE setting closed or
            // interrupting our trusted terminal callback. Normal guest DONE
            // must be published after that callback actually returns.
            require(terminal.get(5, TimeUnit.SECONDS) == 314,
                    "reentrant close incorrectly canceled the normal guest result");
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            waitFor(deadline(), () -> {
                try {
                    scheduler.close();
                    closed.set(true);
                    return true;
                } catch (IllegalStateException pending) {
                    if (!pending.getMessage().contains("unresolved SourceTasks")) {
                        throw pending;
                    }
                    return false;
                }
            });
            require(closed.get(), "native scheduler never completed quiescence");
            kernel.stop();
        }
    }

    public static void main(String[] args) throws Exception {
        require(Boolean.getBoolean("ores.native.actor.turn.experimental"),
                "test requires explicit experimental JNI opt-in");
        int iterations = Integer.getInteger(
                "ores.native.actor.turn.smoke.iterations", 12);
        require(iterations >= 1 && iterations <= 100,
                "smoke iterations must be between 1 and 100");
        for (int iteration = 1; iteration <= iterations; iteration++) {
            try {
                ordinarySuspendResume();
                stopDeniesUserResumption();
                pendingCleanupBlocksNativeDoneUntilTicketClosed();
                closeInsideQuiescenceDoesNotClaimDone();
            } catch (Exception | Error failure) {
                throw new AssertionError(
                        "JNI real-scheduler lifecycle iteration " + iteration
                                + " of " + iterations + " failed",
                        failure);
            }
        }
        System.out.println("NATIVE_ACTOR_TURN_REAL_SCHEDULER_JNI_OK iterations="
                + iterations + " scenarios=" + (4 * iterations));
    }
}
