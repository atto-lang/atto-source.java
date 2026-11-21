package dev.oreslang;

import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded stream transport interleavings. These deliberately exercise the
 * runtime coordinator without relying on Graal bootstrap or private CI.
 */
final class StreamConcurrencyTest {
    private static void assertClosed(org.junit.jupiter.api.function.Executable action) {
        CompletionException failure = assertThrows(CompletionException.class, action);
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class, failure.getCause());
    }

    @Test
    void boundedBufferNeverSilentlyOverwritesAndDrainSurvivesClose() {
        OresStream<Integer> stream = new OresStream<>(2);
        assertTrue(stream.channel().tryWrite(1));
        assertTrue(stream.channel().tryWrite(2));
        assertFalse(stream.channel().tryWrite(3));
        assertEquals(2, stream.channel().size());
        stream.close();
        assertEquals(1, stream.channel().tryRead().orElseThrow().intValue());
        assertEquals(2, stream.channel().tryRead().orElseThrow().intValue());
        assertEquals(0, stream.channel().size());
        assertClosed(() -> stream.channel().readAsync().join());
        assertClosed(() -> stream.channel().writeAsync(4).join());
    }

    @Test
    void producerFailureCloseDrainsCommittedBufferAndPreservesCause() {
        OresStream<Integer> stream = new OresStream<>(1);
        IllegalStateException producerFailure = new IllegalStateException("producer failed");
        assertTrue(stream.channel().tryWrite(71));
        stream.close(producerFailure);
        assertEquals(71, stream.channel().readAsync().join().intValue());
        CompletionException failure = assertThrows(
                CompletionException.class, () -> stream.channel().readAsync().join());
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class, failure.getCause());
        assertSame(producerFailure, failure.getCause().getCause());
        stream.close(); // idempotent: the first close must retain failure authority
        CompletionException second = assertThrows(
                CompletionException.class, () -> stream.channel().readAsync().join());
        assertSame(producerFailure, second.getCause().getCause());
    }

    @Test
    void producerFailureWakesPendingReaderWithOriginalCause() {
        OresStream<Integer> stream = new OresStream<>(1);
        OresFuture<Integer> reader = stream.channel().readAsync();
        assertFalse(reader.isDone());
        IllegalArgumentException cause = new IllegalArgumentException("producer aborted");
        stream.close(cause);
        CompletionException failure = assertThrows(CompletionException.class, reader::join);
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class, failure.getCause());
        assertSame(cause, failure.getCause().getCause());
        assertEquals(0, stream.channel().pendingRegistrations());
    }

    @Test
    void cancellingPendingReaderReleasesRegistrationWithoutClosingStream() {
        OresStream<Integer> stream = new OresStream<>(1);
        OresFuture<Integer> reader = stream.channel().readAsync();
        assertFalse(reader.isDone());
        assertTrue(stream.channel().pendingRegistrations() > 0);
        assertTrue(reader.cancel(false));
        assertTrue(reader.isCancelled());
        assertEquals(0, stream.channel().pendingRegistrations());
        assertFalse(stream.isClosed());
        assertTrue(stream.channel().tryWrite(8));
        assertEquals(8, stream.channel().tryRead().orElseThrow().intValue());
        stream.close();
    }

    @Test
    void cancellingPendingWriterDoesNotEnqueueItsPayload() {
        OresStream<Integer> stream = new OresStream<>(1);
        try {
            assertTrue(stream.channel().tryWrite(11));
            OresFuture<Void> writer = stream.channel().writeAsync(12);
            assertFalse(writer.isDone());
            assertTrue(stream.channel().pendingRegistrations() > 0);
            assertTrue(writer.cancel(false));
            assertTrue(writer.isCancelled());
            assertEquals(0, stream.channel().pendingRegistrations());
            assertEquals(11, stream.channel().tryRead().orElseThrow().intValue());
            assertTrue(stream.channel().tryRead().isEmpty());
            assertTrue(stream.channel().tryWrite(13));
            assertEquals(13, stream.channel().tryRead().orElseThrow().intValue());
        } finally {
            stream.close();
        }
    }

    @Test
    void closingWithPendingWriteFailsWaiterButPreservesCommittedData() {
        OresStream<Integer> stream = new OresStream<>(1);
        assertTrue(stream.channel().tryWrite(10));
        OresFuture<Void> blocked = stream.channel().writeAsync(11);
        assertFalse(blocked.isDone());
        assertTrue(stream.channel().pendingRegistrations() > 0);
        stream.close();
        assertClosed(blocked::join);
        assertEquals(0, stream.channel().pendingRegistrations());
        assertEquals(10, stream.channel().tryRead().orElseThrow().intValue());
    }

    @Test
    void closeAndReaderCancellationRaceNeverLeavesPendingRegistration() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 64; round++) {
                OresStream<Integer> stream = new OresStream<>(1);
                OresFuture<Integer> waiter = stream.channel().readAsync();
                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                Future<?> closing = workers.submit(() -> {
                    try {
                        start.await();
                        stream.close();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                });
                Future<?> cancelling = workers.submit(() -> {
                    try {
                        start.await();
                        waiter.cancel(false);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                });
                start.countDown();
                closing.get(5, TimeUnit.SECONDS);
                cancelling.get(5, TimeUnit.SECONDS);
                assertTrue(stream.isClosed());
                assertTrue(waiter.isDone());
                assertEquals(0, stream.channel().pendingRegistrations());
                final int item = round;
                assertClosed(() -> stream.channel().tryWrite(item));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void closeAndPendingWriterCancellationRaceCannotCommitRejectedValue() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 64; round++) {
                OresStream<Integer> stream = new OresStream<>(1);
                assertTrue(stream.channel().tryWrite(99));
                OresFuture<Void> pending = stream.channel().writeAsync(100 + round);
                assertFalse(pending.isDone());
                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                Future<?> closing = workers.submit(() -> {
                    try {
                        start.await();
                        stream.close();
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                });
                Future<?> cancelling = workers.submit(() -> {
                    try {
                        start.await();
                        pending.cancel(false);
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                });
                start.countDown();
                closing.get(5, TimeUnit.SECONDS);
                cancelling.get(5, TimeUnit.SECONDS);
                assertTrue(stream.isClosed());
                assertTrue(pending.isDone());
                assertEquals(0, stream.channel().pendingRegistrations(),
                        "pending writer remained registered after close/cancel");
                assertEquals(99, stream.channel().tryRead().orElseThrow().intValue());
                if (!pending.isCancelled()) {
                    assertClosed(pending::join);
                }
                assertClosed(() -> stream.channel().readAsync().join());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentConsumersDeliverEveryCommittedItemExactlyOnce() throws Exception {
        final int count = 256;
        OresStream<Integer> stream = new OresStream<>(8);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> producer = workers.submit(() -> {
                for (int i = 0; i < count; i++) {
                    stream.channel().writeAsync(i).join();
                }
                stream.close();
            });
            List<Future<List<Integer>>> consumers = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                consumers.add(workers.submit(() -> {
                    List<Integer> received = new ArrayList<>();
                    for (;;) {
                        try {
                            received.add(stream.channel().readAsync().join());
                        } catch (CompletionException closed) {
                            if (closed.getCause() instanceof ChannelRuntime.ChannelClosedException) {
                                return received;
                            }
                            throw closed;
                        }
                    }
                }));
            }
            producer.get(20, TimeUnit.SECONDS);
            Set<Integer> seen = new HashSet<>();
            int delivered = 0;
            for (Future<List<Integer>> consumer : consumers) {
                List<Integer> items = consumer.get(20, TimeUnit.SECONDS);
                delivered += items.size();
                for (Integer item : items) {
                    assertTrue(seen.add(item), "duplicate stream item " + item);
                }
            }
            assertEquals(count, delivered);
            for (int i = 0; i < count; i++) assertTrue(seen.contains(i));
            assertEquals(0, stream.channel().pendingRegistrations());
        } finally {
            stream.close();
            workers.shutdownNow();
        }
    }

    @Test
    void producerFailureCloseRejectsBlockedWriterAndRetainsCommittedItem() {
        OresStream<Integer> stream = new OresStream<>(1);
        IllegalStateException producerFailure =
                new IllegalStateException("producer failed during backpressure");
        assertTrue(stream.channel().tryWrite(42));
        OresFuture<Void> blockedWriter = stream.channel().writeAsync(43);
        assertFalse(blockedWriter.isDone());
        assertTrue(stream.channel().pendingRegistrations() > 0);

        stream.close(producerFailure);

        CompletionException writerFailure = assertThrows(
                CompletionException.class, blockedWriter::join);
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                writerFailure.getCause());
        assertSame(producerFailure, writerFailure.getCause().getCause());
        assertEquals(0, stream.channel().pendingRegistrations());
        assertEquals(42, stream.channel().tryRead().orElseThrow().intValue());
        CompletionException readerFailure = assertThrows(
                CompletionException.class, () -> stream.channel().readAsync().join());
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                readerFailure.getCause());
        assertSame(producerFailure, readerFailure.getCause().getCause());
        assertEquals(0, stream.channel().size(),
                "a rejected blocked write must not become a committed item");
    }

    @Test
    void normalCloseCannotBeRewrittenAsProducerFailureAfterDrain() {
        OresStream<Integer> stream = new OresStream<>(1);
        assertTrue(stream.channel().tryWrite(77));
        stream.close();
        stream.close(new IllegalStateException("late producer failure"));
        assertEquals(77, stream.channel().tryRead().orElseThrow().intValue());
        CompletionException closed = assertThrows(
                CompletionException.class, () -> stream.channel().readAsync().join());
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class, closed.getCause());
        assertNull(closed.getCause().getCause(),
                "a second close must not replace the original terminal outcome");
        assertEquals(0, stream.channel().pendingRegistrations());
    }

}
