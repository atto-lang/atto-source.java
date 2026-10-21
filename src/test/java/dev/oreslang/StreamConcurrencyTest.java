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
        assertEquals(1, stream.channel().tryRead().orElseThrow());
        assertEquals(2, stream.channel().tryRead().orElseThrow());
        assertEquals(0, stream.channel().size());
        assertClosed(() -> stream.channel().readAsync().join());
        assertClosed(() -> stream.channel().writeAsync(4).join());
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
        assertEquals(8, stream.channel().tryRead().orElseThrow());
        stream.close();
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
        assertEquals(10, stream.channel().tryRead().orElseThrow());
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
}
