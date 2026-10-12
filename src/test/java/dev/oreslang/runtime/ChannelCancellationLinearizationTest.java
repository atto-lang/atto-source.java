package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable refinement probes for the channel coordinator's linearization
 * point. A fair coordinator lock provides a deterministic order, instead of
 * hoping that a sleep or a large repetition count wins the desired race.
 *
 * These tests exercise actual channel registrations and Future settlement;
 * they are not a substitute for verifying source-task cancellation ownership.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class ChannelCancellationLinearizationTest {
    private static ReentrantLock coordinator() throws ReflectiveOperationException {
        Field field = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        field.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) field.get(null);
        assertTrue(lock.isFair(), "test relies on FIFO coordinator admission");
        return lock;
    }

    private static Thread start(FutureTask<?> action, String name) {
        Thread worker = new Thread(action, name);
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void awaitQueued(ReentrantLock lock, Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(worker)) {
            assertTrue(worker.isAlive(), "worker terminated before coordinator admission");
            if (System.nanoTime() >= deadline) {
                fail("worker did not queue at the channel coordinator: " + worker.getName());
            }
            LockSupport.parkNanos(100_000);
        }
    }

    @Test
    void cancellationQueuedFirstCannotStealLaterBufferedProducerValue() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> selection =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertFalse(selection.isDone());

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> cancel = new FutureTask<>(() -> selection.cancel(false));
        FutureTask<Boolean> produce = new FutureTask<>(() -> channel.tryWrite(42));
        lock.lock();
        try {
            Thread canceller = start(cancel, "cancel-first");
            awaitQueued(lock, canceller);
            Thread producer = start(produce, "produce-second");
            awaitQueued(lock, producer);
        } finally {
            lock.unlock();
        }

        assertTrue(cancel.get(5, TimeUnit.SECONDS), "cancellation won admission");
        assertTrue(produce.get(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, selection::join);
        assertEquals(42, channel.tryRead().orElseThrow(),
                "a later producer must not be swallowed by an abandoned read");
    }

    @Test
    void producerQueuedFirstCommitsOnceAndCancellationCannotUndoIt() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> selection =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertFalse(selection.isDone());

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> produce = new FutureTask<>(() -> channel.tryWrite(73));
        FutureTask<Boolean> cancel = new FutureTask<>(() -> selection.cancel(false));
        lock.lock();
        try {
            Thread producer = start(produce, "produce-first");
            awaitQueued(lock, producer);
            Thread canceller = start(cancel, "cancel-second");
            awaitQueued(lock, canceller);
        } finally {
            lock.unlock();
        }

        assertTrue(produce.get(5, TimeUnit.SECONDS));
        assertFalse(cancel.get(5, TimeUnit.SECONDS),
                "a committed read cannot be retroactively cancelled");
        assertEquals(73, selection.get(5, TimeUnit.SECONDS).value());
        assertTrue(channel.tryRead().isEmpty(),
                "each committed value is consumed at most once");
    }

    @Test
    void cancellationOfTwoArmSelectionDetachesBothLosers() throws Exception {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> selection = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second)).selectAsync(ChannelRuntime.SelectPolicy.FAIR);

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> cancel = new FutureTask<>(() -> selection.cancel(false));
        FutureTask<Boolean> produce = new FutureTask<>(() -> second.tryWrite(89));
        lock.lock();
        try {
            Thread canceller = start(cancel, "multi-cancel");
            awaitQueued(lock, canceller);
            Thread producer = start(produce, "multi-produce");
            awaitQueued(lock, producer);
        } finally {
            lock.unlock();
        }

        assertTrue(cancel.get(5, TimeUnit.SECONDS));
        assertTrue(produce.get(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, selection::join);
        assertTrue(first.tryWrite(34));
        assertEquals(34, first.tryRead().orElseThrow());
        assertEquals(89, second.tryRead().orElseThrow());
    }

    @Test
    void cancelledRendezvousWriteNeverAppearsToLaterReceiver() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selection =
                ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, 27))
                        .selectAsync();
        assertFalse(selection.isDone());

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> cancel = new FutureTask<>(() -> selection.cancel(false));
        FutureTask<Boolean> receive = new FutureTask<>(() -> channel.tryRead().isPresent());
        lock.lock();
        try {
            Thread canceller = start(cancel, "write-cancel");
            awaitQueued(lock, canceller);
            Thread receiver = start(receive, "late-receiver");
            awaitQueued(lock, receiver);
        } finally {
            lock.unlock();
        }

        assertTrue(cancel.get(5, TimeUnit.SECONDS));
        assertFalse(receive.get(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, selection::join);
        assertTrue(channel.tryRead().isEmpty());
    }
}
