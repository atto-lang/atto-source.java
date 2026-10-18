package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical Task ownership and scheduler continuation contract.
 *
 * The source compiler is still gated; these tests deliberately exercise
 * the host-owned lower-level frames and their actor-safe lifecycle barrier.
 */
final class OresTaskScopeFrameTest {
    @Test
    void oneCarrierMakesProgressWhileSiblingFrameAwaits() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresFuture<Integer> dependency = new OresFuture<>();
            AtomicInteger resumes = new AtomicInteger();
            OresTask<Integer> suspended = scope.startCompilerFrame(scheduler, resume -> {
                if (resumes.getAndIncrement() == 0) {
                    assertTrue(resume.initial());
                    return new OresScheduler.Await<Integer>(dependency);
                }
                if (resume.failure() != null)
                    throw new IllegalStateException(resume.failure());
                return new OresScheduler.Done<>(((Integer) resume.value()) + 1);
            });
            OresTask<Integer> ready = scope.startCompilerFrame(scheduler,
                    resume -> new OresScheduler.Done<>(11));
            assertEquals(11, AsyncRuntime.await(ready.getAwaited()));
            assertFalse(suspended.isDone());
            dependency.completeFromRuntime(41);
            assertEquals(42, AsyncRuntime.await(suspended.getAwaited()));
            assertNull(AsyncRuntime.await(suspended.getDone()));
            assertEquals(2, resumes.get());
        }
    }

    @Test
    void cancellingScopeCannotReleaseResourcesBeforePhysicalGuestExit() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch allowExit = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicReference<Throwable> closeError = new AtomicReference<>();
            OresTask<Integer> task = scope.startCompilerFrame(scheduler, resume -> {
                entered.countDown();
                try {
                    assertTrue(allowExit.await(3, TimeUnit.SECONDS));
                    return new OresScheduler.Done<>(3);
                } finally {
                    // Simulates compiler-emitted cleanup still owning memory.
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread closer = Thread.ofVirtual().start(() -> {
                try { scope.close(); }
                catch (Throwable failure) { closeError.set(failure); }
                finally { closeReturned.countDown(); }
            });
            try {
                assertTrue(task.cancelAndJoin().isDone() || task.isCancelled());
                assertFalse(task.isQuiescent());
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                        "scope.close cannot finish while guest code retains a frame");
            } finally {
                allowExit.countDown();
            }
            assertTrue(closeReturned.await(3, TimeUnit.SECONDS));
            closer.join();
            assertNull(closeError.get());
            assertTrue(task.isQuiescent());
        }
    }

    @Test
    void noBlockingScopeCloseFromResumableSchedulerTurn() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Boolean> task = scope.startCompilerFrame(scheduler, resume ->
                    new OresScheduler.Done<>(
                            assertThrows(IllegalStateException.class, scope::close) != null));
            assertTrue(AsyncRuntime.await(task.getAwaited()));
        }
    }

    @Test
    void cancellingScopeRejectsLaterCompilerFramesAndCancelsAwaiters() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresFuture<Integer> waiting = new OresFuture<>();
            OresTask<Integer> task = scope.startCompilerFrame(scheduler,
                    resume -> new OresScheduler.Await<Integer>(waiting));
            assertTrue(scope.cancel());
            assertTrue(task.isCancelled());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(1)));
            waiting.completeFromRuntime(5);
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> AsyncRuntime.await(task.getAwaited()));
            assertNull(AsyncRuntime.await(task.getDone()));
        }
    }
    @Test
    void cancelledScopedFrameCannotResumeAfterLateDependencyCompletion() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger lateResumes = new AtomicInteger();
            OresFuture<Integer> dependency = new OresFuture<>();
            OresTask<Integer> child = scope.startCompilerFrame(scheduler, resume -> {
                if (resume.initial()) {
                    entered.countDown();
                    return new OresScheduler.Await<Integer>(dependency);
                }
                lateResumes.incrementAndGet();
                return new OresScheduler.Done<>(17);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(scope.cancel());
            assertNull(AsyncRuntime.await(child.getDone()));
            assertTrue(child.isCancelled());
            dependency.completeFromRuntime(99);
            // The scheduler's physical-exit barrier must also fence any
            // previously registered completion callback from re-entry.
            assertEquals(0, lateResumes.get());
        }
    }



    @Test
    void suspendsStyleAwaitUsesSameTaskAndCancellingWaiterLeavesSharedProducerAlive() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresFuture<Integer> sharedProducer = new OresFuture<>();
            CountDownLatch suspendedEntered = new CountDownLatch(1);
            AtomicInteger cancelledResumes = new AtomicInteger();
            OresTask<Integer> cancelled = scope.startCompilerFrame(scheduler, resume -> {
                if (resume.initial()) {
                    suspendedEntered.countDown();
                    return new OresScheduler.Await<Integer>(sharedProducer);
                }
                cancelledResumes.incrementAndGet();
                return new OresScheduler.Done<>(-1);
            });
            assertTrue(suspendedEntered.await(2, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel());
            assertNull(AsyncRuntime.await(cancelled.getDone()));
            assertEquals(0, cancelledResumes.get());
            assertFalse(sharedProducer.isDone(),
                    "cancelling a suspends-style waiter must not cancel a shared producer");

            AtomicInteger aliveResumes = new AtomicInteger();
            OresTask<Integer> surviving = scope.startCompilerFrame(scheduler, resume -> {
                if (resume.initial()) {
                    aliveResumes.incrementAndGet();
                    return new OresScheduler.Await<Integer>(sharedProducer);
                }
                aliveResumes.incrementAndGet();
                return new OresScheduler.Done<>(((Integer) resume.value()) + 1);
            });
            sharedProducer.completeFromRuntime(40);
            assertEquals(41, AsyncRuntime.await(surviving.getAwaited()));
            assertNull(AsyncRuntime.await(surviving.getDone()));
            assertEquals(2, aliveResumes.get());
            assertEquals(0, cancelledResumes.get(),
                    "a late producer completion must never resurrect the cancelled continuation");
        }
    }

    @Test
    void continuationSuspendsAndResumesWithoutCreatingNestedTaskOrFuture() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresFuture<Integer> input = OresFuture.completed(21);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Object> logicalDomain = new AtomicReference<>();
            OresTask<Integer> outer = scope.startCompilerFrame(scheduler, resume -> {
                Object active = OresScheduler.currentTaskDomain();
                assertNotNull(active, "suspends continuation must run with a Task domain");
                if (calls.getAndIncrement() == 0) {
                    assertNull(logicalDomain.getAndSet(active));
                    return new OresScheduler.Await<Integer>(input);
                }
                assertSame(logicalDomain.get(), active,
                        "resuming suspends work must not silently create a child Task");
                return new OresScheduler.Done<>(((Integer) resume.value()) * 2);
            });
            assertEquals(42, AsyncRuntime.await(outer.getAwaited()));
            assertNull(AsyncRuntime.await(outer.getDone()));
            assertEquals(2, calls.get());
            assertTrue(outer.isQuiescent());
        }
    }
}
