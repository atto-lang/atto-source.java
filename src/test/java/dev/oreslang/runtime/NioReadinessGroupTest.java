package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
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
}
