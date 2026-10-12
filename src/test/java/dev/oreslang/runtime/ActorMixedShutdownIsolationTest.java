package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** A SHARED actor's source cleanup must not block PRIVATE carrier interrupts. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class ActorMixedShutdownIsolationTest {
    @Test
    void pendingSharedSourceCleanupDoesNotDelayPrivateActorShutdownInterrupt()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config);
        OresFuture<Integer> upstream = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        CountDownLatch sourceArmed = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch privateEntered = new CountDownLatch(1);
        CountDownLatch privateInterrupted = new CountDownLatch(1);
        AtomicBoolean cleaning = new AtomicBoolean();
        try {
            var shared = runtime.<Integer>spawnShared(() -> (mail, ctx) ->
                    ctx.runtime().startActorTask(
                            new OresScheduler.CancellationAwareTask<Integer>() {
                                @Override public boolean cleanupInProgress() {
                                    return cleaning.get();
                                }

                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) { }

                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume resumed) {
                                    if (resumed.initial()) {
                                        sourceArmed.countDown();
                                        return OresScheduler.await(upstream);
                                    }
                                    if (cleaning.compareAndSet(false, true)) {
                                        assertInstanceOf(
                                                OresScheduler.TaskCancelledException.class,
                                                resumed.failure());
                                        cleanupEntered.countDown();
                                        return OresScheduler.await(cleanup);
                                    }
                                    return OresScheduler.done(42);
                                }
                            }));
            var privateActor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                privateEntered.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    privateInterrupted.countDown();
                    Thread.interrupted();
                }
            });
            shared.ready().get(5, TimeUnit.SECONDS);
            privateActor.ready().get(5, TimeUnit.SECONDS);
            shared.send(1);
            privateActor.send("block");
            assertTrue(sourceArmed.await(5, TimeUnit.SECONDS));
            assertTrue(privateEntered.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> closing = CompletableFuture.runAsync(runtime::close);
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS),
                    "source cleanup must reach its bounded CONTROL lane");
            assertTrue(privateInterrupted.await(3, TimeUnit.SECONDS),
                    "unrelated PRIVATE carrier must be interrupted while SHARED cleanup waits");
            assertFalse(shared.get_done().isDone(),
                    "SHARED actor cannot physically finalize before cleanup");
            assertFalse(closing.isDone());
            assertTrue(cleanup.completeFromRuntime(7));
            closing.get(5, TimeUnit.SECONDS);
            shared.get_done().get(5, TimeUnit.SECONDS);
            privateActor.get_done().get(5, TimeUnit.SECONDS);
        } finally {
            cleanup.completeFromRuntime(7);
            runtime.close();
        }
    }
}
