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
