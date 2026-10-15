package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mailbox drain proves guest message processing, but not that the carrier
 * has crossed back out of TurnExecutor (and the polyglot context). HTTP
 * shutdown must wait for that second, physical-lifetime boundary.
 */
@Timeout(15)
final class ActorCarrierQuiescenceTest {
    @Test
    void mailboxDrainCannotImpersonatePhysicalCarrierExit() throws Exception {
        CountDownLatch guestProcessed = new CountDownLatch(1);
        CountDownLatch afterGuestBeforeCarrierExit = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        AtomicBoolean gateOnce = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (guestProcessed.getCount() == 0 && gateOnce.compareAndSet(false, true)) {
                afterGuestBeforeCarrierExit.countDown();
                try {
                    if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release test carrier");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("carrier test interrupted", interrupted);
                }
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnShared(() -> (message, context) -> guestProcessed.countDown());
            try {
                actor.send(1);
                assertTrue(guestProcessed.await(5, TimeUnit.SECONDS));
                assertTrue(afterGuestBeforeCarrierExit.await(5, TimeUnit.SECONDS));
                assertTrue(actor.drain().isDone(),
                        "mailbox completion precedes physical TurnExecutor exit");

                OresFuture<Void> first = runtime.quiesceAcceptedMessages(actor);
                OresFuture<Void> second = runtime.quiesceAcceptedMessages(actor);
                assertFalse(first.isDone(),
                        "quiescence must not finish inside the physical carrier boundary");
                assertFalse(second.isDone(), "each shutdown observer must stay pending");
                assertFalse(first.cancel(false), "lifecycle observers are noncancellable");
                releaseCarrier.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertFalse(ActorRuntime.inActorExecution());
            } finally {
                releaseCarrier.countDown();
            }
        }
    }

    @Test
    void alreadyQueuedFollowupTurnMustNotProduceFalseQuiescence() throws Exception {
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch firstCarrierPaused = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        CountDownLatch secondCarrierPaused = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean pausedFirst = new AtomicBoolean();
        AtomicBoolean pausedSecond = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (firstProcessed.getCount() == 0
                    && pausedFirst.compareAndSet(false, true)) {
                firstCarrierPaused.countDown();
                awaitTestRelease(releaseFirst);
            } else if (secondProcessed.getCount() == 0
                    && pausedSecond.compareAndSet(false, true)) {
                secondCarrierPaused.countDown();
                awaitTestRelease(releaseSecond);
            }
        };

        // Throughput one guarantees that the second accepted mail is queued
        // behind the first physical TurnExecutor exit, not processed in the
        // same batch. Observe the first, then admit the follow-up.
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> {
                        if (message == 1) firstProcessed.countDown();
                        else if (message == 2) secondProcessed.countDown();
                    });
            try {
                actor.send(1);
                assertTrue(firstCarrierPaused.await(5, TimeUnit.SECONDS));
                OresFuture<Void> barrier = runtime.quiesceAcceptedMessages(actor);
                assertFalse(barrier.isDone());
                actor.send(2); // accepted after the barrier snapshot
                releaseFirst.countDown();
                assertTrue(secondCarrierPaused.await(5, TimeUnit.SECONDS));
                assertFalse(barrier.isDone(),
                        "the first carrier exit cannot authorize Context.close with a queued successor");
                releaseSecond.countDown();
                barrier.get(5, TimeUnit.SECONDS);
                assertTrue(secondProcessed.getCount() == 0);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    private static void awaitTestRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test carrier release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test carrier interrupted", interrupted);
        }
    }

    @Test
    void foreignRuntimeCannotClaimQuiescenceOfAnotherActor() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor =
                    owner.spawnShared(() -> (message, context) -> { });
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> foreign.quiesceAcceptedMessages(actor));
        }
    }
}
