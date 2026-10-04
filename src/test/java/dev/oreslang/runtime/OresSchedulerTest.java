package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerTest {

    @Test
    void awaitResumesOnlyOnOwningSchedulerNotProducerThread() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicReference<Thread> producer = new AtomicReference<>();
            AtomicInteger state = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int pc = state.getAndIncrement();
                assertSame(scheduler, OresScheduler.current());

                if (pc == 0) {
                    assertTrue(resume.initial());
                    return OresScheduler.await(source);
                }

                assertFalse(resume.initial());
                assertNull(resume.failure());
                assertEquals(41, resume.value());
                assertNotSame(producer.get(), Thread.currentThread(),
                        "producer/completion thread must never execute guest continuation");
                return OresScheduler.done(42);
            });

            Thread completionThread = Thread.ofPlatform().start(() -> {
                producer.set(Thread.currentThread());
                source.completeFromRuntime(41);
            });
            completionThread.join();

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, state.get());
        }
    }

    @Test
    void alreadyCompletedFutureStillCreatesLaterSchedulerTurn() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> completed = OresFuture.completed(7);
            AtomicInteger state = new AtomicInteger();
            AtomicBoolean insideFirstResume = new AtomicBoolean();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int pc = state.getAndIncrement();
                if (pc == 0) {
                    insideFirstResume.set(true);
                    try {
                        return OresScheduler.await(completed);
                    } finally {
                        insideFirstResume.set(false);
                    }
                }

                assertFalse(insideFirstResume.get(),
                        "await continuation must not resume inline in the suspending turn");
                assertEquals(7, resume.value());
                return OresScheduler.done(8);
            });

            assertEquals(8, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, state.get());
        }
    }

    @Test
    void oneFutureMayResumeWaitersOnDifferentSchedulers() throws Exception {
        try (OresScheduler left = new OresScheduler(1);
             OresScheduler right = new OresScheduler(1)) {

            OresFuture<Integer> shared = new OresFuture<>();
            AtomicInteger leftPc = new AtomicInteger();
            AtomicInteger rightPc = new AtomicInteger();

            OresFuture<String> leftResult = left.start(resume -> {
                if (leftPc.getAndIncrement() == 0) {
                    assertSame(left, OresScheduler.current());
                    return OresScheduler.await(shared);
                }
                assertSame(left, OresScheduler.current());
                return OresScheduler.done("left:" + resume.value());
            });

            OresFuture<String> rightResult = right.start(resume -> {
                if (rightPc.getAndIncrement() == 0) {
                    assertSame(right, OresScheduler.current());
                    return OresScheduler.await(shared);
                }
                assertSame(right, OresScheduler.current());
                return OresScheduler.done("right:" + resume.value());
            });

            shared.completeFromRuntime(9);

            assertEquals("left:9", leftResult.get(5, TimeUnit.SECONDS));
            assertEquals("right:9", rightResult.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void awaitFailureIsDeliveredBackToOwningTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source =
                    OresFuture.failed(new IllegalStateException("boom"));
            AtomicInteger pc = new AtomicInteger();

            OresFuture<String> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    return OresScheduler.await(source);
                }
                assertInstanceOf(IllegalStateException.class, resume.failure());
                assertEquals("boom", resume.failure().getMessage());
                return OresScheduler.done("handled");
            });

            assertEquals("handled", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void synchronousTaskIsStillSchedulerBound() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Boolean> result =
                    scheduler.startSync(() -> OresScheduler.current() == scheduler);
            assertTrue(result.get(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void cancellingTaskDetachesPendingAwaitRegistration() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int turn = turns.incrementAndGet();
                if (turn == 1) return OresScheduler.await(source);
                return OresScheduler.done((Integer) resume.value());
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.queuedRuntimeWaiters() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, source.queuedRuntimeWaiters());

            assertTrue(result.cancel(false));
            assertTrue(result.isCancelled());
            assertEquals(0, source.queuedRuntimeWaiters(),
                    "task cancellation must release its awaited Future callback");

            assertTrue(source.completeFromRuntime(41));
            assertTrue(scheduler.startSync(() -> true).get(5, TimeUnit.SECONDS));
            assertEquals(1, turns.get(),
                    "late producer completion must not revive a cancelled task");
        }
    }

    @Test
    void closingSchedulerDetachesPendingAwaitRegistration() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> source = new OresFuture<>();
        AtomicInteger turns = new AtomicInteger();

        OresFuture<Integer> result = scheduler.start(resume -> {
            int turn = turns.incrementAndGet();
            if (turn == 1) return OresScheduler.await(source);
            return OresScheduler.done((Integer) resume.value());
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (source.queuedRuntimeWaiters() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, source.queuedRuntimeWaiters());

        scheduler.close();

        assertTrue(result.isCancelled());
        assertEquals(0, source.queuedRuntimeWaiters(),
                "scheduler close must release every suspended await callback");

        assertTrue(source.completeFromRuntime(9));
        assertEquals(1, turns.get(),
                "producer completion after scheduler close must not resume guest code");
    }

}
