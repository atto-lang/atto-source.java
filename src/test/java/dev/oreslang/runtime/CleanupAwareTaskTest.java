package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cancellation request != guest cleanup completion != physical retirement. */
@Timeout(20)
final class CleanupAwareTaskTest {
    @Test void suspendedSourceRunsCleanupBeforeCancellationIsPublished() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Void> cleanup = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        AtomicInteger stages = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (stages.getAndIncrement() == 0) {
                        assertTrue(resume.initial());
                        waiting.countDown();
                        return OresScheduler.await(source);
                    }
                    if (stages.get() == 2) {
                        assertInstanceOf(CancellationException.class, resume.failure());
                        assertFalse(resume.initial());
                        cleanupEntered.countDown();
                        return OresScheduler.await(cleanup);
                    }
                    assertNull(resume.failure());
                    return OresScheduler.done(42);
                }
            });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "cancelled result must wait for defer cleanup");
            assertFalse(source.isCancelled(), "source Future is borrowed");
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(task.cancel(false), "second cancellation must not restart cleanup");
            cleanup.completeFromRuntime(null);
            assertThrows(CancellationException.class,
                    () -> task.get(3, TimeUnit.SECONDS));
            assertTrue(task.isCancelled());
            assertEquals(3, stages.get());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
        }
    }

    @Test void runningTaskCannotEnterCleanupOnExternalCarrier() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        AtomicInteger resumes = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> future = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (resumes.getAndIncrement() == 0) {
                        entered.countDown();
                        try {
                            assertTrue(unblock.await(4, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return OresScheduler.cooperate();
                    }
                    assertInstanceOf(CancellationException.class, resume.failure());
                    cleanup.countDown();
                    return OresScheduler.done(77);
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(future.cancel(false));
                assertFalse(future.isDone());
                assertEquals(0, cleanup.getCount() == 0 ? 1 : 0,
                        "cleanup must wait for physically running turn");
            } finally {
                unblock.countDown();
            }
            assertTrue(cleanup.await(4, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertEquals(2, resumes.get());
        }
    }

    @Test void cleanupCanSuspendSeveralTimesWithoutRepeatedCancellation() throws Exception {
        OresFuture<Integer> input = new OresFuture<>();
        OresFuture<Integer> cleanupA = new OresFuture<>();
        OresFuture<Integer> cleanupB = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger state = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume r) {
                    return switch (state.getAndIncrement()) {
                        case 0 -> { started.countDown(); yield OresScheduler.await(input); }
                        case 1 -> {
                            assertInstanceOf(CancellationException.class, r.failure());
                            yield OresScheduler.await(cleanupA);
                        }
                        case 2 -> {
                            assertEquals(20, r.value());
                            assertNull(r.failure());
                            yield OresScheduler.await(cleanupB);
                        }
                        case 3 -> {
                            assertEquals(30, r.value());
                            assertNull(r.failure());
                            yield OresScheduler.done(99);
                        }
                        default -> throw new AssertionError("duplicate cleanup resume");
                    };
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertFalse(task.isDone());
            cleanupA.completeFromRuntime(20);
            cleanupB.completeFromRuntime(30);
            assertThrows(CancellationException.class,
                    () -> task.get(4, TimeUnit.SECONDS));
            assertEquals(4, state.get());
        }
    }

    @Test void cancellationBeforeFirstExecutionNeverRunsGuestTask() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch resumeBusy = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> blocker = scheduler.start(resume -> {
                busy.countDown();
                try { assertTrue(resumeBusy.await(4, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return OresScheduler.done(null);
            });
            assertTrue(busy.await(3, TimeUnit.SECONDS));
            OresFuture<Integer> future = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                        started.set(true);
                        return OresScheduler.done(1);
                    });
            assertTrue(future.cancel(false));
            resumeBusy.countDown();
            assertThrows(CancellationException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertNull(blocker.get(4, TimeUnit.SECONDS));
            assertFalse(started.get());
        }
    }

    @Test void fatalCleanupErrorCannotBeHiddenByCancellation() throws Exception {
        OresFuture<Integer> waiting = new OresFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        AssertionError fatal = new AssertionError("panic during cleanup");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                private int phase;
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume r) {
                    if (phase++ == 0) {
                        entered.countDown();
                        return OresScheduler.await(waiting);
                    }
                    assertInstanceOf(CancellationException.class, r.failure());
                    throw fatal;
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertSame(fatal, failed.getCause());
            assertFalse(future.isCancelled());
        }
    }

    @Test void guestThrownCancellationExceptionWithoutOwnerRequestIsFailure() throws Exception {
        CancellationException thrown = new CancellationException("guest thrown");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) r -> { throw thrown; });
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertSame(thrown, failed.getCause());
            assertFalse(future.isCancelled());
        }
    }
}
