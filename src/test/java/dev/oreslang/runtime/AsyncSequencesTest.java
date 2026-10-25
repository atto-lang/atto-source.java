package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real ChannelRuntime/OresFuture nonparking stream adapter regression tests. */
public class AsyncSequencesTest {
    @Test void bufferedChannelPreservesFIFOAndProducesEOF() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryWrite(4));
        assertTrue(channel.tryWrite(7));
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        assertEquals(Optional.of(4), cursor.nextAsync().get(3, TimeUnit.SECONDS));
        assertEquals(Optional.of(7), cursor.nextAsync().get(3, TimeUnit.SECONDS));
        OresFuture<Optional<Integer>> waiting = cursor.nextAsync();
        assertFalse(waiting.isDone());
        channel.close();
        assertEquals(Optional.empty(), waiting.get(3, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), cursor.nextAsync().join());
    }

    @Test void sourceCloseFailureRemainsErrorNotEOF() throws Exception {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite("first"));
        RuntimeException cause = new IllegalArgumentException("producer failed");
        channel.close(cause);
        AsyncSequences.Cursor<String> cursor = AsyncSequences.fromChannel(channel);
        assertEquals(Optional.of("first"), cursor.nextAsync().get(3, TimeUnit.SECONDS));
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> cursor.nextAsync().get(3, TimeUnit.SECONDS));
        assertSame(cause, thrown.getCause());
        assertSame(cause, assertThrows(ExecutionException.class,
                () -> cursor.nextAsync().get(3, TimeUnit.SECONDS)).getCause());
    }

    @Test void cancelledPendingChannelReadDoesNotStealNextValue() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        OresFuture<Optional<Integer>> pending = cursor.nextAsync();
        assertFalse(pending.isDone());
        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);
        assertEquals(0, channel.pendingRegistrations());
        assertTrue(channel.tryWrite(99));
        assertEquals(Optional.of(99), cursor.nextAsync().get(3, TimeUnit.SECONDS));
        cursor.close();
        assertFalse(channel.isClosed(), "borrowed cursor must not close source");
    }

    @Test void concurrentNextRejectedWithoutConsumingSecondItem() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        OresFuture<Optional<Integer>> first = cursor.nextAsync();
        OresFuture<Optional<Integer>> second = cursor.nextAsync();
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, second::get).getCause());
        assertTrue(channel.tryWrite(13));
        assertEquals(Optional.of(13), first.get(3, TimeUnit.SECONDS));
        cursor.close();
    }

    @Test void borrowedCursorCloseDetachesPendingReadWithoutClosingChannel() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        OresFuture<Optional<Integer>> waiting = cursor.nextAsync();
        cursor.close();
        assertTrue(waiting.isCancelled());
        assertEquals(0, channel.pendingRegistrations());
        assertFalse(channel.isClosed());
        assertTrue(channel.tryWrite(5));
        assertEquals(Optional.of(5), channel.readAsync().join());
        assertEquals(Optional.empty(), cursor.nextAsync().join());
    }

    @Test void ownedCursorCloseClosesSourceButDoesNotRevokeBufferedValue() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryWrite("kept"));
        AsyncSequences.Cursor<String> cursor = AsyncSequences.owningChannel(channel);
        cursor.close();
        assertTrue(channel.isClosed());
        assertEquals(Optional.of("kept"), channel.tryRead());
        assertEquals(Optional.empty(), cursor.nextAsync().join());
    }

    @Test void futureCursorObservesOnceWithNoNewWork() {
        OresFuture<Integer> source = OresFuture.completed(18);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromFuture(source);
        assertEquals(Optional.of(18), cursor.nextAsync().join());
        assertEquals(Optional.empty(), cursor.nextAsync().join());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test void futureCursorDetachesOnCloseWithoutCancellingProducer() throws Exception {
        OresFuture<String> source = new OresFuture<>();
        AsyncSequences.Cursor<String> cursor = AsyncSequences.fromFuture(source);
        OresFuture<Optional<String>> next = cursor.nextAsync();
        assertEquals(1, source.pendingRuntimeWaiterCount());
        cursor.close();
        assertTrue(next.isCancelled());
        assertFalse(source.isCancelled());
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertTrue(source.completeFromRuntime("later"));
        assertEquals(Optional.empty(), cursor.nextAsync().join());
    }

    @Test void futureCancellationAndFailureStayDistinct() throws Exception {
        OresFuture<Integer> cancelled = new OresFuture<>();
        OresFuture<Integer> failed = new OresFuture<>();
        AsyncSequences.Cursor<Integer> cancelledCursor =
                AsyncSequences.fromFuture(cancelled);
        AsyncSequences.Cursor<Integer> failedCursor =
                AsyncSequences.fromFuture(failed);
        OresFuture<Optional<Integer>> cancellation = cancelledCursor.nextAsync();
        OresFuture<Optional<Integer>> error = failedCursor.nextAsync();
        IllegalArgumentException thrown = new IllegalArgumentException("genuine error");
        assertTrue(cancelled.cancel(false));
        assertTrue(failed.failFromRuntime(thrown));
        assertTrue(cancellation.isCancelled());
        assertSame(thrown, assertThrows(ExecutionException.class,
                () -> error.get(3, TimeUnit.SECONDS)).getCause());
        assertEquals(0, cancelled.pendingRuntimeWaiterCount());
        assertEquals(0, failed.pendingRuntimeWaiterCount());
    }

    @Test void voidFutureCannotBeMistakenForEndOfStream() {
        AsyncSequences.Cursor<Void> cursor =
                AsyncSequences.fromFuture(OresFuture.<Void>completed(null));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, cursor.nextAsync()::get)
                        .getCause());
    }

    @Test void streamItemsComposeWithExistingFutureAll() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        OresFuture<Optional<Integer>> fromChannel = cursor.nextAsync();
        OresFuture<Optional<Integer>> fromFuture =
                AsyncSequences.fromFuture(OresFuture.completed(21)).nextAsync();
        OresFuture<List<Optional<Integer>>> results =
                FutureCombinators.all(List.of(fromChannel, fromFuture));
        assertFalse(results.isDone());
        assertTrue(channel.tryWrite(8));
        assertEquals(List.of(Optional.of(8), Optional.of(21)),
                results.get(3, TimeUnit.SECONDS));
        cursor.close();
    }

    @Test void channelCompletionResumesOnAwaitingSchedulerNotProducerThread()
            throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
        AtomicReference<Thread> waiterCarrier = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(new OresScheduler.Task<>() {
                private int pc;
                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (pc++ == 0) return OresScheduler.await(cursor.nextAsync());
                    waiterCarrier.set(Thread.currentThread());
                    if (resume.failure() != null) {
                        throw new IllegalStateException("unexpected stream failure",
                                resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    Optional<Integer> next = (Optional<Integer>) resume.value();
                    return OresScheduler.done(next.orElseThrow());
                }
            });
            Thread producer = Thread.ofPlatform().start(() -> {
                producerThread.set(Thread.currentThread());
                channel.writeAsync(34);
            });
            producer.join(3_000);
            assertFalse(producer.isAlive());
            assertEquals(Integer.valueOf(34), result.get(3, TimeUnit.SECONDS));
            assertNotNull(waiterCarrier.get());
            assertNotSame(producerThread.get(), waiterCarrier.get(),
                    "source callback must not execute guest continuation inline");
        } finally {
            cursor.close();
        }
    }

    @Test void closeVsReadRegistrationRaceNeverRetainsChannelWaiters()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(channel);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<OresFuture<Optional<Integer>>> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    result.set(cursor.nextAsync());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            go.countDown();
            cursor.close();
            reader.join(3_000);
            assertFalse(reader.isAlive(), "cursor read registration stalled");
            assertNull(failure.get());
            assertNotNull(result.get());
            assertTrue(result.get().isDone());
            assertEquals(0, channel.pendingRegistrations());
            assertFalse(channel.isClosed(), "borrowed Channel must remain open");
        }
    }

    @Test void futureSourceSettlementVsCursorCloseDoesNotRetainListener()
            throws Exception {
        for (int round = 0; round < 128; round++) {
            OresFuture<Integer> source = new OresFuture<>();
            AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromFuture(source);
            OresFuture<Optional<Integer>> result = cursor.nextAsync();
            CountDownLatch go = new CountDownLatch(1);
            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    source.completeFromRuntime(15);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            go.countDown();
            cursor.close();
            producer.join(3_000);
            assertFalse(producer.isAlive());
            assertTrue(result.isDone());
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(source.isCancelled(), "observer close cannot revoke source");
        }
    }

    @Test void completedChannelReadMayReenterNextFromRuntimeListener() throws Exception {
        ChannelRuntime.Channel<Integer> source = new ChannelRuntime.Channel<>(1);
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromChannel(source);
        OresFuture<Optional<Integer>> first = cursor.nextAsync();
        AtomicReference<OresFuture<Optional<Integer>>> second = new AtomicReference<>();
        first.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            assertEquals(Optional.of(1), value);
            second.set(cursor.nextAsync());
        });
        assertTrue(source.tryWrite(1));
        assertNotNull(second.get());
        assertFalse(second.get().isDone());
        assertTrue(source.tryWrite(2));
        assertEquals(Optional.of(2), second.get().get(3, TimeUnit.SECONDS));
        cursor.close();
        assertEquals(0, source.pendingRegistrations());
    }

    @Test void completedFutureCursorMayObserveEOFFromTerminalListener() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        AsyncSequences.Cursor<Integer> cursor = AsyncSequences.fromFuture(source);
        OresFuture<Optional<Integer>> first = cursor.nextAsync();
        AtomicReference<OresFuture<Optional<Integer>>> next = new AtomicReference<>();
        first.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            assertEquals(Optional.of(5), value);
            next.set(cursor.nextAsync());
        });
        source.completeFromRuntime(5);
        assertNotNull(next.get());
        assertEquals(Optional.empty(), next.get().get(3, TimeUnit.SECONDS));
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }
}
