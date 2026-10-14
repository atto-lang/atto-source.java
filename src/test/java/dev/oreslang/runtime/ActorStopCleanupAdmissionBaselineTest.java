package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorStopCleanupAdmissionBaselineTest {
    @Test
    void stopRejectsAnAuthenticatedButNeverStartedSourceTurn() throws Exception {
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> task = context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            guestTurns.incrementAndGet();
                            return OresScheduler.done(19);
                        });
                taskRef.set(context.runtime().ownCurrentActorFuture(task));
                context.self().stop();
                published.countDown();
            });
            actor.send("stop");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, guestTurns.get(),
                    "a queued initial source turn has no lexical cleanup authority");
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingStopFenceDoesNotResumeSuspendedSourceCleanup() throws Exception {
        OresFuture<Integer> never = new OresFuture<>();
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> task = context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) event -> {
                            int turn = guestTurns.incrementAndGet();
                            if (turn == 1) {
                                suspended.countDown();
                                return OresScheduler.await(never);
                            }
                            throw new AssertionError("STOP cannot admit this continuation yet");
                        });
                taskRef.set(context.runtime().ownCurrentActorFuture(task));
            });
            actor.send("suspend");
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, guestTurns.get(), "STOP must not run a cleanup continuation without an actor permit");
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, never.pendingRuntimeWaiterCount());
            assertFalse(never.isCancelled(), "shared source producer remains owned by its caller");
        }
    }
}
