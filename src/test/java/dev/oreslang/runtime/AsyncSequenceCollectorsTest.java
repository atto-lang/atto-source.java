package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Bounded asynchronous sequence collection executed as real scheduler Tasks. */
public class AsyncSequenceCollectorsTest {
    @Test void collectsOutOfAlreadyBufferedChannelInFIFOOrder() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(3);
        assertTrue(channel.tryWrite(7));
        assertTrue(channel.tryWrite(8));
        assertTrue(channel.tryWrite(9));
        channel.close();

        try (OresScheduler scheduler = new OresScheduler(2)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), 3);
            assertEquals(List.of(7, 8, 9), collection.result().get(5, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(List.of(7, 8, 9)),
                    collection.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
        }
    }

    @Test void emptySequenceSucceedsAtZeroLimit() throws Exception {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        channel.close();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), 0);
            assertEquals(List.of(), collection.result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    collection.getDone().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void exceedsStrictLimitAndDoesNotRetainUnboundedResults() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(4);
        for (int i = 0; i < 4; i++) assertTrue(channel.tryWrite(i));
        channel.close();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), 2);
            var thrown = assertThrows(ExecutionException.class,
                    () -> collection.result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(AsyncSequenceCollectors.LimitExceededException.class,
                    thrown.getCause());
            var outcome = collection.getDone().get(5, TimeUnit.SECONDS);
            assertInstanceOf(TaskHandle.Failed.class, outcome);
            // An extra item is consumed only to detect overflow; the fourth
            // item was never admitted to the collector's bounded result list.
            assertEquals(Optional.of(3), channel.tryRead());
            assertEquals(0, channel.pendingRegistrations());
        }
    }

    @Test void aProducerErrorIsNotNormalEndOfStream() throws Exception {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite("ok"));
        IllegalArgumentException cause = new IllegalArgumentException("producer crashed");
        channel.close(cause);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), 10);
            var failure = assertThrows(ExecutionException.class,
                    () -> collection.result().get(5, TimeUnit.SECONDS));
            assertSame(cause, failure.getCause());
            var done = assertInstanceOf(TaskHandle.Failed.class,
                    collection.getDone().get(5, TimeUnit.SECONDS));
            assertSame(cause, done.failure());
        }
    }

    @Test void cancelWhileWaitingDetachesReadAndPreservesBorrowedChannel() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), 8);
            assertTrue(collection.cancel("consumer stopped"));
            var retired = collection.getDone().get(5, TimeUnit.SECONDS);
            assertInstanceOf(TaskHandle.Cancelled.class, retired);
            assertTrue(collection.result().isCancelled());
            assertEquals(0, channel.pendingRegistrations(),
                    "physical cleanup barrier must release every pending read");
            assertFalse(channel.isClosed(), "collector only borrowed channel");
            assertTrue(channel.tryWrite(31));
            assertEquals(Optional.of(31), channel.tryRead());
        }
    }

    @Test void physicallyRetiredBarrierRunsCursorCloseOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean nextInvoked = new AtomicBoolean();
        AsyncSequences.Cursor<Integer> cursor = new AsyncSequences.Cursor<>() {
            private boolean emitted;
            @Override
            public OresFuture<Optional<Integer>> nextAsync() {
                nextInvoked.set(true);
                if (emitted) return OresFuture.completed(Optional.empty());
                emitted = true;
                return OresFuture.completed(Optional.of(5));
            }
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(scheduler, cursor, 1);
            assertEquals(List.of(5), collection.result().get(5, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(List.of(5)),
                    collection.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(nextInvoked.get());
            assertEquals(1, closes.get());
        }
    }

    @Test void finiteLongSequenceRunsInSchedulerTurnsWithoutRecursiveCompletion()
            throws Exception {
        final int n = 512;
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(n);
        for (int i = 0; i < n; i++) assertTrue(channel.tryWrite(i));
        channel.close();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            var collection = AsyncSequenceCollectors.collect(
                    scheduler, AsyncSequences.fromChannel(channel), n);
            List<Integer> items = collection.result().get(10, TimeUnit.SECONDS);
            assertEquals(n, items.size());
            for (int i = 0; i < n; i++) assertEquals(Integer.valueOf(i), items.get(i));
            assertInstanceOf(TaskHandle.Success.class,
                    collection.getDone().get(10, TimeUnit.SECONDS));
            assertEquals(0, channel.pendingRegistrations());
        }
    }

    @Test void rejectsInvalidLimitsBeforeMovingCursor() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
            assertThrows(IllegalArgumentException.class, () ->
                    AsyncSequenceCollectors.collect(scheduler, cursor, -1));
            assertThrows(IllegalArgumentException.class, () ->
                    AsyncSequenceCollectors.collect(scheduler, cursor,
                            AsyncSequenceCollectors.MAX_COLLECT_ITEMS + 1));
            assertFalse(channel.isClosed());
            cursor.close();
        }
    }
}
