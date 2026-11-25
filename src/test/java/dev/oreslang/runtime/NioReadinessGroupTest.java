package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class NioReadinessGroupTest {
    @Test
    void constructionIsColdAndInvalidWatchDoesNotStartReactor() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup(2, 4);
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             Pipe.SinkChannel sink = pipe.sink()) {
            assertEquals(0, group.reactorCount());
            OresFuture<Integer> invalid = group.watch(source, SelectionKey.OP_WRITE);
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> invalid.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(0, group.reactorCount());
            assertEquals(0, group.pendingCount());
        } finally {
            group.close();
            assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneShotReadinessStartsLazilyAndRetiresExactlyOnce() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup(4, 8);
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             Pipe.SinkChannel sink = pipe.sink()) {
            OresFuture<Integer> ready = group.watch(source, SelectionKey.OP_READ);
            assertEquals(1, group.pendingCount());

            sink.write(ByteBuffer.wrap(new byte[] { 1 }));
            int mask = ready.get(5, TimeUnit.SECONDS);

            assertNotEquals(0, mask & SelectionKey.OP_READ);
            assertEquals(0, group.pendingCount());
            assertEquals(1, group.reactorCount());
        } finally {
            group.close();
            assertTrue(group.awaitStopped(5, TimeUnit.SECONDS));
        }
        assertEquals(0, group.reactorCount());
    }

    @Test
    void duplicateChannelAndGlobalCapacityFailClosed() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup(1, 4);
        Pipe first = Pipe.open();
        Pipe second = Pipe.open();
        try (Pipe.SourceChannel firstSource = first.source();
             Pipe.SinkChannel firstSink = first.sink();
             Pipe.SourceChannel secondSource = second.source();
             Pipe.SinkChannel secondSink = second.sink()) {
            OresFuture<Integer> admitted =
                    group.watch(firstSource, SelectionKey.OP_READ);

            OresFuture<Integer> duplicate =
                    group.watch(firstSource, SelectionKey.OP_READ);
            ExecutionException duplicateFailure = assertThrows(
                    ExecutionException.class,
                    () -> duplicate.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,
                    duplicateFailure.getCause());

            OresFuture<Integer> saturated =
                    group.watch(secondSource, SelectionKey.OP_READ);
            ExecutionException capacityFailure = assertThrows(
                    ExecutionException.class,
                    () -> saturated.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class,
                    capacityFailure.getCause());

            assertTrue(admitted.cancel(false));
            assertTrue(admitted.isCancelled());
            assertTrue(group.awaitIdle(5, TimeUnit.SECONDS),
                    "cancellation must retire channel ownership");
            assertEquals(0, group.pendingCount());

            OresFuture<Integer> rearmed =
                    group.watch(firstSource, SelectionKey.OP_READ);
            assertFalse(rearmed.isDone(),
                    "the same channel must be reusable after cancellation retirement");
            assertTrue(rearmed.cancel(false));
            assertTrue(group.awaitIdle(5, TimeUnit.SECONDS));
        } finally {
            group.close();
            assertTrue(group.awaitStopped(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeFailsPendingWatchAndPhysicallyStopsReactor() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup(2, 4);
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             Pipe.SinkChannel sink = pipe.sink()) {
            OresFuture<Integer> pending =
                    group.watch(source, SelectionKey.OP_READ);

            group.close();
            assertTrue(group.awaitStopped(5, TimeUnit.SECONDS));
            assertEquals(0, group.pendingCount());
            assertEquals(0, group.reactorCount());

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> pending.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class,
                    failure.getCause());

            OresFuture<Integer> afterClose =
                    group.watch(source, SelectionKey.OP_READ);
            ExecutionException closedFailure = assertThrows(
                    ExecutionException.class,
                    () -> afterClose.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class,
                    closedFailure.getCause());
        } finally {
            group.close();
        }
    }

    @Test
    void duplexInterestsRetireIndependentlyAndTimeoutAllowsRearm() throws Exception {
        NioReadinessGroup group = new NioReadinessGroup();
        try (group; ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel server = listener.accept()) {
                OresFuture<Integer> read = group.watchDirection(server, SelectionKey.OP_READ, 0);
                OresFuture<Integer> write = group.watchDirection(server, SelectionKey.OP_WRITE, 0);
                assertEquals(SelectionKey.OP_WRITE, write.get(5, TimeUnit.SECONDS));
                assertFalse(read.isDone(), "writable readiness must leave a read armed");
                client.write(ByteBuffer.wrap(new byte[] { 1 }));
                assertEquals(SelectionKey.OP_READ, read.get(5, TimeUnit.SECONDS));
                assertEquals(1, server.read(ByteBuffer.allocate(1)));

                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
                OresFuture<Integer> timed = group.watchDirection(server, SelectionKey.OP_READ, deadline);
                var expired = assertThrows(ExecutionException.class, () -> timed.get(5, TimeUnit.SECONDS));
                assertInstanceOf(InterruptedByTimeoutException.class, expired.getCause());
                OresFuture<Integer> rearmed = group.watchDirection(server, SelectionKey.OP_READ, 0);
                client.write(ByteBuffer.wrap(new byte[] { 2 }));
                assertEquals(SelectionKey.OP_READ, rearmed.get(5, TimeUnit.SECONDS));
            }
        } finally {
            assertTrue(group.awaitStopped(5, TimeUnit.SECONDS));
        }
    }
}
