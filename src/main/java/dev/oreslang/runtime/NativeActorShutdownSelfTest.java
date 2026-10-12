package dev.oreslang.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in native executable smoke for actor-owned cancellation and finalization.
 * Unlike a native-image build check, this starts real actor turns and a
 * suspended cleanup Future inside the running executable.
 */
public final class NativeActorShutdownSelfTest {
    private NativeActorShutdownSelfTest() { }

    public static void run() throws Exception {
        CountDownLatch armed = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        OresFuture<Integer> upstream = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        AtomicBoolean cleaning = new AtomicBoolean();
        AtomicInteger cleanupRuns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    () -> (message, ctx) -> task.set(ctx.runtime().startActorTask(
                            new OresScheduler.CancellationAwareTask<Integer>() {
                                @Override public boolean cleanupInProgress() {
                                    return cleaning.get();
                                }

                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) { }

                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume resumed) {
                                    if (resumed.initial()) {
                                        armed.countDown();
                                        return OresScheduler.await(upstream);
                                    }
                                    if (!cleaning.getAndSet(true)) {
                                        if (!(resumed.failure()
                                                instanceof OresScheduler.TaskCancelledException)) {
                                            throw new AssertionError(
                                                    "cleanup must receive cancellation");
                                        }
                                        cleanupRuns.incrementAndGet();
                                        cleanupEntered.countDown();
                                        return OresScheduler.await(cleanup);
                                    }
                                    if (resumed.failure() != null) {
                                        throw new AssertionError(
                                                "cleanup resumed with unexpected failure",
                                                resumed.failure());
                                    }
                                    return OresScheduler.done(42);
                                }
                            })));

            actor.ready().get(10, TimeUnit.SECONDS);
            actor.send(1);
            if (!armed.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("actor task never reached its upstream await");
            }
            CompletableFuture<Void> stopped = CompletableFuture.runAsync(actor::stop);
            if (!cleanupEntered.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("source cancellation never entered cleanup CONTROL");
            }
            if (actor.get_done().isDone()) {
                throw new AssertionError("get_done completed before pending cleanup");
            }
            if (!cleanup.completeFromRuntime(7)) {
                throw new AssertionError("cleanup Future did not settle");
            }
            stopped.get(10, TimeUnit.SECONDS);
            actor.get_done().get(10, TimeUnit.SECONDS);
            if (cleanupRuns.get() != 1) {
                throw new AssertionError("cleanup ran " + cleanupRuns.get() + " times");
            }
            if (task.get() == null || !task.get().isDone()) {
                throw new AssertionError("source task did not settle after cleanup");
            }
        }
        checkFailedDrainRetainsPhysicalOwnership();
        checkNativeLoanRetirementFencesPhysicalDone();
        System.out.println("ORES_NATIVE_SHUTDOWN|PASS|cleanup=1|physical_done=1|failed_drain_fenced=1|native_loans=1");
    }

    private static void checkNativeLoanRetirementFencesPhysicalDone() throws Exception {
        if (!ExplicitNativeI64Region.isAvailable()) {
            throw new AssertionError("native-image actor shutdown requires real FFM backing");
        }
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<ExplicitNativeI64Region> region = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ActorRuntime runtime = new ActorRuntime();
        try {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    () -> (message, context) -> {
                        try {
                            ExplicitNativeI64Region memory = context
                                    .actorOwnedMemory().orElseThrow()
                                    .newExplicitNativeI64Region(1);
                            region.set(memory);
                            loan.set(memory.borrow(memory.allocate(42)));
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            created.countDown();
                        }
                    });
            actor.ready().get(10, TimeUnit.SECONDS);
            actor.send(1);
            if (!created.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("native loan creation never entered actor turn");
            }
            if (failure.get() != null) {
                throw new AssertionError("native loan creation failed", failure.get());
            }
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(actor::stop);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!region.get().closed() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            if (!region.get().closed() || actor.get_done().isDone()) {
                throw new AssertionError(
                        "logical STOP must retire arena but retain physical done for native loans");
            }
            if (runtime.privateMemoryBytes() != Long.BYTES) {
                throw new AssertionError("native FFM quota must stay charged while pinned");
            }
            loan.get().close();
            stopping.get(10, TimeUnit.SECONDS);
            actor.get_done().get(10, TimeUnit.SECONDS);
            if (!region.get().physicallyClosed()
                    || runtime.privateMemoryBytes() != 0L) {
                throw new AssertionError(
                        "last native loan did not release arena and aggregate quota");
            }
        } finally {
            runtime.close();
        }
    }

    private static void checkFailedDrainRetainsPhysicalOwnership() throws Exception {
        CountDownLatch armed = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        OresFuture<Integer> upstream = new OresFuture<>();
        OresFuture<Integer> neverCompletes = new OresFuture<>();
        AtomicBoolean cleaning = new AtomicBoolean();
        AtomicInteger cleanupRuns = new AtomicInteger();
        ActorRuntime runtime = new ActorRuntime();
        try {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    () -> (message, ctx) -> ctx.runtime().startActorTask(
                            new OresScheduler.CancellationAwareTask<Integer>() {
                                @Override public boolean cleanupInProgress() {
                                    return cleaning.get();
                                }
                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) { }
                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume resumed) {
                                    if (resumed.initial()) {
                                        armed.countDown();
                                        return OresScheduler.await(upstream);
                                    }
                                    if (cleaning.compareAndSet(false, true)) {
                                        cleanupRuns.incrementAndGet();
                                        cleanupEntered.countDown();
                                        return OresScheduler.await(neverCompletes);
                                    }
                                    throw new AssertionError(
                                            "stale cancellation replayed unfinished cleanup");
                                }
                            }));
            actor.ready().get(10, TimeUnit.SECONDS);
            actor.send(1);
            if (!armed.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("failed-drain actor did not suspend");
            }
            CompletableFuture<Void> stopped = CompletableFuture.runAsync(actor::stop);
            if (!cleanupEntered.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("failed-drain actor did not enter cleanup");
            }
            if (actor.get_done().isDone()) {
                throw new AssertionError("pending cleanup published physical termination");
            }
            try {
                actor.get_shutdown_result().get(9, TimeUnit.SECONDS);
                throw new AssertionError("failed cleanup unexpectedly reported success");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!String.valueOf(expected.getCause())
                        .contains("unfinished cancellation cleanup")) {
                    throw expected;
                }
            }
            if (actor.get_done().isDone() || cleanupRuns.get() != 1) {
                throw new AssertionError(
                        "failed drain released physical barrier or replayed cleanup");
            }
            try {
                stopped.get(5, TimeUnit.SECONDS);
                throw new AssertionError("stop unexpectedly reported successful teardown");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)) {
                    throw expected;
                }
            }
        } finally {
            try {
                runtime.close();
            } catch (IllegalStateException expected) {
                // Intentionally unresolved cleanup retains physical resources.
            }
        }
    }
}
