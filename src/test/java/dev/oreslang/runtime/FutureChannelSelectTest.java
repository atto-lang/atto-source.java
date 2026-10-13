package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void concurrentChannelWriteAndFutureSettlementChooseExactlyOneWinner() throws Exception {
        for (int round = 0; round < 64; round++) {
            var channel = new ChannelRuntime.Channel<Integer>(1);
            var producer = new OresFuture<Integer>();
            var selected = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.read(channel), ChannelRuntime.await(producer))
                    .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
            var go = new CountDownLatch(1);
            Thread writer = Thread.startVirtualThread(() -> {
                awaitStart(go);
                assertTrue(channel.tryWrite(71));
            });
            Thread resolver = Thread.startVirtualThread(() -> {
                awaitStart(go);
                assertTrue(producer.completeFromRuntime(82));
            });

            go.countDown();
            writer.join(5_000);
            resolver.join(5_000);
            assertFalse(writer.isAlive(), "writer stalled in round " + round);
            assertFalse(resolver.isAlive(), "resolver stalled in round " + round);
            var result = selected.get(5, TimeUnit.SECONDS);
            if (result.index() == 0) {
                assertEquals(71, result.value());
                assertTrue(channel.tryRead().isEmpty());
                assertEquals(82, producer.join());
            } else {
                assertEquals(1, result.index());
                assertEquals(82, result.value());
                assertEquals(71, channel.tryRead().orElseThrow());
            }
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    private static void awaitStart(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent select test gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent select test interrupted", interrupted);
        }
    }

    @Test
    void tooManySelectableDescriptorsFailWithoutConsumingAnyPayload() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertTrue(channel.tryWrite(91));
        var cases = new java.util.ArrayList<ChannelRuntime.SelectCase>();
        for (int i = 0; i < 257; i++) cases.add(ChannelRuntime.read(channel));
        assertThrows(ChannelRuntime.SelectAdmissionException.class,
                () -> new ChannelRuntime.SelectSet(cases));
        assertEquals(91, channel.tryRead().orElseThrow());
    }

    @Test
    void fullPendingQuotaCannotSuppressAnImmediatelyReadyFutureOrRead() {
        var pending = new java.util.ArrayList<OresFuture<ChannelRuntime.SelectResult>>();
        try {
            for (int i = 0; i < 4096; i++) {
                var channel = new ChannelRuntime.Channel<Integer>(0);
                pending.add(ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR));
            }
            var full = new ChannelRuntime.Channel<Integer>(0);
            var rejected = ChannelRuntime.SelectSet.of(ChannelRuntime.read(full)).selectAsync();
            var rejectedFailure = assertThrows(CompletionException.class, rejected::join);
            assertTrue(rejectedFailure.getCause()
                    instanceof ChannelRuntime.SelectAdmissionException);

            var readyFuture = ChannelRuntime.SelectSet.of(
                    ChannelRuntime.await(OresFuture.completed(99))).selectAsync();
            assertEquals(99, readyFuture.join().value());

            var buffered = new ChannelRuntime.Channel<Integer>(1);
            assertTrue(buffered.tryWrite(100));
            var readyRead = ChannelRuntime.SelectSet.of(ChannelRuntime.read(buffered)).selectAsync();
            assertEquals(100, readyRead.join().value());
        } finally {
            for (var waiter : pending) waiter.cancel(false);
        }
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
