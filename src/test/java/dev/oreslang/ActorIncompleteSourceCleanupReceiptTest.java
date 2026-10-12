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
    void structuredCancellationPreservesCauseAndExactlyOneIncompleteCleanupReceipt()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        for (boolean pendingCleanup : new boolean[]{false, true}) {
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                CompletableFuture<Integer> stage = new CompletableFuture<>();
                var guest = new PendingSourceTask(OresFuture.from(stage));
                guest.cleanupPending.set(pendingCleanup);
                var actor = runtime.<Integer>spawnShared(() -> (mail, context) ->
                        context.runtime().startActorTask(guest));
                actor.send(1);
                assertTrue(guest.entered.await(5, TimeUnit.SECONDS));

                assertTrue(runtime.cancel(actor, ActorRuntime.CancellationMode.STRUCTURED));
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                var primary = actor.failure().orElseThrow();
                assertInstanceOf(ActorRuntime.ActorCancelledException.class, primary,
                        "the cancellation reason must remain the primary failure");
                long incompleteReceipts = java.util.Arrays.stream(primary.getSuppressed())
                        .filter(ActorRuntime.IncompleteSourceCleanupException.class::isInstance)
                        .count();
                assertEquals(pendingCleanup ? 1 : 0, incompleteReceipts,
                        "exactly one diagnostic receipt must distinguish abandoned guest cleanup");

                stage.complete(7);
                assertEquals(1, guest.resumeCount.get(),
                        "producer completion must not revive the cancelled actor");
            }
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
    @Test
    void sharedPrimaryFailureRetainsSeparateReceiptsForEachActor() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var original = new IllegalStateException("shared failure instance");
            var firstStage = new CompletableFuture<Integer>();
            var secondStage = new CompletableFuture<Integer>();
            var firstTask = new PendingSourceTask(OresFuture.from(firstStage));
            var secondTask = new PendingSourceTask(OresFuture.from(secondStage));
            var first = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                if (mail == 1) context.runtime().startActorTask(firstTask);
                else throw original;
            });
            var second = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                if (mail == 1) context.runtime().startActorTask(secondTask);
                else throw original;
            });

            first.send(1);
            second.send(1);
            assertTrue(firstTask.entered.await(5, TimeUnit.SECONDS));
            assertTrue(secondTask.entered.await(5, TimeUnit.SECONDS));

            first.send(2);
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            second.send(2);
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            assertSame(original, first.failure().orElseThrow());
            assertSame(original, second.failure().orElseThrow());

            var receipts = java.util.Arrays.stream(original.getSuppressed())
                    .filter(ActorRuntime.IncompleteSourceCleanupException.class::isInstance)
                    .map(ActorRuntime.IncompleteSourceCleanupException.class::cast)
                    .toList();
            assertEquals(2, receipts.size(),
                    "a receipt for one actor must not suppress another actor's evidence");
            assertEquals(java.util.Set.of(first.id(), second.id()),
                    receipts.stream().map(ActorRuntime.IncompleteSourceCleanupException::actorId)
                            .collect(java.util.stream.Collectors.toSet()));

            firstStage.complete(10);
            secondStage.complete(11);
            assertEquals(1, firstTask.resumeCount.get());
            assertEquals(1, secondTask.resumeCount.get());
        }
    }

    @Test
    void suppressedDisabledPrimaryFailsClosedAndRetainsOriginalCause() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        // Throwable.addSuppressed is specified to be a silent no-op for this cause.
        class NonSuppressingFailure extends RuntimeException {
            NonSuppressingFailure() {
                super("suppression disabled", null, false, true);
            }
        }
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var original = new NonSuppressingFailure();
            var stage = new CompletableFuture<Integer>();
            var task = new PendingSourceTask(OresFuture.from(stage));
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                if (mail == 1) context.runtime().startActorTask(task);
                else throw original;
            });

            actor.send(1);
            assertTrue(task.entered.await(5, TimeUnit.SECONDS));
            actor.send(2);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            var incomplete = assertInstanceOf(
                    ActorRuntime.IncompleteSourceCleanupException.class,
                    actor.failure().orElseThrow(),
                    "cannot silently discard missing-cleanup evidence");
            assertEquals(actor.id(), incomplete.actorId());
            assertSame(original, incomplete.getCause(),
                    "fallback receipt must retain the pre-existing failure");
            assertEquals(0, original.getSuppressed().length);

            stage.complete(10);
            assertEquals(1, task.resumeCount.get());
        }
    }

}
