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

    private static int retainedRegistrations(ChannelRuntime.Channel<?> channel)
            throws ReflectiveOperationException {
        ReentrantLock lock = coordinator();
        Field field = ChannelRuntime.Channel.class.getDeclaredField("registrations");
        field.setAccessible(true);
        lock.lock();
        try {
            return ((java.util.Collection<?>) field.get(channel)).size();
        } finally {
            lock.unlock();
        }
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
        assertEquals(0, retainedRegistrations(channel));
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
        assertEquals(0, retainedRegistrations(channel));
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
        assertEquals(0, retainedRegistrations(first),
                "cancelled losing select arm must not retain the actor");
        assertEquals(0, retainedRegistrations(second),
                "cancelled winning candidate must not retain the actor");
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
        assertEquals(0, retainedRegistrations(channel));
    }

    @Test
    void concurrentFairSelectsSnapshotCursorAtCoordinatorAdmission() throws Exception {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(2);
        assertTrue(first.tryWrite(11));
        assertTrue(first.tryWrite(12));
        assertTrue(second.tryWrite(21));
        assertTrue(second.tryWrite(22));

        ChannelRuntime.SelectSet reusable = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first), ChannelRuntime.read(second));
        FutureTask<ChannelRuntime.SelectResult> one = new FutureTask<>(
                () -> reusable.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());
        FutureTask<ChannelRuntime.SelectResult> two = new FutureTask<>(
                () -> reusable.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());

        ReentrantLock lock = coordinator();
        lock.lock();
        try {
            Thread firstCaller = start(one, "first-fair-admission");
            awaitQueued(lock, firstCaller);
            Thread secondCaller = start(two, "second-fair-admission");
            awaitQueued(lock, secondCaller);
        } finally {
            lock.unlock();
        }

        assertEquals(0, one.get(5, TimeUnit.SECONDS).index(),
                "first committed selection starts with arm zero");
        assertEquals(1, two.get(5, TimeUnit.SECONDS).index(),
                "second concurrent caller must see first's committed cursor");
        assertEquals(12, first.tryRead().orElseThrow());
        assertEquals(22, second.tryRead().orElseThrow());
        assertEquals(0, retainedRegistrations(first));
        assertEquals(0, retainedRegistrations(second));
    }

    @Test
    void defaultArmDoesNotSpendFairnessCreditBeforeActualRead() {
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet reusable = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.defaultCase(),
                ChannelRuntime.read(second));

        assertEquals(1, reusable.selectAsync(ChannelRuntime.SelectPolicy.FAIR)
                .join().index(), "nothing ready must choose DEFAULT");
        assertTrue(first.tryWrite(17));
        assertTrue(second.tryWrite(27));
        assertEquals(0, reusable.selectAsync(ChannelRuntime.SelectPolicy.FAIR)
                .join().index(), "DEFAULT must not advance FAIR cursor");
        assertEquals(27, second.tryRead().orElseThrow());
        assertEquals(0, retainedRegistrationsUnchecked(first));
        assertEquals(0, retainedRegistrationsUnchecked(second));
    }

    private static int retainedRegistrationsUnchecked(ChannelRuntime.Channel<?> channel) {
        try {
            return retainedRegistrations(channel);
        } catch (ReflectiveOperationException reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    @Test
    void cancellationQueuedBeforeRendezvousWriterLeavesChannelUnconsumed() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> receiver =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertFalse(receiver.isDone());

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> cancel = new FutureTask<>(() -> receiver.cancel(false));
        FutureTask<Boolean> write = new FutureTask<>(() -> channel.tryWrite(91));
        lock.lock();
        try {
            Thread cancelling = start(cancel, "cancel-rendezvous-receiver");
            awaitQueued(lock, cancelling);
            Thread writing = start(write, "write-after-cancel");
            awaitQueued(lock, writing);
        } finally {
            lock.unlock();
        }

        assertTrue(cancel.get(5, TimeUnit.SECONDS));
        assertFalse(write.get(5, TimeUnit.SECONDS),
                "the abandoned read cannot acknowledge a new rendezvous write");
        assertThrows(CancellationException.class, receiver::join);
        assertEquals(0, retainedRegistrations(channel));

        OresFuture<ChannelRuntime.SelectResult> fresh =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertTrue(channel.tryWrite(92));
        assertEquals(92, fresh.join().value(),
                "new readers must not inherit cancelled rendezvous state");
        assertEquals(0, retainedRegistrations(channel));
    }

    @Test
    void rendezvousWriterQueuedBeforeCancelCommitsReadExactlyOnce() throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> receiver =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();
        assertFalse(receiver.isDone());

        ReentrantLock lock = coordinator();
        FutureTask<Boolean> write = new FutureTask<>(() -> channel.tryWrite(55));
        FutureTask<Boolean> cancel = new FutureTask<>(() -> receiver.cancel(false));
        lock.lock();
        try {
            Thread writing = start(write, "write-before-receiver-cancel");
            awaitQueued(lock, writing);
            Thread cancelling = start(cancel, "late-rendezvous-cancel");
            awaitQueued(lock, cancelling);
        } finally {
            lock.unlock();
        }

        assertTrue(write.get(5, TimeUnit.SECONDS));
        assertFalse(cancel.get(5, TimeUnit.SECONDS));
        assertEquals(55, receiver.join().value());
        assertTrue(channel.tryRead().isEmpty());
        assertEquals(0, retainedRegistrations(channel));
    }
}
