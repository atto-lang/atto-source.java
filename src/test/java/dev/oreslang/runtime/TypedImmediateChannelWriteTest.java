package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves typed immediate write does not lose an unadmitted owned payload. */
@Timeout(10)
final class TypedImmediateChannelWriteTest {
    private record Payload(int sequence) { }

    @Test
    void acceptedFullAndClosedAreDistinctAndRejectedPayloadIsIdentical() {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        Payload first = new Payload(1);
        Payload second = new Payload(2);
        Payload afterClose = new Payload(3);

        assertInstanceOf(ChannelRuntime.TryWriteResult.Accepted.class, ch.tryWriteResult(first));
        var full = assertInstanceOf(ChannelRuntime.TryWriteResult.Full.class,
                ch.tryWriteResult(second));
        assertSame(second, full.value());
        assertEquals(0, ch.pendingRegistrations());

        ch.close();
        var closed = assertInstanceOf(ChannelRuntime.TryWriteResult.Closed.class,
                ch.tryWriteResult(afterClose));
        assertSame(afterClose, closed.value());
        assertSame(first, ch.tryRead().orElseThrow());
        assertEquals(0, ch.pendingRegistrations());
        assertThrows(RuntimeException.class, ch::tryRead);
    }

    @Test
    void rejectionCanBeRetriedThroughWriteOnlyCapability() {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        var writer = ch.input();
        Payload first = new Payload(1);
        Payload retried = new Payload(2);
        assertInstanceOf(ChannelRuntime.TryWriteResult.Accepted.class, writer.tryWriteResult(first));
        var full = assertInstanceOf(ChannelRuntime.TryWriteResult.Full.class,
                writer.tryWriteResult(retried));
        assertSame(retried, full.value());

        assertSame(first, ch.tryRead().orElseThrow());
        assertInstanceOf(ChannelRuntime.TryWriteResult.Accepted.class,
                writer.tryWriteResult((Payload) full.value()));
        assertSame(retried, ch.tryRead().orElseThrow());
        assertTrue(ch.tryRead().isEmpty());
        assertEquals(0, ch.pendingRegistrations());
    }

    @Test
    void rendezvousUnreadyReturnsFullAndClosedWinsAfterClose() {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(0);
        Payload payload = new Payload(9);
        var full = assertInstanceOf(ChannelRuntime.TryWriteResult.Full.class,
                ch.tryWriteResult(payload));
        assertSame(payload, full.value());
        assertEquals(0, ch.pendingRegistrations());
        ch.close();
        var closed = assertInstanceOf(ChannelRuntime.TryWriteResult.Closed.class,
                ch.tryWriteResult((Payload) full.value()));
        assertSame(payload, closed.value());
        assertEquals(0, ch.pendingRegistrations());
    }

    @Test
    void racingWritersCommitExactlyOnceAndReturnEveryLoser() throws Exception {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(8);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        List<Payload> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final Payload payload = new Payload(i);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    var result = ch.tryWriteResult(payload);
                    if (result instanceof ChannelRuntime.TryWriteResult.Accepted<?>) {
                        accepted.incrementAndGet();
                    } else if (result instanceof ChannelRuntime.TryWriteResult.Full<?> full) {
                        assertSame(payload, full.value());
                        failed.add(payload);
                    } else {
                        throw new AssertionError("unexpected Closed result");
                    }
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    workerFailure.compareAndSet(null, failure);
                } finally {
                    finish.countDown();
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        assertTrue(finish.await(5, TimeUnit.SECONDS));
        for (Thread worker : workers) worker.join();
        assertNull(workerFailure.get(), "worker assertions/errors must reach JUnit");
        assertEquals(1, accepted.get());
        assertEquals(7, failed.size());
        assertEquals(1, ch.size());
        assertEquals(0, ch.pendingRegistrations());
    }

    @Test
    void hundredTwentyEightConcurrentProbesReturnEveryUnadmittedOwner() throws Exception {
        final int contenders = 128;
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Payload> rejected = java.util.Collections.synchronizedList(new ArrayList<>());
        var pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                final Payload original = new Payload(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    var result = ch.tryWriteResult(original);
                    if (result instanceof ChannelRuntime.TryWriteResult.Accepted<?>) {
                        accepted.incrementAndGet();
                    } else if (result instanceof ChannelRuntime.TryWriteResult.Full<?> full) {
                        assertSame(original, full.value(), "rejection must return original owner");
                        rejected.add(original);
                    } else {
                        throw new AssertionError("open channel cannot report Closed");
                    }
                    return null;
                }));
            }
            start.countDown();
            // Future.get propagates every worker exception to JUnit.
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "failed immediate probes must never retain a waiting worker");
        }
        assertEquals(1, accepted.get(), "capacity-one channel admits one winner");
        assertEquals(contenders - 1, rejected.size());
        assertEquals(0, ch.pendingRegistrations());
        assertEquals(1, ch.size());

        // A rejected owner is reusable, by identity, on a different channel.
        ChannelRuntime.Channel<Payload> recovery = new ChannelRuntime.Channel<>(1);
        for (Payload original : rejected) {
            assertInstanceOf(ChannelRuntime.TryWriteResult.Accepted.class,
                    recovery.tryWriteResult(original));
            assertSame(original, recovery.tryRead().orElseThrow());
        }
        assertTrue(recovery.tryRead().isEmpty());
        assertEquals(0, recovery.pendingRegistrations());
    }

    @Test
    void concurrentCloseAndTryWriteProduceOneLinearizableOwnedOutcome() throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
            Payload original = new Payload(iteration);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<ChannelRuntime.TryWriteResult<Payload>> observed = new AtomicReference<>();
            AtomicReference<Throwable> raceFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    start.await();
                    observed.set(ch.tryWriteResult(original));
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    raceFailure.compareAndSet(null, failure);
                }
            });
            Thread closer = new Thread(() -> {
                try {
                    start.await();
                    ch.close();
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    raceFailure.compareAndSet(null, failure);
                }
            });
            sender.start();
            closer.start();
            start.countDown();
            sender.join(3000);
            closer.join(3000);
            assertFalse(sender.isAlive());
            assertFalse(closer.isAlive());
            assertNull(raceFailure.get(), "close/send worker errors must reach JUnit");
            assertTrue(ch.isClosed());
            var outcome = observed.get();
            assertNotNull(outcome);
            if (outcome instanceof ChannelRuntime.TryWriteResult.Accepted<?>) {
                assertSame(original, ch.tryRead().orElseThrow());
            } else if (outcome instanceof ChannelRuntime.TryWriteResult.Closed<?> rejected) {
                assertSame(original, rejected.value());
                assertEquals(0, ch.size());
            } else {
                throw new AssertionError("empty capacity-one channel cannot report Full during close race");
            }
            assertEquals(0, ch.pendingRegistrations());
        }
    }

    @Test
    void closedChannelCauseStillReturnsOriginalValue() {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        Payload payload = new Payload(31);
        ch.close(new IllegalStateException("closed by owner"));
        var result = assertInstanceOf(ChannelRuntime.TryWriteResult.Closed.class,
                ch.tryWriteResult(payload));
        assertSame(payload, result.value());
        assertEquals(0, ch.pendingRegistrations());
    }

    @Test
    void invalidNullPayloadIsNotReportedAsFullOrClosed() {
        ChannelRuntime.Channel<Payload> ch = new ChannelRuntime.Channel<>(1);
        assertThrows(NullPointerException.class, () -> ch.tryWriteResult(null));
        ch.close();
        assertThrows(NullPointerException.class, () -> ch.tryWriteResult(null));
    }
}
