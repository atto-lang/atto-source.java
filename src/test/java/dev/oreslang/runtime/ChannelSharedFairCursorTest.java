package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class ChannelSharedFairCursorTest {

    private static ChannelRuntime.SelectSet select(
            ChannelRuntime.Channel<Integer> a,
            ChannelRuntime.Channel<Integer> b,
            ChannelRuntime.FairCursor cursor) {
        return new ChannelRuntime.SelectSet(
                List.of(ChannelRuntime.read(a), ChannelRuntime.read(b)), cursor);
    }

    @Test
    void freshSetsShareOnlyCommittedCreditAcrossDefaultAndCancellation() {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> a = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.Channel<Integer> b = new ChannelRuntime.Channel<>(2);

        assertEquals(2, new ChannelRuntime.SelectSet(
                List.of(ChannelRuntime.read(a), ChannelRuntime.read(b),
                        ChannelRuntime.defaultCase()), cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        OresFuture<ChannelRuntime.SelectResult> cancelled =
                select(a, b, cursor).selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(cancelled.cancel(false));
        assertTrue(a.tryWrite(1));
        assertTrue(b.tryWrite(2));

        assertEquals(0, select(a, b, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());
        assertTrue(a.tryWrite(3));
        assertEquals(1, select(a, b, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());
    }

    @Test
    void pendingSelectResamplesCommittedCursorRatherThanStaleAdmissionOrder() {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> pendingChannel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> pending =
                select(pendingChannel, pendingChannel, cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(pending.isDone());

        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        assertTrue(first.tryWrite(5));
        assertTrue(second.tryWrite(6));
        assertEquals(0, select(first, second, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        assertTrue(pendingChannel.tryWrite(17));
        assertEquals(1, pending.join().index(),
                "pending FAIR select must see credit advanced after admission");
        assertEquals(17, pending.join().value());
    }

    @Test
    void retirementCancelsPendingReadsAndWritesAndDetachesAllArms() {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> outgoing = new ChannelRuntime.Channel<>(0);

        OresFuture<ChannelRuntime.SelectResult> pendingRead =
                select(first, second, cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        OresFuture<ChannelRuntime.SelectResult> pendingWrite =
                new ChannelRuntime.SelectSet(
                        List.of(ChannelRuntime.write(outgoing, 43)), cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(pendingRead.isDone());
        assertFalse(pendingWrite.isDone());

        cursor.retire();
        assertTrue(pendingRead.isCancelled());
        assertTrue(pendingWrite.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, pendingRead::join);
        assertThrows(java.util.concurrent.CancellationException.class, pendingWrite::join);
        assertFalse(first.tryWrite(11));
        assertFalse(second.tryWrite(12));
        assertTrue(outgoing.tryRead().isEmpty());
        cursor.retire(); // idempotent

        OresFuture<ChannelRuntime.SelectResult> later =
                select(first, second, cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertTrue(later.isCancelled(),
                "even non-FAIR registrations may not reuse an actor-retired cursor");
    }

    @Test
    void retirementQueuedBeforeProducerPreservesLateBufferedValue() throws Exception {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> pending =
                new ChannelRuntime.SelectSet(List.of(ChannelRuntime.read(channel)), cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);

        java.util.concurrent.locks.ReentrantLock coordinator = coordinator();
        java.util.concurrent.FutureTask<Void> retirement =
                new java.util.concurrent.FutureTask<>(() -> {
                    cursor.retire();
                    return null;
                });
        java.util.concurrent.FutureTask<Boolean> producer =
                new java.util.concurrent.FutureTask<>(() -> channel.tryWrite(101));

        coordinator.lock();
        try {
            Thread retirementThread = new Thread(retirement, "retire-first");
            retirementThread.start();
            awaitQueued(coordinator, retirementThread);
            Thread producerThread = new Thread(producer, "producer-second");
            producerThread.start();
            awaitQueued(coordinator, producerThread);
        } finally {
            coordinator.unlock();
        }

        retirement.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(producer.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(pending.isCancelled());
        assertEquals(101, channel.tryRead().orElseThrow(),
                "retirement must detach pending read before producer admission");
    }

    @Test
    void producerQueuedBeforeRetirementCommitsExactlyOnce() throws Exception {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> pending =
                new ChannelRuntime.SelectSet(List.of(ChannelRuntime.read(channel)), cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);

        java.util.concurrent.locks.ReentrantLock coordinator = coordinator();
        java.util.concurrent.FutureTask<Boolean> producer =
                new java.util.concurrent.FutureTask<>(() -> channel.tryWrite(202));
        java.util.concurrent.FutureTask<Void> retirement =
                new java.util.concurrent.FutureTask<>(() -> {
                    cursor.retire();
                    return null;
                });
        coordinator.lock();
        try {
            Thread producerThread = new Thread(producer, "producer-first");
            producerThread.start();
            awaitQueued(coordinator, producerThread);
            Thread retirementThread = new Thread(retirement, "retirement-second");
            retirementThread.start();
            awaitQueued(coordinator, retirementThread);
        } finally {
            coordinator.unlock();
        }

        assertTrue(producer.get(5, java.util.concurrent.TimeUnit.SECONDS));
        retirement.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(pending.isCancelled(), "earlier physical commit cannot be revoked");
        assertEquals(202, pending.join().value());
        assertTrue(channel.tryRead().isEmpty(), "no duplicate channel value");
    }

    @Test
    void retiredSelectionNotifiesWaitersOutsideCoordinatorLock() throws Exception {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> pending =
                new ChannelRuntime.SelectSet(List.of(ChannelRuntime.read(channel)), cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        java.util.concurrent.locks.ReentrantLock coordinator = coordinator();
        java.util.concurrent.atomic.AtomicBoolean callbackUnderLock =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        pending.whenCompleteRuntime((value, failure) ->
                callbackUnderLock.set(coordinator.isHeldByCurrentThread()));
        cursor.retire();
        assertTrue(pending.isCancelled());
        assertFalse(callbackUnderLock.get(),
                "scheduler callbacks must run outside the channel coordinator");
    }

    private static java.util.concurrent.locks.ReentrantLock coordinator() throws Exception {
        Field field = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        field.setAccessible(true);
        return (java.util.concurrent.locks.ReentrantLock) field.get(null);
    }

    private static void awaitQueued(
            java.util.concurrent.locks.ReentrantLock lock, Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(thread) && System.nanoTime() < deadline) {
            if (!thread.isAlive()) throw new AssertionError("worker unexpectedly terminated");
            Thread.onSpinWait();
        }
        assertTrue(lock.hasQueuedThread(thread), "worker failed to enter ordered lock queue");
    }

    @Test
    void retiredCursorCannotBeResurrectedByLaterCommit() throws Exception {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> a = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> b = new ChannelRuntime.Channel<>(1);

        cursor.retire();
        assertTrue(a.tryWrite(7));
        OresFuture<ChannelRuntime.SelectResult> afterRetirement =
                select(a, b, cursor).selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(afterRetirement.isCancelled(),
                "retired source actor must not rearm a channel operation");
        assertThrows(java.util.concurrent.CancellationException.class,
                afterRetirement::join);
        assertEquals(7, a.tryRead().orElseThrow(),
                "late channel message must survive the retired selection");

        Field indexField = ChannelRuntime.FairCursor.class.getDeclaredField("index");
        indexField.setAccessible(true);
        assertEquals(Long.MIN_VALUE, ((AtomicLong) indexField.get(cursor)).get(),
                "a late coordinator callback cannot unretire actor fairness state");
    }
}
