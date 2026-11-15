package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                writer.tryWriteResult(full.value()));
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
                ch.tryWriteResult(full.value()));
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
        assertNull(workerFailure.get(), "worker assertions/exceptions must fail the JUnit test");
        assertEquals(1, accepted.get());
        assertEquals(7, failed.size());
        assertEquals(1, ch.size());
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
