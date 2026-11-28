package dev.oreslang.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Channel timeout races must be decided by selection commit, not timer order. */
final class TimedChannelRuntimeTest {
    @Test void anEmptyReadExpiresWithoutStealingALaterMessage() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var pending = channel.readAsyncWithin(Duration.ofMillis(25), timer);
            var failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(channel.tryWrite("untouched"));
            assertEquals("untouched", channel.tryRead().orElseThrow());
        }
    }

    @Test void aFullWriteExpiresWithoutDeliveringLater() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(1);
        assertTrue(channel.tryWrite("already-buffered"));
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var pending = channel.writeAsyncWithin(
                    "must-not-deliver", Duration.ofMillis(25), timer);
            var failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals("already-buffered", channel.tryRead().orElseThrow());
            assertTrue(channel.tryRead().isEmpty());
        }
    }

    @Test void zeroBudgetStillAllowsAnAlreadyCommittedReadOrWrite() throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            assertNull(channel.writeAsyncWithin(7, Duration.ZERO, timer).get(5, TimeUnit.SECONDS));
            assertEquals(7, channel.readAsyncWithin(Duration.ZERO, timer).get(5, TimeUnit.SECONDS));
            var pending = channel.readAsyncWithin(Duration.ZERO, timer);
            var failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
        }
    }

    @Test void settledRendezvousRemainsCommittedAfterDeadline() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(0);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var reader = channel.readAsyncWithin(Duration.ofMillis(100), timer);
            var writer = channel.writeAsyncWithin(
                    "paired", Duration.ofMillis(100), timer);
            assertEquals("paired", reader.get(5, TimeUnit.SECONDS));
            assertNull(writer.get(5, TimeUnit.SECONDS));
            Thread.sleep(125);
            assertEquals("paired", reader.join());
        }
    }

    @Test void rejectedTimerCannotLeaveAnUnboundedReadOrWrite() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(0);
        var timer = Executors.newSingleThreadScheduledExecutor();
        timer.shutdownNow();
        var read = channel.readAsyncWithin(Duration.ofSeconds(1), timer);
        var write = channel.writeAsyncWithin("value", Duration.ofSeconds(1), timer);
        // The read must be withdrawn before the write gets registered;
        // neither operation may commit following timer admission rejection.
        var readFailure = assertThrows(ExecutionException.class,
                () -> read.get(5, TimeUnit.SECONDS));
        var writeFailure = assertThrows(ExecutionException.class,
                () -> write.get(5, TimeUnit.SECONDS));
        assertTrue(readFailure.getCause().getMessage().contains("timer admission rejected"));
        assertTrue(writeFailure.getCause().getMessage().contains("timer admission rejected"));
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test void expiredReadCannotCommitWhenTimerThreadIsStalled() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(1);
        var unblockTimer = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.CountDownLatch(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            timer.execute(() -> {
                blocked.countDown();
                try {
                    unblockTimer.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            try {
                var expired = channel.readAsyncWithin(Duration.ofMillis(20), timer);
                Thread.sleep(50);
                assertTrue(channel.tryWrite("preserved"),
                        "a stale timed reader cannot consume a later message");
                var error = assertThrows(ExecutionException.class,
                        () -> expired.get(5, TimeUnit.SECONDS));
                assertInstanceOf(TimeoutException.class, error.getCause());
                assertEquals("preserved", channel.tryRead().orElseThrow());
            } finally {
                unblockTimer.countDown();
            }
        }
    }

    @Test void expiredWriteCannotRendezvousWhenTimerThreadIsStalled() throws Exception {
        var channel = new ChannelRuntime.Channel<String>(0);
        var unblockTimer = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.CountDownLatch(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            timer.execute(() -> {
                blocked.countDown();
                try {
                    unblockTimer.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            try {
                var expired = channel.writeAsyncWithin(
                        "stale", Duration.ofMillis(20), timer);
                Thread.sleep(50);
                var reader = channel.readAsync();
                assertFalse(reader.isDone(), "expired write must not satisfy a new reader");
                var error = assertThrows(ExecutionException.class,
                        () -> expired.get(5, TimeUnit.SECONDS));
                assertInstanceOf(TimeoutException.class, error.getCause());
                reader.cancel(false);
            } finally {
                unblockTimer.countDown();
            }
        }
    }

    @Test void negativeTimeoutIsRejectedBeforeChannelRegistration() {
        var channel = new ChannelRuntime.Channel<String>(1);
        assertTrue(channel.tryWrite("preserved"));
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            assertThrows(IllegalArgumentException.class,
                    () -> channel.readAsyncWithin(Duration.ofMillis(-1), timer));
            assertThrows(IllegalArgumentException.class,
                    () -> channel.writeAsyncWithin(
                            "ignored", Duration.ofMillis(-1), timer));
        }
        assertEquals("preserved", channel.tryRead().orElseThrow());
    }
}
