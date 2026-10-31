package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime continuation mailbox, not a simulated queue.
 * These are host-only scheduler contracts, not guest compiler evidence.
 */
@Timeout(15)
final class WebSocketActorMailboxDeliveryTest {
    @Test
    void liveActorExecutesHostTicketOnItsMailboxTurnAndReleasesOnce() throws Exception {
        var delivered = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var releases = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    ctx -> (mail, turn) -> { });
            actor.ready().get(3, TimeUnit.SECONDS);
            assertTrue(runtime.enqueueHostActorDelivery(actor,
                    () -> {
                        assertEquals(runtime, ActorRuntime.currentActorRuntime());
                        assertEquals(actor.address(),
                                ActorRuntime.currentActorAddress().orElseThrow());
                        executions.incrementAndGet();
                        delivered.countDown();
                    }, releases::incrementAndGet));
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (releases.get() != 1 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, executions.get());
            assertEquals(1, releases.get());
            actor.cancel();
        }
    }

    @Test
    void fullAndStoppedMailboxNeverForgetsAcceptedCleanupOrRunsGuestWork()
            throws Exception {
        var entered = new CountDownLatch(1);
        var releaseTurn = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var releases = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    ctx -> (mail, turn) -> {
                        entered.countDown();
                        if (!releaseTurn.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("held actor mailbox timed out");
                        }
                    });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("hold");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            int accepted = 0;
            try {
                for (int i = 0; i < 1_100; i++) {
                    boolean admitted = runtime.enqueueHostActorDelivery(
                            actor, executions::incrementAndGet, releases::incrementAndGet);
                    if (!admitted) break;
                    accepted++;
                }
                assertTrue(accepted > 0);
                assertTrue(accepted <= 1_024,
                        "bounded internal continuation slots must not overflow");
                assertFalse(runtime.enqueueHostActorDelivery(
                        actor, () -> fail("overfull continuation ran"),
                        () -> fail("rejected admission cannot claim caller cleanup")));
                assertTrue(actor.cancel());
            } finally {
                releaseTurn.countDown();
            }
            final int expected = accepted;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (releases.get() != expected && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(expected, releases.get(),
                    "every accepted host mailbox event retires once after STOP");
            assertEquals(0, executions.get(),
                    "STOP drains queued host deliveries without running their handlers");
            assertFalse(runtime.enqueueHostActorDelivery(
                    actor, () -> fail("stopped actor message executed"),
                    () -> fail("stopped actor did not accept this event")));
        }
    }
}
