package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A transformed/select-keyed Future must inherit its source's cancellation
 * arbitration. After a channel winner has committed, cancelling an outer
 * Future cannot erase the result of an already-consumed operation.
 */
@Timeout(20)
final class SelectMappedCancellationArbitrationTest {
    @Test
    void sourceCommitDuringSlowMappingRejectsOuterCancellation() throws Exception {
        var source = new OresFuture<ChannelRuntime.SelectResult>();
        var mapperEntered = new CountDownLatch(1);
        var allowMappingToReturn = new CountDownLatch(1);
        var mapped = ChannelRuntime.mapSelection(source, selected -> {
            mapperEntered.countDown();
            try {
                if (!allowMappingToReturn.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("mapper release timed out");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError("mapper interrupted", interruption);
            }
            return (Integer) selected.value();
        });

        var worker = Executors.newSingleThreadExecutor();
        try {
            var published = worker.submit(() ->
                    source.completeFromRuntime(new ChannelRuntime.SelectResult(
                            0, ChannelRuntime.SelectOperation.READ, 71)));
            assertTrue(mapperEntered.await(5, TimeUnit.SECONDS),
                    "source terminal claim must precede the mapper callback");
            assertTrue(source.isDone());
            assertFalse(mapped.isDone(), "mapped publication is deliberately paused");
            assertFalse(mapped.cancel(false),
                    "source already committed; wrapper must not claim cancellation");
            assertFalse(mapped.isCancelled());
            allowMappingToReturn.countDown();
            assertTrue(published.get(5, TimeUnit.SECONDS));
            assertEquals(71, mapped.get(5, TimeUnit.SECONDS));
        } finally {
            allowMappingToReturn.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void cancellingPendingMappingAtomicallyCancelsSource() {
        var source = new OresFuture<ChannelRuntime.SelectResult>();
        var mapped = ChannelRuntime.mapSelection(source,
                selected -> (Integer) selected.value());
        assertTrue(mapped.cancel(false), "uncommitted source is cancellable");
        assertTrue(source.isCancelled(),
                "outer cancellation must revoke the actual select, not just its view");
        assertTrue(mapped.isCancelled());
        assertThrows(CancellationException.class, mapped::join);
        assertFalse(source.completeFromRuntime(new ChannelRuntime.SelectResult(
                0, ChannelRuntime.SelectOperation.READ, 1)));
    }

    @Test
    void bufferedWriteCannotBeCancelledAfterValueIsCommitted() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var written = channel.writeAsync(92);
        assertTrue(written.isDone(), "free buffer must commit before returning");
        assertFalse(written.cancel(false),
                "cancel after a successful write must not lie about consumed capacity");
        assertNull(written.join());
        assertEquals(92, channel.tryRead().orElseThrow());
    }

    @Test
    void keyedSelectCancellationVsRendezvousHasExactlyOneOwner() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 128; round++) {
                var channel = new ChannelRuntime.Channel<Integer>(0);
                var plan = new ChannelRuntime.DynamicSelectPlan<String>();
                plan.put("input", ChannelRuntime.read(channel));
                var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
                var start = new CountDownLatch(1);
                var writing = workers.submit(() -> {
                    start.await();
                    return channel.writeAsync(77);
                });
                var cancelling = workers.submit(() -> {
                    start.await();
                    return pending.cancel(false);
                });
                start.countDown();
                var outbound = writing.get(5, TimeUnit.SECONDS);
                boolean cancelled = cancelling.get(5, TimeUnit.SECONDS);
                channel.close();
                assertTrue(pending.isDone());
                assertTrue(outbound.isDone());
                if (cancelled) {
                    assertTrue(pending.isCancelled());
                    assertThrows(CancellationException.class, pending::join);
                    assertThrows(java.util.concurrent.CompletionException.class,
                            outbound::join,
                            "writer may not commit after mapped read cancellation");
                } else {
                    assertEquals("input", pending.join().key());
                    assertEquals(77, pending.join().selection().value());
                    assertNull(outbound.join());
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancellationOfPendingReadRevokesRendezvousArm() {
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var pendingRead = channel.readAsync();
        assertFalse(pendingRead.isDone());
        assertTrue(pendingRead.cancel(false));
        assertThrows(CancellationException.class, pendingRead::join);
        assertFalse(channel.tryWrite(43),
                "a cancelled read must not consume a later rendezvous send");
    }
}
