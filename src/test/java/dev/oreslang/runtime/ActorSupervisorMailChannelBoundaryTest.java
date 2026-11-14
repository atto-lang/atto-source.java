package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gate for the distinction between private Channel-backed USER
 * mail and host/supervisor lifecycle CONTROL. These tests do not claim that
 * the unmerged lossless child-failure CONTROL protocol is implemented.
 */
@Timeout(25)
final class ActorSupervisorMailChannelBoundaryTest {
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
    void supervisorCancellationIsIndependentOfFullUserMailboxAndPendingProducer()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch siblingProcessed = new CountDownLatch(1);
        AtomicInteger laterMessagesProcessed = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var busy = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held actor turn timed out");
                            }
                        } else {
                            laterMessagesProcessed.incrementAndGet();
                        }
                    });
            var sibling = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 99) siblingProcessed.countDown();
                    });

            busy.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            busy.send(2); // one queued USER slot is full
            assertThrows(ActorRuntime.ActorMailboxFullException.class,
                    () -> busy.send(4));

            OresFuture<Void> pending = busy.sendAsync(3);
            assertFalse(pending.isDone(), "USER backpressure must not fake delivery");
            assertTrue(runtime.mailboxUsesChannelTransport(busy));

            // Supervisor CONTROL authority is not serialized into the USER
            // mailbox, and does not need the producer to release a USER slot.
            ActorRuntime.ActorControlHandle control = runtime.controlHandle(busy);
            assertTrue(control.cancel(), "host supervisor cancellation must be admitted");
            assertFalse(busy.isAlive());
            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(rejected.getCause(),
                    "the pending producer must receive a terminal failure");

            // The host fixture intentionally held a physical carrier.
            // Release it before checking sibling progress: a blocking host
            // callback is NOT a valid cooperative scheduler-liveness test.
            release.countDown();
            assertTrue(busy.awaitTermination(5, TimeUnit.SECONDS),
                    "physical retirement must eventually join the held turn");
            sibling.send(99);
            assertTrue(siblingProcessed.await(5, TimeUnit.SECONDS));
            assertEquals(0, laterMessagesProcessed.get(),
                    "cancelled queued USER mail must not execute");
            sibling.stop();
            assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void rawChannelAndNestedChannelCannotBeSmuggledThroughActorMail()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime();
             ChannelRuntime.Channel<Integer> source = new ChannelRuntime.Channel<>(1)) {
            var receiver = runtime.<Object>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if ("valid".equals(message)) accepted.countDown();
                    });

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(source));
            assertTrue(direct.getMessage().contains("channel"));
            assertThrows(IllegalArgumentException.class,
                    () -> receiver.send(List.of(source)));
            IllegalArgumentException reader = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(source.output()));
            IllegalArgumentException writer = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(source.input()));
            assertTrue(reader.getMessage().contains("channel capability"));
            assertTrue(writer.getMessage().contains("channel capability"));
            assertThrows(IllegalArgumentException.class,
                    () -> receiver.send(List.of(source.output(), source.input())));

            // Rejection must not consume the USER mailbox credit.
            receiver.send("valid");
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            receiver.drain().get(5, TimeUnit.SECONDS);
            receiver.stop();
            assertTrue(receiver.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullMailboxCannotTurnChannelCapabilityIntoAnAsyncWaiter()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime();
             ChannelRuntime.Channel<Integer> source = new ChannelRuntime.Channel<>(1)) {
            var receiver = runtime.<Object>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (Integer.valueOf(1).equals(message)) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held actor turn timed out");
                            }
                        }
                        accepted.incrementAndGet();
                    });

            receiver.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            receiver.send(2);

            // sendAsync must validate the channel capability even when its
            // fast path first encounters a full ordinary mailbox.
            assertThrows(IllegalArgumentException.class,
                    () -> receiver.sendAsync(source));
            IllegalArgumentException readDenied = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.sendAsync(source.output()));
            IllegalArgumentException writeDenied = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.sendAsync(source.input()));
            assertTrue(readDenied.getMessage().contains("channel capability"));
            assertTrue(writeDenied.getMessage().contains("channel capability"));
            OresFuture<Void> legitimate = receiver.sendAsync(3);
            assertFalse(legitimate.isDone(),
                    "failed capability admission must not steal USER credit");
            assertTrue(legitimate.cancel(false));
            release.countDown();

            receiver.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, accepted.get());
            receiver.stop();
            assertTrue(receiver.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
