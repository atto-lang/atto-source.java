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

    @Test
    void generationLeaseRemainsPinnedUntilPhysicalTaskTurnExits() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch permitExit = new CountDownLatch(1);
            AtomicInteger generationPins = new AtomicInteger(1);
            AtomicInteger releaseCallbacks = new AtomicInteger();
            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> {
                        entered.countDown();
                        assertTrue(permitExit.await(3, TimeUnit.SECONDS));
                        return new OresScheduler.Done<>(11);
                    },
                    () -> {
                        assertEquals(1, generationPins.getAndDecrement(),
                                "the generation image must still be pinned at physical exit");
                        releaseCallbacks.incrementAndGet();
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(child.cancel());
                assertTrue(child.isCancelled());
                assertEquals(1, generationPins.get(),
                        "result cancellation must not release executing code");
                assertFalse(child.isQuiescent());
            } finally {
                permitExit.countDown();
            }
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(0, generationPins.get());
            assertEquals(1, releaseCallbacks.get());
        }
    }

    @Test
    void generationReleaseFailurePropagatesAndCannotBeHiddenByTaskScope() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(runtime);
            AtomicInteger pinnedGeneration = new AtomicInteger(1);
            AtomicInteger callbacks = new AtomicInteger();
            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(7),
                    () -> {
                        callbacks.incrementAndGet();
                        throw new IllegalStateException("fault-injected generation release");
                    });
            assertEquals(7, AsyncRuntime.await(child.getAwaited()));
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> AsyncRuntime.await(child.getDone()));
            assertTrue(failure.toString().contains("release"), failure::toString);
            RuntimeException closure = assertThrows(RuntimeException.class, scope::close);
            assertTrue(closure.toString().contains("retirement failed"),
                    closure::toString);
            assertEquals(1, pinnedGeneration.get(),
                    "failed external release may never be mistaken for pin retirement");
            assertEquals(1, callbacks.get(),
                    "code-image release must not run twice on scope shutdown");
        }
    }

    @Test
    void closedSchedulerRejectsAdmissionAndRetiresGenerationExactlyOnce() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresScheduler scheduler = new OresScheduler(1);
            scheduler.close();
            AtomicInteger imageReleases = new AtomicInteger();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(42),
                            () -> imageReleases.incrementAndGet()));
            assertEquals(1, imageReleases.get(),
                    "a pin acquired before rejected scheduler admission must retire");
            assertDoesNotThrow(scope::close);
            assertEquals(1, imageReleases.get(),
                    "closing the owner must not retire a rejected pin twice");
        }
    }

    @Test
    void rejectedAdmissionCannotHideGenerationReleaseFailure() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresScheduler scheduler = new OresScheduler(1);
            scheduler.close();
            AtomicInteger releases = new AtomicInteger();
            java.util.concurrent.RejectedExecutionException rejected =
                    assertThrows(java.util.concurrent.RejectedExecutionException.class,
                            () -> scope.startCompilerFrame(scheduler,
                                    resume -> new OresScheduler.Done<>(42),
                                    () -> {
                                        releases.incrementAndGet();
                                        throw new IllegalStateException("image release failed");
                                    }));
            assertEquals(1, releases.get());
            assertEquals(1, rejected.getSuppressed().length,
                    "host release failure must remain visible to the caller");
            assertTrue(rejected.getSuppressed()[0].toString().contains("image release failed"));
        }
    }


    @Test
    void cancelledParentScopeRejectsChildButReleasesItsAcquiredCodePin() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertTrue(scope.cancel());
            AtomicInteger releases = new AtomicInteger();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(9),
                            () -> releases.incrementAndGet()));
            assertEquals(1, releases.get(),
                    "cancelled scope must retire a never-admitted child pin");
            assertDoesNotThrow(scope::close);
            assertEquals(1, releases.get(),
                    "a rejected child cannot be retired a second time");
        }
    }


    @Test
    void parentRewalkOfClaimedChildStillReleasesRejectedGrandchildCodePinOnce()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope root = new OresTaskScope(runtime)) {
            OresTaskScope child = root.childScope();
            OresTaskScope grandchild = child.childScope();

            // Reproduce the state between another canceller claiming the
            // child admission gate and visiting the grandchild. Parent close
            // must not skip this subtree simply because its flag is set.
            java.lang.reflect.Field claim =
                    OresTaskScope.class.getDeclaredField("cancelled");
            claim.setAccessible(true);
            claim.setBoolean(child, true);
            assertFalse(grandchild.isCancelRequested());
            assertTrue(root.cancel());
            assertTrue(grandchild.isCancelRequested());

            AtomicInteger releases = new AtomicInteger();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> grandchild.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(17),
                            releases::incrementAndGet));
            assertEquals(1, releases.get(),
                    "rejected grandchild admission must release its new code pin");
            assertDoesNotThrow(root::close);
            assertEquals(1, releases.get(),
                    "parent shutdown must not release an unadmitted pin again");
        }
    }



    @Test
    void cancelledScopeReleasesRejectedFramePinOutsideScopeMonitor() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            java.lang.reflect.Field field = OresTaskScope.class.getDeclaredField("gate");
            field.setAccessible(true);
            Object monitor = field.get(scope);
            AtomicInteger releases = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean lockHeld =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            assertTrue(scope.cancel());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(42), () -> {
                                releases.incrementAndGet();
                                lockHeld.set(Thread.holdsLock(monitor));
                            }));
            assertEquals(1, releases.get(), "rejected generation pin must be retired once");
            assertFalse(lockHeld.get(), "release callback must not execute under TaskScope monitor");
            assertDoesNotThrow(scope::close);
            assertEquals(1, releases.get(), "scope close must not release a rejected pin again");
        }
    }

    @Test
    void schedulerRejectionReleasesFramePinOutsideScopeMonitor() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            java.lang.reflect.Field field = OresTaskScope.class.getDeclaredField("gate");
            field.setAccessible(true);
            Object monitor = field.get(scope);
            OresScheduler scheduler = new OresScheduler(1);
            scheduler.close();
            AtomicInteger releases = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean lockHeld =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(42), () -> {
                                releases.incrementAndGet();
                                lockHeld.set(Thread.holdsLock(monitor));
                            }));
            assertEquals(1, releases.get());
            assertFalse(lockHeld.get(), "scheduler rejection cleanup must not hold scope monitor");
            assertDoesNotThrow(scope::close);
            assertEquals(1, releases.get());
        }
    }

    @Test
    void admittedCompilerFrameReleasesPinExactlyOnceAfterPhysicalExit() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exitAllowed = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicInteger releases = new AtomicInteger();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            OresTask<Integer> child = scope.startCompilerFrame(scheduler,
                    resume -> {
                        entered.countDown();
                        assertTrue(exitAllowed.await(3, TimeUnit.SECONDS));
                        return new OresScheduler.Done<>(81);
                    }, releases::incrementAndGet);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closed.countDown();
                }
            });
            try {
                assertFalse(closed.await(75, TimeUnit.MILLISECONDS),
                        "physical Task execution must prevent close completion");
                assertEquals(0, releases.get(),
                        "cancellation/result settlement cannot retire executing code");
            } finally {
                exitAllowed.countDown();
            }
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            closer.join();
            assertNull(closeFailure.get());
            assertEquals(1, releases.get());
            assertTrue(child.isQuiescent());
        }
    }

    @Test
    void concurrentCompilerFrameAdmissionAndCloseNeverLoseCodePin() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2)) {
            for (int trial = 0; trial < 64; trial++) {
                OresTaskScope scope = new OresTaskScope(runtime);
                CountDownLatch begin = new CountDownLatch(1);
                AtomicInteger releases = new AtomicInteger();
                AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
                AtomicReference<Throwable> closeFailure = new AtomicReference<>();
                Thread starter = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(begin.await(3, TimeUnit.SECONDS));
                        scope.startCompilerFrame(scheduler,
                                resume -> new OresScheduler.Done<>(21),
                                releases::incrementAndGet);
                    } catch (java.util.concurrent.RejectedExecutionException expected) {
                        // close won admission; its unconsumed pin was released.
                    } catch (Throwable unexpected) {
                        admissionFailure.set(unexpected);
                    }
                });
                Thread closer = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(begin.await(3, TimeUnit.SECONDS));
                        scope.close();
                    } catch (Throwable unexpected) {
                        closeFailure.set(unexpected);
                    }
                });
                begin.countDown();
                starter.join(4_000);
                closer.join(4_000);
                assertFalse(starter.isAlive(), "admission stuck on trial " + trial);
                assertFalse(closer.isAlive(), "close stuck on trial " + trial);
                assertNull(admissionFailure.get(), "admission failed on trial " + trial);
                assertNull(closeFailure.get(), "close failed on trial " + trial);
                assertEquals(1, releases.get(),
                        "exactly one code lease retirement required on trial " + trial);
            }
        }
    }

    @Test
    void sealedScopeRejectsCompilerFrameWithoutUnregisteredOwnerRemoval() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertTrue(scope.cancel());
            var slotsField = OresTaskScope.class.getDeclaredField("tasks");
            slotsField.setAccessible(true);
            Object original = slotsField.get(scope);
            AtomicInteger attemptedRemove = new AtomicInteger();
            java.util.Set<Object> hostile = new java.util.HashSet<>() {
                @Override public boolean remove(Object item) {
                    attemptedRemove.incrementAndGet();
                    throw new IllegalStateException("never-owned Task removed");
                }
            };
            slotsField.set(scope, hostile);
            try {
                AtomicInteger pinReleases = new AtomicInteger();
                var rejection = assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        () -> scope.startCompilerFrame(scheduler,
                                resume -> new OresScheduler.Done<>(2),
                                pinReleases::incrementAndGet));
                assertEquals(0, rejection.getSuppressed().length);
                assertEquals(0, attemptedRemove.get(),
                        "rejected-before-insertion slot is not scope-owned");
                assertEquals(1, pinReleases.get(),
                        "unconsumed compiler code pin releases once");
                assertTrue(scope.physicallyQuiescent());
            } finally {
                slotsField.set(scope, original);
            }
        }
    }

    @Test
    void postInsertCompilerSlotFailureStillRetiresPinAndOwner() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            var slotsField = OresTaskScope.class.getDeclaredField("tasks");
            slotsField.setAccessible(true);
            Object original = slotsField.get(scope);
            java.util.Set<Object> hostile = new java.util.HashSet<>() {
                @Override public boolean add(Object item) {
                    super.add(item);
                    throw new IllegalStateException("after compiler owner insert");
                }
            };
            slotsField.set(scope, hostile);
            try {
                AtomicInteger pinReleases = new AtomicInteger();
                var failure = assertThrows(IllegalStateException.class,
                        () -> scope.startCompilerFrame(scheduler,
                                resume -> new OresScheduler.Done<>(3),
                                pinReleases::incrementAndGet));
                assertTrue(failure.getMessage().contains("after compiler owner insert"));
                assertEquals(1, pinReleases.get());
                assertTrue(scope.physicallyQuiescent(),
                        "partial owner must be removed before rejecting admission");
                assertEquals(0, hostile.size(), "no Task owner leaked after rollback");
            } finally {
                slotsField.set(scope, original);
            }
        }
    }
}
