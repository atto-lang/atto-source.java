package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OresObservableChannelInteropTest {
    @Test
    void fromChannelMapsValuesAndCloseToComplete() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
        channel.tryWrite(1);
        channel.tryWrite(2);
        channel.close();

        OresSubscription<Integer> subscription = OresObservable.from(channel).subscribe();

        assertEquals(1, subscription.next().join().value());
        assertEquals(2, subscription.next().join().value());
        assertTrue(subscription.next().join().isComplete());
        assertTrue(subscription.isTerminated());
    }

    @Test
    void cancellingChannelObservableUnregistersPendingRead() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresSubscription<Integer> subscription = OresObservable.from(channel).subscribe();

        OresFuture<OresNotification<Integer>> pending = subscription.next();
        assertFalse(pending.isDone());
        assertTrue(subscription.cancel());

        channel.writeAsync(42).join();
        assertThrowsCancellation(pending);
        assertEquals(42, channel.readAsync().join());
    }

    @Test
    void channelProviderIsColdAndOwnsOneChannelPerSubscription() {
        AtomicInteger calls = new AtomicInteger();
        List<ChannelRuntime.Channel<Integer>> created = new java.util.ArrayList<>();

        OresObservable<Integer> observable = OresObservable.fromChProvider(() -> {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            created.add(channel);
            calls.incrementAndGet();
            return channel;
        });

        assertEquals(0, calls.get());

        OresSubscription<Integer> first = observable.subscribe();
        OresSubscription<Integer> second = observable.subscribe();
        assertEquals(2, calls.get());
        assertTrue(created.get(0) != created.get(1));

        OresFuture<OresNotification<Integer>> firstPending = first.next();
        OresFuture<OresNotification<Integer>> secondPending = second.next();
        created.get(0).writeAsync(10).join();
        created.get(1).writeAsync(20).join();

        assertEquals(10, firstPending.join().value());
        assertEquals(20, secondPending.join().value());

        first.cancel();
        second.cancel();
        assertTrue(created.get(0).isClosed());
        assertTrue(created.get(1).isClosed());
    }

    @Test
    void channelProviderClosesOwnedChannelAfterSourceCompletion() {
        AtomicInteger calls = new AtomicInteger();
        List<ChannelRuntime.Channel<Integer>> created = new java.util.ArrayList<>();

        OresObservable<Integer> observable = OresObservable.fromChProvider(() -> {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
            created.add(channel);
            calls.incrementAndGet();
            channel.tryWrite(7);
            channel.close();
            return channel;
        });

        OresSubscription<Integer> subscription = observable.subscribe();
        assertEquals(7, subscription.next().join().value());
        assertTrue(subscription.next().join().isComplete());
        assertEquals(1, calls.get());
        assertTrue(created.get(0).isClosed());
    }

    @Test
    void futureToObservableAndBackUsesNativeFuture() {
        OresFuture<Integer> future = new OresFuture<>();
        OresObservable<Integer> observable = OresObservable.fromFuture(future);

        OresFuture<Integer> first = observable.first();
        assertFalse(first.isDone());

        future.completeFromRuntime(7);

        assertEquals(7, first.join());
    }

    @Test
    void channelMergeUsesNativeSelectAndPreservesSourceOwnership() {
        ChannelRuntime.Channel<String> left = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> right = new ChannelRuntime.Channel<>(1);
        left.tryWrite("left");
        right.tryWrite("right");

        OresObservable<String> merged =
                OresObservable.merge(OresObservable.from(left), OresObservable.from(right));

        OresSubscription<String> subscription = merged.subscribe();
        String first = subscription.next().join().value();
        String second = subscription.next().join().value();

        assertTrue(List.of(first, second).containsAll(List.of("left", "right")));
        assertEquals(0, left.size());
        assertEquals(0, right.size());
    }

    @Test
    void observableToChannelUsesNativeWriteBackpressure() {
        ChannelRuntime.Channel<Integer> output = new ChannelRuntime.Channel<>(1);
        OresObservableChannelBridge<Integer> bridge =
                new OresObservableChannelBridge<>(
                        OresObservable.fromValues(List.of(1, 2, 3)),
                        output);

        OresFuture<Void> completion = bridge.start();
        assertEquals(1, output.readAsync().join());
        assertEquals(2, output.readAsync().join());
        assertEquals(3, output.readAsync().join());

        assertTrue(completion.isDone());
        assertFalse(completion.isCancelled());
        assertTrue(output.isClosed());
    }

    private static void assertThrowsCancellation(OresFuture<?> future) {
        try {
            future.join();
            throw new AssertionError("expected cancellation");
        } catch (CancellationException expected) {
            // expected
        }
    }
}
