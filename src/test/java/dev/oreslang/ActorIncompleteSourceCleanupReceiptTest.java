package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stopped actor cannot claim graceful get_done() when an abandoned source
 * task still owns registered defer/finally obligations. This is a safety
 * receipt, NOT a test of guest cleanup execution (which needs a new CONTROL
 * cleanup-only authorization protocol).
 */
@Timeout(20)
final class ActorIncompleteSourceCleanupReceiptTest {
    private static final class PendingSourceTask
            implements OresScheduler.Task<Void>, OresScheduler.GuestCleanupObligation {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean cleanupPending = new AtomicBoolean(true);
        final AtomicInteger resumeCount = new AtomicInteger();
        final OresFuture<Integer> awaited;

        PendingSourceTask(OresFuture<Integer> awaited) {
            this.awaited = awaited;
        }

        @Override public boolean hasPendingGuestCleanup() {
            return cleanupPending.get();
        }

        @Override public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
            if (resumeCount.incrementAndGet() > 1) {
                throw new AssertionError("old incarnation source task was revived after STOP");
            }
            entered.countDown();
            return OresScheduler.await(awaited);
        }
    }

    @Test
    void stoppedActorWithRegisteredCleanupReportsIncompleteRatherThanSuccess() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CompletableFuture<Integer> stage = new CompletableFuture<>();
            var guest = new PendingSourceTask(OresFuture.from(stage));
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) ->
                    context.runtime().startActorTask(guest));
            actor.send(1);
            assertTrue(guest.entered.await(5, TimeUnit.SECONDS), "source task never reached await");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.IncompleteSourceCleanupException.class,
                    actor.failure().orElseThrow(),
                    "get_done must not claim that suspended lexical cleanup settled");
            stage.complete(7);
            assertEquals(1, guest.resumeCount.get(), "late producer cannot reenter dead actor");
        }
    }

    @Test
    void cancelledTaskWithoutRegisteredCleanupKeepsOrdinaryGracefulStop() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CompletableFuture<Integer> stage = new CompletableFuture<>();
            var guest = new PendingSourceTask(OresFuture.from(stage));
            guest.cleanupPending.set(false);
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) ->
                    context.runtime().startActorTask(guest));
            actor.send(1);
            assertTrue(guest.entered.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "no guest lexical cleanup was registered");
            stage.complete(7);
            assertEquals(1, guest.resumeCount.get());
        }
    }
}
