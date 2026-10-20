package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cancellation authority is revoked before potentially blocking subscribers. */
final class TaskScopeTwoPhaseCancellationTest {
    @Test
    void blockedSubscriberCannotDelaySiblingRevocationOrNotification() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration firstTicket = scope.reserve();
        TaskScope.Registration secondTicket = scope.reserve();
        OresFuture<Void> first = new OresFuture<>();
        OresFuture<Void> second = new OresFuture<>();

        CountDownLatch firstSubscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSubscriber = new CountDownLatch(1);
        CountDownLatch secondSubscriberNotified = new CountDownLatch(1);
        first.whenCompleteRuntime((ignored, failure) -> {
            firstSubscriberEntered.countDown();
            try {
                releaseFirstSubscriber.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        second.whenCompleteRuntime((ignored, failure) ->
                secondSubscriberNotified.countDown());

        firstTicket.attach(first);
        secondTicket.attach(second);
        try {
            OresFuture<Void> joining = scope.cancelAndDrain();
            assertTrue(first.isCancelled());
            assertTrue(second.isCancelled());
            assertTrue(firstSubscriberEntered.await(5, TimeUnit.SECONDS));
            assertTrue(secondSubscriberNotified.await(5, TimeUnit.SECONDS),
                    "second subscriber must publish despite blocked first subscriber");
            assertFalse(joining.isDone(),
                    "terminal revocation cannot forge physical quiescence");
            firstTicket.quiesced();
            assertFalse(joining.isDone());
            secondTicket.quiesced();
            assertTrue(joining.isCompletedExceptionally());
        } finally {
            releaseFirstSubscriber.countDown();
        }
    }
}
