package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic interleavings at the Future-select waiter publication barrier. */
final class FutureSelectAttachmentFailureTest {
    private static final class PausedAttachment implements AutoCloseable {
        private final CountDownLatch attached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<OresFuture<ChannelRuntime.SelectResult>> selection =
                new AtomicReference<>();
        private final Thread worker;

        PausedAttachment(ChannelRuntime.SelectSet set) {
            worker = Thread.startVirtualThread(() -> {
                try {
                    set.selectAsyncForTesting(ChannelRuntime.SelectPolicy.PRIORITY,
                            selection::set, () -> {
                                attached.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS)) {
                                        throw new AssertionError("attachment pause timed out");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError("attachment interrupted", interrupted);
                                }
                            });
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
        }

        OresFuture<ChannelRuntime.SelectResult> pendingSelection() throws Exception {
            assertTrue(attached.await(10, TimeUnit.SECONDS),
                    "Future listener did not reach the attachment barrier");
            return selection.get();
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "attachment did not finish");
            if (failure.get() != null) {
                throw new AssertionError("attachment failed", failure.get());
            }
        }
    }

    @Test
    void producerFailureCannotPublishBeforeLosingWaiterIsDetached()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var failure = new IllegalStateException("failed producer");
        OresFuture<ChannelRuntime.SelectResult> selected;
        try (var paused = new PausedAttachment(
                ChannelRuntime.SelectSet.of(ChannelRuntime.await(producer)))) {
            selected = paused.pendingSelection();
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            assertTrue(producer.failFromRuntime(failure));
            assertFalse(selected.isDone(), "failure published during attachment");
            assertFalse(selected.cancel(false), "committed winner was cancelled");
        }
        var thrown = assertThrows(CompletionException.class, selected::join);
        assertEquals(failure, thrown.getCause());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void channelCloseFailureDoesNotOvertakeFutureWaiterReclamation()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var closeCause = new IllegalStateException("close cause");
        OresFuture<ChannelRuntime.SelectResult> selected;
        try (var paused = new PausedAttachment(
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(channel), ChannelRuntime.await(producer)))) {
            selected = paused.pendingSelection();
            assertTrue(producer.pendingRuntimeWaiterCount() > 0);
            channel.close(closeCause);
            assertFalse(selected.isDone(), "channel close published early");
            assertFalse(selected.cancel(false), "committed close was cancelled");
        }
        var thrown = assertThrows(CompletionException.class, selected::join);
        assertTrue(thrown.getCause()
                instanceof ChannelRuntime.ChannelClosedException);
        assertEquals(closeCause, thrown.getCause().getCause());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(0, channel.pendingRegistrations());
        assertFalse(producer.isDone());
    }

    @Test
    void cancelledSelectionIgnoresLaterProducerFailureAndReleasesChannel()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        OresFuture<ChannelRuntime.SelectResult> selected;
        try (var paused = new PausedAttachment(
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.await(producer), ChannelRuntime.read(channel)))) {
            selected = paused.pendingSelection();
            assertTrue(selected.cancel(false));
            assertTrue(producer.failFromRuntime(
                    new IllegalStateException("late producer failure")));
            assertTrue(selected.isCancelled());
            assertEquals(0, channel.pendingRegistrations());
        }
        assertThrows(CancellationException.class, selected::join);
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite(43));
        assertEquals(43, channel.tryRead().orElseThrow());
    }


    @Test
    void cancellationOfPausedSelectionDoesNotBlockSecondConsumer()
            throws Exception {
        var producer = new OresFuture<Integer>();
        OresFuture<ChannelRuntime.SelectResult> cancelled;
        try (var paused = new PausedAttachment(
                ChannelRuntime.SelectSet.of(ChannelRuntime.await(producer)))) {
            cancelled = paused.pendingSelection();
            var second = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(producer))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            assertEquals(2, producer.pendingRuntimeWaiterCount());
            assertTrue(cancelled.cancel(false));
            assertFalse(second.isDone());
            assertTrue(producer.completeFromRuntime(51));
            assertEquals(51, second.get(5, TimeUnit.SECONDS).value());
            assertTrue(cancelled.isCancelled());
        }
        assertThrows(CancellationException.class, cancelled::join);
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void unpublishedChannelWinnerDoesNotDelayOtherProducerConsumer()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var channel = new ChannelRuntime.Channel<Integer>(1);
        OresFuture<ChannelRuntime.SelectResult> first;
        try (var paused = new PausedAttachment(ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer),
                ChannelRuntime.read(channel)))) {
            first = paused.pendingSelection();
            var second = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(producer))
                    .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
            assertEquals(2, producer.pendingRuntimeWaiterCount());
            assertTrue(channel.tryWrite(17));
            assertFalse(first.isDone(), "first result overtook pending detach");
            assertTrue(producer.completeFromRuntime(29));
            assertEquals(29, second.get(5, TimeUnit.SECONDS).value());
            assertFalse(first.isDone(),
                    "independent producer completion released the wrong barrier");
        }
        var firstWinner = first.get(5, TimeUnit.SECONDS);
        assertEquals(1, firstWinner.index());
        assertEquals(17, firstWinner.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void repeatedProducerArmsDoNotPublishUntilFirstAttachmentDrains()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var arms = new ArrayList<ChannelRuntime.SelectCase>();
        for (int index = 0; index < 128; index++) {
            arms.add(ChannelRuntime.await(producer));
        }
        OresFuture<ChannelRuntime.SelectResult> selected;
        try (var paused = new PausedAttachment(new ChannelRuntime.SelectSet(arms))) {
            selected = paused.pendingSelection();
            assertTrue(producer.completeFromRuntime(99));
            assertFalse(selected.isDone(), "result published during attachment");
            assertFalse(selected.cancel(false));
        }
        var winner = selected.get(10, TimeUnit.SECONDS);
        assertEquals(0, winner.index());
        assertEquals(99, winner.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }
}
