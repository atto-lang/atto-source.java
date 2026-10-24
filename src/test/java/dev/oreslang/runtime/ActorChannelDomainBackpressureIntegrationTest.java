package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorChannelDomainBackpressureIntegrationTest {
    private static IsolatePolicy oneMessagePolicy() {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                1,
                base.maxWallTime(),
                base.adversarial());
    }

    @Test
    void mailboxBackpressureAndRuntimeDomainCoexist() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var actor = left.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("actor turn timeout");
                            }
                        }
                    });

            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send(2);
            OresFuture<Void> pending = actor.sendAsync(3);
            assertFalse(pending.isDone());

            // Mailbox waiters use actor-owned admission state, not generic
            // Channel select credits; unrelated runtimes retain their budget.
            assertEquals(0, right.channelAdmissionDomain().pendingSelections());

            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            actor.drain().get(5, TimeUnit.SECONDS);

            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateCancellationStillLosesAfterTransferredCredit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch transferred = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.asyncSendCreditTransferHookForTesting = () -> {
                transferred.countDown();
                try {
                    if (!publish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("credit publication timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            var actor = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("actor turn timeout");
                            }
                        }
                    });
            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send(2);
            OresFuture<Void> pending = actor.sendAsync(3);

            release.countDown();
            assertTrue(transferred.await(5, TimeUnit.SECONDS));
            assertFalse(pending.cancel(false));
            publish.countDown();
            pending.get(5, TimeUnit.SECONDS);

            actor.drain().get(5, TimeUnit.SECONDS);
            runtime.asyncSendCreditTransferHookForTesting = null;
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            publish.countDown();
            release.countDown();
        }
    }
}
