package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real actor scheduler cancellation across STOP and physical get_done. */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
final class ActorSourceCleanupTerminationTest {
    private static final class SuspendedSource {
        final OresFuture<Integer> upstream = new OresFuture<>();
        final OresFuture<Integer> cleanup = new OresFuture<>();
        final CountDownLatch armed = new CountDownLatch(1);
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final AtomicInteger cleanupRuns = new AtomicInteger();
        final AtomicInteger normalMessages = new AtomicInteger();
        final AtomicBoolean draining = new AtomicBoolean();
        final AtomicReference<OresFuture<Integer>> taskResult = new AtomicReference<>();
    }

    private static ActorRuntime.ActorRef<Integer> spawn(
            ActorRuntime runtime, SuspendedSource state) {
        return runtime.spawnPrivate(() -> (message, ctx) -> {
            state.normalMessages.incrementAndGet();
            state.taskResult.set(ctx.runtime().startActorTask(
                    new OresScheduler.CancellationAwareTask<Integer>() {
                        @Override public boolean cleanupInProgress() {
                            return state.draining.get();
                        }

                        @Override public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) { }

                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resumed) {
                            if (resumed.initial()) {
                                state.armed.countDown();
                                return OresScheduler.await(state.upstream);
                            }
                            if (!state.draining.getAndSet(true)) {
                                assertInstanceOf(
                                        OresScheduler.TaskCancelledException.class,
                                        resumed.failure());
                                state.cleanupRuns.incrementAndGet();
                                state.cleanupEntered.countDown();
                                return OresScheduler.await(state.cleanup);
                            }
                            assertNull(resumed.failure());
                            return OresScheduler.done(42);
                        }
                    }));
        });
    }

    @Test
    void stopDrainsSourceCleanupOnActorLaneBeforePublishingDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            SuspendedSource state = new SuspendedSource();
            ActorRuntime.ActorRef<Integer> actor = spawn(runtime, state);
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(state.armed.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> stop = CompletableFuture.runAsync(actor::stop);
            assertTrue(state.cleanupEntered.await(5, TimeUnit.SECONDS),
                    "scheduler cancellation must enter the actor cleanup-only lane");
            assertFalse(actor.get_done().isDone(),
                    "get_done cannot publish before a suspended source finalizer drains");
            assertEquals(1, state.cleanupRuns.get());
            assertEquals(1, state.normalMessages.get());
            assertTrue(state.cleanup.completeFromRuntime(7));

            stop.get(5, TimeUnit.SECONDS);
            actor.get_done().get(5, TimeUnit.SECONDS);
            assertEquals(1, state.cleanupRuns.get(),
                    "STOP must not replay the lexical finalizer");
            assertEquals(1, state.normalMessages.get(),
                    "cleanup-only CONTROL may not re-open DATA admission");
        }
    }

    @Test
    void neverResolvingSourceCleanupFailsDoneWithoutClaimingPhysicalTermination()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            SuspendedSource state = new SuspendedSource();
            ActorRuntime.ActorRef<Integer> actor = spawn(runtime, state);
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(state.armed.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> stop = CompletableFuture.runAsync(actor::stop);
            assertTrue(state.cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(actor.get_done().isDone());
            ExecutionException failedShutdown = assertThrows(
                    ExecutionException.class,
                    () -> actor.get_shutdown_result().get(9, TimeUnit.SECONDS));
            assertTrue(failedShutdown.getCause().toString()
                            .contains("unfinished cancellation cleanup"),
                    "the scheduler's failed drain must reach shutdown observer");
            assertFalse(actor.get_done().isDone(),
                    "a failed cleanup is NOT a physical termination barrier");
            assertFalse(actor.get_done().cancel(false),
                    "physical termination observation is not cancellation authority");
            ExecutionException failedStop = assertThrows(
                    ExecutionException.class,
                    () -> stop.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failedStop.getCause());
            assertEquals(1, state.cleanupRuns.get());
            assertFalse(actor.awaitTermination(0, TimeUnit.MILLISECONDS),
                    "failed logical teardown is not physical finalization");
        } finally {
            try {
                runtime.close();
            } catch (IllegalStateException expected) {
                // The intentionally unresolved source finalizer retains physical
                // resources, so runtime-close must also fail closed.
            }
        }
    }
}
