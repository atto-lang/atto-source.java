package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** Future completion and channel transfer must share one atomic select winner. */
final class FutureChannelSelectTest {
    @Test
    void pendingFutureWakesHeterogeneousSelectWithoutConsumingLosingChannel() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var producer = new OresFuture<Integer>();
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(producer));

        var selected = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(selected.isDone());
        assertEquals(1, producer.pendingRuntimeWaiterCount());

        assertTrue(producer.completeFromRuntime(42));
        var result = selected.join();
        assertEquals(1, result.index());
        assertEquals(ChannelRuntime.SelectOperation.FUTURE, result.operation());
        assertEquals(42, result.value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());

        assertTrue(channel.tryWrite(7));
        assertEquals(7, channel.tryRead().orElseThrow());
    }

    @Test
    void channelWinnerDetachesFutureWaiterAndDoesNotCancelSharedProducer() {
        var channel = new ChannelRuntime.Channel<String>(1);
        var producer = new OresFuture<String>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(producer),
                ChannelRuntime.read(channel)).selectAsync();

        assertEquals(1, producer.pendingRuntimeWaiterCount());
        assertTrue(channel.tryWrite("mail"));

        assertEquals(1, selected.join().index());
        assertEquals("mail", selected.join().value());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertFalse(producer.isDone());

        assertTrue(producer.completeFromRuntime("later"));
        assertEquals("mail", selected.join().value());
    }

    @Test
    void cancelledSelectionDetachesAllFutureWaitersWithoutCancellingSources() {
        var first = new OresFuture<Integer>();
        var second = new OresFuture<Integer>();
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(first), ChannelRuntime.await(second)).selectAsync();

        assertEquals(1, first.pendingRuntimeWaiterCount());
        assertEquals(1, second.pendingRuntimeWaiterCount());
        assertTrue(selected.cancel(false));
        assertTrue(selected.isCancelled());
        assertThrows(CancellationException.class, selected::join);
        assertEquals(0, first.pendingRuntimeWaiterCount());
        assertEquals(0, second.pendingRuntimeWaiterCount());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
    }

    @Test
    void priorityAndFairnessApplyAcrossAlreadyReadyChannelsAndFutures() {
        var channel = new ChannelRuntime.Channel<Integer>(2);
        assertTrue(channel.tryWrite(11));
        var future = OresFuture.completed(22);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel), ChannelRuntime.await(future));

        var first = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(0, first.index());
        assertEquals(11, first.value());

        assertTrue(channel.tryWrite(33));
        var second = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, second.index());
        assertEquals(22, second.value());
        assertEquals(33, channel.tryRead().orElseThrow());
    }

    @Test
    void settledFailedFutureIsSelectableButPreservesItsFailure() {
        var failure = new IllegalStateException("upstream failed");
        var future = OresFuture.<Integer>failed(failure);
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var selected = ChannelRuntime.SelectSet.of(
                ChannelRuntime.await(future), ChannelRuntime.read(channel))
                .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        var thrown = assertThrows(CompletionException.class, selected::join);
        assertEquals(failure, thrown.getCause());
        assertFalse(channel.tryRead().isPresent());
    }

    @Test
    void immediateProbeOnPendingFutureRegistersNoWaiter() {
        var future = new OresFuture<Integer>();
        var set = ChannelRuntime.SelectSet.of(ChannelRuntime.await(future));

        assertTrue(set.trySelect().isEmpty());
        assertEquals(0, future.pendingRuntimeWaiterCount());

        assertTrue(future.completeFromRuntime(4));
        assertEquals(4, set.trySelect().orElseThrow().value());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }
}
