package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class OutboundHttpBodyPipeTest {
    private static OutboundHttpRequest.BodyPipe pipe() {
        return new OutboundHttpRequest.BodyPipe(System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }
    private static final OutboundHttpRequest.Owner ROOT = new OutboundHttpRequest.Owner(null, null);

    @Test void producerWriteLargerThanQueueBudgetMakesProgress() throws Exception {
        var pipe = pipe();
        byte[] source = new byte[131072];
        new java.util.Random(51).nextBytes(source);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                pipe.write(source, 0, source.length);
                pipe.finish();
                return null;
            });
            var collected = new ByteArrayOutputStream();
            try {
                while (true) {
                    try (var chunk = pipe.read(ROOT, 8192, OresBytes::fromDetached).get(5, TimeUnit.SECONDS)) {
                        if (chunk.size() == 0) break;
                        collected.writeBytes(chunk.toArrayCopy());
                    }
                }
                writer.get(5, TimeUnit.SECONDS);
                assertArrayEquals(source, collected.toByteArray());
            } finally { pipe.cancel(); }
        }
    }

    @Test void cancelledPendingReadDoesNotConsumeNextChunk() throws Exception {
        var pipe = pipe();
        var cancelled = pipe.read(ROOT, 3, OresBytes::fromDetached);
        assertTrue(cancelled.cancel(false));
        var replacement = pipe.read(ROOT, 3, OresBytes::fromDetached);
        pipe.write(new byte[] {0, (byte) 255, 42}, 0, 3);
        try (var bytes = replacement.get(2, TimeUnit.SECONDS)) {
            assertArrayEquals(new byte[] {0, (byte) 255, 42}, bytes.toArrayCopy());
        }
        assertTrue(cancelled.isCancelled());
    }

    @Test void claimedDeliveryRejectsCancellationAndSecondPendingReader() throws Exception {
        var pipe = pipe();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pending = pipe.read(ROOT, 1, raw -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("delivery not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return OresBytes.fromDetached(raw);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> { pipe.write(new byte[] {7, 8}, 0, 2); return null; });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(pending.cancel(false));
                assertThrows(IllegalStateException.class, () -> pipe.read(ROOT, 1, raw -> raw));
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            try (var first = pending.get(2, TimeUnit.SECONDS);
                 var second = pipe.read(ROOT, 1, OresBytes::fromDetached).get(2, TimeUnit.SECONDS)) {
                assertEquals(7, first.byteAt(0));
                assertEquals(8, second.byteAt(0));
            }
        } finally { pipe.cancel(); }
    }

    @Test void explicitCloseAfterEofRevokesUnreadBufferedBytes() throws Exception {
        var pipe = pipe();
        pipe.write(new byte[] {1, 2, 3}, 0, 3);
        pipe.finish();
        pipe.cancel();
        var result = pipe.read(ROOT, 3, OresBytes::fromDetached);
        assertThrows(Exception.class, () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(result.isDone());
    }

    @Test void boundsAndSinglePendingReadApplyAcrossRepresentations() {
        var pipe = pipe();
        assertThrows(IllegalArgumentException.class, () -> pipe.read(ROOT, 0, raw -> raw));
        assertThrows(IllegalArgumentException.class, () -> pipe.read(ROOT, 65537, raw -> raw));
        var pending = pipe.read(ROOT, 1, raw -> raw);
        assertThrows(IllegalStateException.class, () -> pipe.read(ROOT, 1, OresBytes::fromDetached));
        pipe.cancel();
        assertTrue(pending.isCancelled());
    }
}
