package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpTextBodySubscriberTest {
    private static final class Subscription implements Flow.Subscription {
        int demand;
        int cancellations;
        @Override public void request(long amount) { demand += (int) amount; }
        @Override public void cancel() { cancellations++; }
    }

    @Test void exactUtf8BoundaryAcceptsMultipleChunksAndCompletesOnce() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        var target = new BoundedHttpTextBodySubscriber(euro.length, -1L);
        var subscription = new Subscription();
        target.onSubscribe(subscription);
        target.onNext(List.of(ByteBuffer.wrap(euro, 0, 1)));
        target.onNext(List.of(ByteBuffer.wrap(euro, 1, 2)));
        target.onComplete();
        target.onComplete();
        assertEquals("€", target.getBody().toCompletableFuture().join());
        assertEquals(0, subscription.cancellations);
    }

    @Test void largeDeclaredResponseIsRejectedBeforeDemand() {
        var target = new BoundedHttpTextBodySubscriber(4, 5L);
        var subscription = new Subscription();
        target.onSubscribe(subscription);
        assertEquals(0, subscription.demand);
        assertEquals(1, subscription.cancellations);
        assertThrows(CompletionException.class, () -> target.getBody().toCompletableFuture().join());
    }

    @Test void chunkedResponseCannotExceedBoundEvenWithoutContentLength() {
        var target = new BoundedHttpTextBodySubscriber(4, -1L);
        var subscription = new Subscription();
        target.onSubscribe(subscription);
        target.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        target.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));
        target.onComplete();
        assertEquals(1, subscription.cancellations);
        assertThrows(CompletionException.class, () -> target.getBody().toCompletableFuture().join());
    }

    @Test void multipleBuffersCannotBypassSharedByteLimit() {
        var target = new BoundedHttpTextBodySubscriber(3, 3L);
        var subscription = new Subscription();
        target.onSubscribe(subscription);
        target.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        assertEquals(1, subscription.cancellations);
        assertThrows(CompletionException.class, () -> target.getBody().toCompletableFuture().join());
    }

    @Test void transportFailureCompletesExceptionalWithoutConsumingMoreData() {
        var target = new BoundedHttpTextBodySubscriber(3, -1L);
        var subscription = new Subscription();
        target.onSubscribe(subscription);
        target.onError(new IllegalStateException("broken transport"));
        target.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertThrows(CompletionException.class, () -> target.getBody().toCompletableFuture().join());
    }
}
