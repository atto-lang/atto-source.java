package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Atomicity, closure and no-retained-waiter guarantees of try readch/writech. */
@Timeout(10)
final class ImmediateChannelProbeRuntimeTest {
    @Test
    void falseWriteDoesNotConsumeOrRegisterAndCanBeRetried() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(41));
        assertFalse(channel.tryWrite(42));
        assertEquals(0, channel.pendingRegistrations());
        assertEquals(41, channel.tryRead().orElseThrow());
        assertTrue(channel.tryRead().isEmpty());
        assertEquals(0, channel.pendingRegistrations());
        assertTrue(channel.tryWrite(42));
        assertEquals(42, channel.tryRead().orElseThrow());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void immediateProbeDistinguishesEmptyFromClosedAfterDrain() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryRead().isEmpty());
        assertTrue(channel.tryWrite(9));
        channel.close();
        assertEquals(9, channel.tryRead().orElseThrow());
        assertThrows(RuntimeException.class, channel::tryRead);
        assertThrows(RuntimeException.class, () -> channel.tryWrite(10));
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void racingWritersAdmitExactlyOneAndRetainNoWaiters() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int value = i + 1;
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    if (channel.tryWrite(value)) accepted.incrementAndGet();
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    workerFailure.compareAndSet(null, failure);
                } finally {
                    done.countDown();
                }
            });
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        for (Thread writer : writers) {
            writer.join(2000);
            assertFalse(writer.isAlive(), "worker must exit without retaining a channel waiter");
        }
        assertEquals(null, workerFailure.get(), "worker exceptions must reach JUnit");
        assertEquals(1, accepted.get());
        assertTrue(channel.tryRead().isPresent());
        assertTrue(channel.tryRead().isEmpty());
        assertEquals(0, channel.pendingRegistrations());
    }
}
