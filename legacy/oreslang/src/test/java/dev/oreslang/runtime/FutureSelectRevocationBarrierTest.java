package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A select arm cannot observe cancellation before its producer's revocation finishes. */
@Timeout(15)
final class FutureSelectRevocationBarrierTest {
    @Test
    void cancelledStateIsNotSelectableUntilRevocationHookCompletes() throws Exception {
        var insideRevocation = new CountDownLatch(1);
        var allowRevocation = new CountDownLatch(1);
        var backgroundFailure = new AtomicReference<Throwable>();
        var producer = new OresFuture<Integer>(() -> {
            insideRevocation.countDown();
            try {
                if (!allowRevocation.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("revocation release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("revocation interrupted", interrupted);
            }
        });
        var waiting = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer)).selectAsync();
        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                assertTrue(producer.cancel(false));
            } catch (Throwable failure) {
                backgroundFailure.set(failure);
            }
        });

        try {
            assertTrue(insideRevocation.await(5, TimeUnit.SECONDS));
            assertTrue(producer.isCancelled(), "cancellation state was published");
            assertFalse(waiting.isDone(), "select must not bypass producer revocation");

            // A fresh immediate probe must also respect the same barrier.
            assertTrue(ChannelRuntime.SelectSet.of(ChannelRuntime.await(producer))
                    .trySelect().isEmpty());

            // Even with priority ordering, an unrevoked cancellation is not
            // a ready candidate. The channel may win without losing its value.
            var channel = new ChannelRuntime.Channel<Integer>(1);
            assertTrue(channel.tryWrite(91));
            var competing = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(producer),
                    ChannelRuntime.read(channel))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();
            assertEquals(ChannelRuntime.SelectOperation.READ, competing.operation());
            assertEquals(91, competing.value());
            assertFalse(channel.tryRead().isPresent());
        } finally {
            allowRevocation.countDown();
            canceller.join(5_000);
        }
        assertFalse(canceller.isAlive());
        if (backgroundFailure.get() != null) {
            throw new AssertionError("cancel thread failed", backgroundFailure.get());
        }

        var failure = assertThrows(CompletionException.class, waiting::join);
        assertTrue(failure.getCause() instanceof java.util.concurrent.CancellationException);
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }
}
