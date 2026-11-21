package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The real OresScheduler TaskHandle + OresMutex synchronous guard path. */
public final class TaskOwnedLockLedgerTest {
    @Test
    void wallDeadlineTaskHasOwnGuardLedgerAndReleasesQuotaCallbacks()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{5});
            AtomicReference<OresMutex.Guard<int[]>> leaked = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger physical = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger notified = new java.util.concurrent.atomic.AtomicInteger();
            TaskHandle<Void> task = TaskHandle.startWithWallDeadline(
                    scheduler,
                    resume -> {
                        var guard = mutex.tryLock().orElseThrow();
                        leaked.set(guard);
                        guard.value()[0] = 29;
                        return OresScheduler.done(null);
                    },
                    physical::incrementAndGet,
                    TimeUnit.SECONDS.toNanos(5),
                    new IllegalStateException("injected wall budget exhausted"),
                    notified::incrementAndGet);
            assertEquals(new TaskHandle.Success<Void>(null),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertNotNull(leaked.get());
            assertTrue(leaked.get().released(), "wall-deadline Task lost its lock ledger");
            assertTrue(mutex.isPoisoned());
            assertEquals(1, physical.get(), "physical quota retirement duplicated");
            assertEquals(1, notified.get(), "timed notification quota retirement duplicated");
        }
    }

    @Test
    void wallDeadlineSuspendedTaskIsQuiescentBeforeSchedulerCloseReturnsDone()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            var mutex = OresMutex.shared(new int[]{8});
            OresFuture<Integer> never = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicReference<OresMutex.Guard<int[]>> held = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger physical = new java.util.concurrent.atomic.AtomicInteger();
            TaskHandle<Void> task = TaskHandle.startWithWallDeadline(
                    scheduler,
                    resume -> {
                        if (!resume.initial()) {
                            throw new IllegalStateException("cancelled Task resumed");
                        }
                        var guard = mutex.tryLock().orElseThrow();
                        held.set(guard);
                        suspended.countDown();
                        return OresScheduler.await(never);
                    },
                    physical::incrementAndGet,
                    TimeUnit.SECONDS.toNanos(5),
                    new IllegalStateException("deadline exceeded"),
                    () -> { });
            assertTrue(suspended.await(3, TimeUnit.SECONDS));
            scheduler.close();
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, physical.get());
            assertTrue(held.get().released());
            assertTrue(mutex.isPoisoned());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void immediatelyAvailableAsyncMutexCannotOutliveRequestingTask()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int round = 0; round < 128; round++) {
                var mutex = OresMutex.shared(new int[] {round});
                AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                        result = new AtomicReference<>();
                TaskHandle<Void> task = scheduler.startTracked(resume -> {
                    // The first acquisition takes the immediate physical
                    // permit path. Its Future publication may race Task exit.
                    result.set(mutex.lockAsync());
                    return OresScheduler.done(null);
                });
                assertEquals(new TaskHandle.Success<Void>(null),
                        task.getDone().get(5, TimeUnit.SECONDS));
                assertNotNull(result.get());
                assertTrue(result.get().isDone(),
                        "Task DONE published ahead of immediate grant settlement");
                if (!result.get().isCompletedExceptionally()) {
                    assertTrue(result.get().join().released(),
                            "late async grant retained a live dead-Task permit");
                }
                assertTrue(mutex.isPoisoned(),
                        "unreleased Task-owned writer must poison after quiescence");
                mutex.recover(value -> {
                    value[0] = 0;
                    return null;
                });
                var recovered = mutex.tryLock().orElseThrow();
                recovered.release();
            }
        }
    }

    @Test
    void mutexCreatorMayRetireWhileAnotherTaskLaterAcquiresItsGuard() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            AtomicReference<OresMutex.Shared<int[]>> published = new AtomicReference<>();
            TaskHandle<Void> creator = scheduler.startTracked(resume -> {
                published.set(OresMutex.shared(new int[] {3}));
                return OresScheduler.done(null);
            });
            assertEquals(new TaskHandle.Success<Void>(null),
                    creator.getDone().get(3, TimeUnit.SECONDS));

            // A mutex object's creator does not own a later Task's permit.
            TaskHandle<Integer> holder = scheduler.startTracked(resume -> {
                var guard = published.get().tryLock().orElseThrow();
                try {
                    guard.value()[0] += 4;
                    return OresScheduler.done(guard.value()[0]);
                } finally {
                    guard.release();
                }
            });
            assertEquals(new TaskHandle.Success<>(7),
                    holder.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(published.get().isPoisoned());
        }
    }

    @Test
    void staleQueuedFutureSkipsPermitWithoutForgingCallbackDrain()
            throws Exception {
        // Fault injection models a terminal Future whose synchronous host
        // subscriber has not yet allowed the queue's own observer to run.
        // The releaser must not confuse public isDone with physical cleanup.
        var mutex = OresMutex.shared(new int[] {13});
        var hostGuard = mutex.tryLock().orElseThrow();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                request = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> request.set(mutex.lockAsync()));
        requester.join(5000);
        assertFalse(requester.isAlive());
        assertNotNull(request.get());
        assertFalse(request.get().isDone());

        var queueField = OresMutex.Shared.class.getDeclaredField("asyncQueue");
        var lockField = OresMutex.Shared.class.getDeclaredField("asyncQueueLock");
        queueField.setAccessible(true);
        lockField.setAccessible(true);
        Object waiter;
        synchronized (lockField.get(mutex)) {
            waiter = ((java.util.ArrayDeque<?>) queueField.get(mutex)).peekFirst();
        }
        assertNotNull(waiter);
        var receiptField = waiter.getClass().getDeclaredField("physicalSettlement");
        receiptField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<Void> physical =
                (java.util.concurrent.CompletableFuture<Void>) receiptField.get(waiter);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        request.get().whenComplete((ignored, error) -> {
            entered.countDown();
            try {
                if (!unblock.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocked subscriber timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        var injection = OresMutex.GuardFuture.class.getDeclaredMethod(
                "completeCancellationFromRuntime", boolean.class);
        injection.setAccessible(true);
        Thread notifier = Thread.ofPlatform().start(() -> {
            try {
                injection.invoke(request.get(), false);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(request.get().isCancelled());
            hostGuard.release(); // Skip terminal queue entry during callback
            assertFalse(physical.isDone(),
                    "stale queue path forged physical Task receipt before notifier drain");
        } finally {
            unblock.countDown();
            notifier.join(5000);
        }
        assertFalse(notifier.isAlive());
        assertNull(failure.get());
        physical.get(5, TimeUnit.SECONDS);
        var next = mutex.tryLock().orElseThrow();
        next.release();
        assertFalse(mutex.isPoisoned());
    }

    @Test
    void losingGrantAndFailureHandoffsWaitForCancellationSubscriberDrain()
            throws Exception {
        // Force the owner-death publication claim to win, then leave its
        // synchronous host cancellation subscriber blocked. Both losing
        // handoff paths must withhold physicalSettlement until it unwinds.
        for (boolean granted : new boolean[] {false, true}) {
            var constructor = OresMutex.GuardFuture.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            OresMutex.GuardFuture<int[]> future =
                    (OresMutex.GuardFuture<int[]>) constructor.newInstance();
            var field = OresMutex.GuardFuture.class.getDeclaredField(
                    "publicationClaimed");
            field.setAccessible(true);
            assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                    field.get(future)).compareAndSet(false, true));
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch unblockCallback = new CountDownLatch(1);
            AtomicReference<Throwable> notifierFailure = new AtomicReference<>();
            future.whenComplete((value, failure) -> {
                callbackEntered.countDown();
                try {
                    if (!unblockCallback.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("callback release timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            var completeCancellation =
                    OresMutex.GuardFuture.class.getDeclaredMethod(
                            "completeCancellationFromRuntime", boolean.class);
            completeCancellation.setAccessible(true);
            Thread cancellingNotifier = Thread.ofPlatform().start(() -> {
                try {
                    completeCancellation.invoke(future, false);
                } catch (Throwable error) {
                    notifierFailure.set(error);
                }
            });
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertTrue(future.isCancelled());
                AtomicBoolean physicallySettled = new AtomicBoolean();
                if (granted) {
                    var mutex = OresMutex.shared(new int[] {9});
                    var guard = mutex.tryLock().orElseThrow();
                    var handoff = OresMutex.GuardFuture.class.getDeclaredMethod(
                            "completeHandoffOnNotifier", OresMutex.Guard.class,
                            Runnable.class, Runnable.class);
                    handoff.setAccessible(true);
                    handoff.invoke(future, guard, (Runnable) guard::release,
                            (Runnable) () -> physicallySettled.set(true));
                    assertTrue(guard.released(),
                            "abandoned physical grant was not returned");
                } else {
                    var handoff = OresMutex.GuardFuture.class.getDeclaredMethod(
                            "failHandoffOnNotifier", Throwable.class,
                            Runnable.class);
                    handoff.setAccessible(true);
                    handoff.invoke(future,
                            new IllegalStateException("claimed failure"),
                            (Runnable) () -> physicallySettled.set(true));
                }
                assertFalse(physicallySettled.get(),
                        "a losing handoff bypassed blocked cancellation callbacks");
                unblockCallback.countDown();
                cancellingNotifier.join(5000);
                assertFalse(cancellingNotifier.isAlive());
                assertNull(notifierFailure.get());
                assertTrue(physicallySettled.get(),
                        "handoff was not retired after cancellation subscribers drained");
            } finally {
                unblockCallback.countDown();
                cancellingNotifier.join(5000);
            }
        }
    }

    @Test
    void taskDoneWaitsForCancelledQueuedWaiterSubscriberDrain() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var mutex = OresMutex.shared(new int[] {8});
            var heldByHost = mutex.tryLock().orElseThrow();
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch finishCallback = new CountDownLatch(1);
            AtomicBoolean carriedTaskAuthority = new AtomicBoolean();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                var requested = mutex.lockAsync();
                requested.whenComplete((guard, failure) -> {
                    carriedTaskAuthority.set(OresScheduler.currentTaskDomain() != null);
                    callbackEntered.countDown();
                    try {
                        if (!finishCallback.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("completion callback timeout");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                return OresScheduler.done(null);
            });
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS),
                        "Task retirement did not cancel its queued waiter");
                assertFalse(task.getDone().isDone(),
                        "DONE published before host callback physically returned");
            } finally {
                finishCallback.countDown();
                heldByHost.release();
            }
            assertEquals(new TaskHandle.Success<Void>(null),
                    task.getDone().get(5, TimeUnit.SECONDS));
            assertFalse(carriedTaskAuthority.get(),
                    "queued cancellation callback inherited dead Task authority");
            assertFalse(mutex.isPoisoned(),
                    "a cancelled never-granted lock request cannot poison");
        }
    }

    @Test
    void queuedAsyncLockRequestIsCancelledAtTaskQuiescence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {12});
            var holder = mutex.tryLock().orElseThrow();
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    pending = new AtomicReference<>();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                pending.set(mutex.lockAsync());
                assertFalse(pending.get().isDone());
                return OresScheduler.done(null); // no explicit cancel/defer
            });
            try {
                assertEquals(new TaskHandle.Success<Void>(null),
                        task.getDone().get(3, TimeUnit.SECONDS));
                assertNotNull(pending.get());
                assertTrue(pending.get().isCancelled(),
                        "Task exited but its queued lock request survived");
            } finally {
                holder.release();
            }
            var subsequent = mutex.tryLock().orElseThrow();
            subsequent.release();
            assertFalse(mutex.isPoisoned(),
                    "a never-granted waiter must not poison shared state");
        }
    }

    @Test
    void asyncHandoffRegistersGrantWithOriginatingTaskNotReleasingHostThread()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{3});
            var hostGuard = mutex.tryLock().orElseThrow();
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch allowTaskToObserve = new CountDownLatch(1);
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    pending = new AtomicReference<>();
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                pending.set(mutex.lockAsync());
                requested.countDown();
                try {
                    if (!allowTaskToObserve.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("handoff barrier exceeded");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                var granted = pending.get().getNow(null);
                assertNotNull(granted, "host release did not publish a guard");
                try {
                    granted.value()[0] = 8;
                    return OresScheduler.done(granted.value()[0]);
                } finally {
                    granted.release();
                }
            });
            try {
                assertTrue(requested.await(3, TimeUnit.SECONDS));
                assertFalse(pending.get().isDone());
                hostGuard.release(); // public callbacks must run off this carrier
                assertNotNull(pending.get().get(3, TimeUnit.SECONDS));
            } finally {
                allowTaskToObserve.countDown();
            }
            assertEquals(new TaskHandle.Success<>(8),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(mutex.isPoisoned());
            var check = mutex.tryLock().orElseThrow();
            assertEquals(8, check.value()[0]);
            check.release();
        }
    }

    @Test
    void blockedHostSubscribersCannotStarveUnrelatedMutexHandoffs()
            throws Exception {
        final int blocked = 4;
        java.util.List<OresMutex.Shared<int[]>> mutexes = new java.util.ArrayList<>();
        java.util.List<OresMutex.Guard<int[]>> originals = new java.util.ArrayList<>();
        java.util.List<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                waiting = new java.util.ArrayList<>();
        CountDownLatch allBlocked = new CountDownLatch(blocked);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        try {
            for (int i = 0; i < blocked; i++) {
                var mutex = OresMutex.shared(new int[] {i});
                mutexes.add(mutex);
                originals.add(mutex.tryLock().orElseThrow());
                AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                        requested = new AtomicReference<>();
                Thread requester = Thread.ofPlatform().start(
                        () -> requested.set(mutex.lockAsync()));
                requester.join(3000);
                assertFalse(requester.isAlive(), "async request did not return");
                var pending = requested.get();
                assertNotNull(pending);
                pending.whenComplete((guard, error) -> {
                    allBlocked.countDown();
                    try {
                        if (!releaseCallbacks.await(3, TimeUnit.SECONDS))
                            throw new IllegalStateException("callback release timed out");
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                });
                waiting.add(pending);
            }
            for (var original : originals) original.release();
            assertTrue(allBlocked.await(3, TimeUnit.SECONDS),
                    "notifier did not admit independent blocked callbacks");

            var separate = OresMutex.shared(new int[] {99});
            var first = separate.tryLock().orElseThrow();
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    extra = new AtomicReference<>();
            Thread additional = Thread.ofPlatform().start(
                    () -> extra.set(separate.lockAsync()));
            additional.join(3000);
            assertFalse(additional.isAlive(), "additional async request blocked");
            var future = extra.get();
            assertNotNull(future);
            first.release();
            // A fixed four-worker pool would strand this fifth handoff while
            // all workers are running blocked non-Async host callbacks.
            var grant = future.get(3, TimeUnit.SECONDS);
            assertEquals(99, grant.value()[0]);
            grant.release();
        } finally {
            releaseCallbacks.countDown();
        }
        for (var future : waiting) {
            var guard = future.get(3, TimeUnit.SECONDS);
            guard.release();
        }
        for (var mutex : mutexes) {
            assertFalse(mutex.isPoisoned());
        }
    }

    @Test
    void blockedHostFutureSubscriberDoesNotParkReleasingCarrierOrForgeTaskDone()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {4});
            var original = mutex.tryLock().orElseThrow();
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch allowTaskExit = new CountDownLatch(1);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch unblockCallback = new CountDownLatch(1);
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    pending = new AtomicReference<>();
            AtomicBoolean callbackRanOnTask = new AtomicBoolean();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                pending.set(mutex.lockAsync());
                requested.countDown();
                try {
                    if (!allowTaskExit.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("waiting for Task exit timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return OresScheduler.done(null);
            });
            try {
                assertTrue(requested.await(3, TimeUnit.SECONDS));
                pending.get().whenComplete((guard, failure) -> {
                    callbackRanOnTask.set(OresScheduler.currentTaskDomain() != null
                            || ActorRuntime.inActorExecution());
                    callbackEntered.countDown();
                    try {
                        if (!unblockCallback.await(3, TimeUnit.SECONDS))
                            throw new IllegalStateException("notification blocker timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                });
                // Must return promptly, not execute callback on this host
                // mutex-releaser. The notifier runs it separately.
                original.release();
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                allowTaskExit.countDown();
                // CompletableFuture.isDone does NOT establish that host
                // subscribers or the physical handoff have unwound.
                assertFalse(task.getDone().isDone(),
                        "Task DONE must wait behind claimed handoff receipt");
            } finally {
                allowTaskExit.countDown();
                unblockCallback.countDown();
            }
            assertEquals(new TaskHandle.Success<Void>(null),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(callbackRanOnTask.get(),
                    "host Future callback inherited guest Task authority");
            assertTrue(mutex.isPoisoned(),
                    "Task exit with an unreleased writer must poison state");
        }
    }

    @Test
    void queuedAsyncTimeoutSettlesPhysicalWaitAndQuotaBeforePublication()
            throws Exception {
        OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{0});
        var holder = mutex.tryLock().orElseThrow();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                pending = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(() ->
                pending.set(mutex.lockAsyncFor(java.time.Duration.ofMillis(100))));
        requester.join();
        try {
            java.util.concurrent.atomic.AtomicBoolean settledBeforeCallback =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.lang.reflect.Field field =
                    OresMutex.Shared.class.getDeclaredField("asyncWaiters");
            field.setAccessible(true);
            pending.get().whenComplete((ignored, failure) -> {
                try {
                    int queued = ((java.util.concurrent.atomic.AtomicInteger)
                            field.get(mutex)).get();
                    settledBeforeCallback.set(queued == 0 && failure != null);
                } catch (IllegalAccessException error) {
                    throw new AssertionError(error);
                }
            });
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.get().get(3, TimeUnit.SECONDS));
            assertTrue(settledBeforeCallback.get(),
                    "future timeout published before physical waiter settlement");
        } finally {
            holder.release();
        }
        var next = mutex.tryLock().orElseThrow();
        next.release();
    }

    @Test
    void asyncCancelVsHandoffEitherGrantsOrCancelsWithoutPermitLeak()
            throws Exception {
        for (int round = 0; round < 100; round++) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{round});
            var holder = mutex.tryLock().orElseThrow();
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    pending = new AtomicReference<>();
            Thread requester = Thread.ofPlatform().start(() ->
                    pending.set(mutex.lockAsync()));
            requester.join();
            assertFalse(pending.get().isDone());
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread cancellation = Thread.ofPlatform().start(() -> {
                try {
                    if (!go.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("cancel barrier");
                    pending.get().cancel(false);
                } catch (Throwable error) {
                    unexpected.set(error);
                }
            });
            // Release from the acquiring host thread. A foreign host
            // carrier must not be able to close that thread's live guard.
            go.countDown();
            try {
                holder.release();
            } finally {
                cancellation.join();
            }
            assertNull(unexpected.get());
            // A handoff now posts public notification off the releasing
            // carrier. Wait for that bounded asynchronous publication.
            try {
                var grant = pending.get().get(3, TimeUnit.SECONDS);
                assertFalse(grant.released());
                grant.release();
            } catch (java.util.concurrent.CancellationException expected) {
                assertTrue(pending.get().isCancelled());
            }
            assertTrue(pending.get().isDone());
            var next = mutex.tryLock().orElseThrow();
            next.release();
        }
    }

    @Test
    void cancelledRunningTaskUnlinksAsyncWaiterOnlyAfterQuiescence()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{1});
            var holder = mutex.tryLock().orElseThrow();
            CountDownLatch waiting = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>
                    pending = new AtomicReference<>();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                pending.set(mutex.lockAsync());
                waiting.countDown();
                try {
                    if (!exit.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("quiescence barrier exceeded");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return OresScheduler.done(null);
            });
            try {
                assertTrue(waiting.await(3, TimeUnit.SECONDS));
                assertTrue(task.cancel("shutdown"));
                assertFalse(task.getDone().isDone());
                assertFalse(pending.get().isDone(),
                        "physical Task execution must remain alive until exit");
            } finally {
                exit.countDown();
                holder.release();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            // Handoff may race with actor exit. Either the queued future was
            // cancelled or a grant was rejected and failed closed; no
            // outstanding guard or live permit may survive Task DONE.
            assertTrue(pending.get().isDone());
            var next = mutex.tryLock().orElseThrow();
            next.release();
        }
    }

    @Test
    void unreleasedSharedGuardIsRevokedAndPoisonedBeforeTaskDone() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {0});
            AtomicReference<OresMutex.Guard<int[]>> retained = new AtomicReference<>();
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 11;
                retained.set(guard); // simulates a leaked lexical guard
                return OresScheduler.done(11);
            });
            assertEquals(new TaskHandle.Success<>(11),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(retained.get().released(), "Task DONE preceded guard revocation");
            assertThrows(IllegalStateException.class, retained.get()::value);
            assertTrue(mutex.isPoisoned(), "abandoned mutable state must fail closed");
            assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);
            mutex.recover(value -> { value[0] = 0; return null; });
            var next = mutex.tryLock().orElseThrow();
            assertEquals(0, next.value()[0]);
            next.release();
        }
    }

    @Test
    void explicitlyReleasedSharedGuardIsNotPoisonedByTaskRetirement() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {0});
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 7;
                guard.release();
                return OresScheduler.done(7);
            });
            assertEquals(new TaskHandle.Success<>(7),
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(mutex.isPoisoned());
            var next = mutex.tryLock().orElseThrow();
            assertEquals(7, next.value()[0]);
            next.release();
        }
    }

    @Test
    void cancellationNeverUnlocksRunningTaskBeforeCarrierQuiescence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[] {1});
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch leave = new CountDownLatch(1);
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                var guard = mutex.tryLock().orElseThrow();
                entered.countDown();
                try {
                    if (!leave.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                guard.value()[0] = 4; // old owner is still executing
                return OresScheduler.done(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(task.cancel("stop"));
                assertTrue(mutex.tryLock().isEmpty(), "permit stolen from live carrier");
                assertFalse(task.getDone().isDone(), "DONE before physical exit");
            } finally {
                leave.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(mutex.isPoisoned());
            mutex.recover(value -> { value[0] = 0; return null; });
            var guard = mutex.tryLock().orElseThrow();
            guard.release();
        }
    }

    @Test
    void cancelledTaskCannotAcquireAnotherPermitAfterAdmissionFence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresMutex.Shared<int[]> mutex = OresMutex.shared(new int[]{2});
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch leave = new CountDownLatch(1);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            TaskHandle<Void> task = scheduler.startTracked(resume -> {
                entered.countDown();
                try {
                    if (!leave.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("test barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                try {
                    var guard = mutex.tryLock().orElseThrow();
                    guard.release();
                } catch (Throwable failure) {
                    rejected.set(failure);
                }
                return OresScheduler.done(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(task.cancel("stop"));
            } finally {
                leave.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, rejected.get());
            assertFalse(mutex.isPoisoned(), "failed acquisition has no dirty writer");
            var next = mutex.tryLock().orElseThrow();
            next.release();
        }
    }

    @Test
    void trackedPermitsRetireInLifoOrderWithoutRetainingReleasedGuards() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        java.util.List<Integer> releaseOrder = new java.util.ArrayList<>();
        TaskOwnedLockLedger.Lease first = () -> releaseOrder.add(1);
        TaskOwnedLockLedger.Lease second = () -> releaseOrder.add(2);
        TaskOwnedLockLedger.Lease third = () -> releaseOrder.add(3);
        ledger.register(first);
        ledger.register(second);
        ledger.register(third);
        ledger.forget(second); // ordinary explicit guard close
        assertEquals(2, ledger.outstanding());
        ledger.revokeAfterQuiescence();
        assertEquals(java.util.List.of(3, 1), releaseOrder);
        assertEquals(0, ledger.outstanding());
        ledger.revokeAfterQuiescence(); // exactly once
        assertEquals(java.util.List.of(3, 1), releaseOrder);
    }

    @Test
    void hostileTaskCannotRegisterUnboundedPermits() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        for (int i = 0; i < 4096; i++) {
            final int identity = i;
            ledger.register(() -> { if (identity < 0) throw new AssertionError(); });
        }
        assertThrows(IllegalStateException.class, () -> ledger.register(() -> {}));
        assertEquals(4096, ledger.outstanding());
    }

    @Test
    void registerVersusCancellationFenceNeverLosesOwnedPermit() throws Exception {
        for (int round = 0; round < 150; round++) {
            TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
            java.util.concurrent.atomic.AtomicInteger revoked =
                    new java.util.concurrent.atomic.AtomicInteger();
            AtomicBoolean registered = new AtomicBoolean();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            CountDownLatch go = new CountDownLatch(1);
            Thread admission = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    ledger.register(revoked::incrementAndGet);
                    registered.set(true);
                } catch (CancellationException expected) {
                    // STOP won before permit registration.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    unexpected.compareAndSet(null, error);
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    ledger.fence();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    unexpected.compareAndSet(null, error);
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            go.countDown();
            admission.join();
            canceller.join();
            assertNull(unexpected.get(), "unexpected racing worker failure");
            ledger.revokeAfterQuiescence();
            assertEquals(0, ledger.outstanding());
            assertEquals(registered.get() ? 1 : 0, revoked.get(),
                    "a granted permit must be reclaimed exactly once");
        }
    }

    @Test
    void repeatedOrReentrantTaskRetirementUsesSamePhysicalReceipt() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        java.util.concurrent.CompletableFuture<Void> physical =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger revokeCount =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>
                nested = new java.util.concurrent.atomic.AtomicReference<>();
        ledger.register(new TaskOwnedLockLedger.Lease() {
            @Override
            public void revokeAfterQuiescence() {
                throw new AssertionError("async permit should not use legacy cleanup");
            }
            @Override
            public java.util.concurrent.CompletionStage<Void> settleAfterQuiescence() {
                revokeCount.incrementAndGet();
                nested.set(ledger.retireAfterQuiescence());
                return physical;
            }
        });
        var first = ledger.retireAfterQuiescence();
        var second = ledger.retireAfterQuiescence();
        assertSame(first, second, "STOP must share one pending retirement receipt");
        assertSame(first, nested.get(), "reentrant revoker must see original receipt");
        assertEquals(1, revokeCount.get(), "permit revoked more than once");
        assertFalse(first.isDone(), "DONE before physical handoff settled");
        physical.complete(null);
        first.join();
        assertEquals(0, ledger.outstanding());
        assertEquals(1, revokeCount.get());
    }

    @Test
    void reentrantActorTaskCleanupDoesNotDuplicatePermitRevocation() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        java.util.concurrent.CompletableFuture<Void> physical =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>
                nested = new java.util.concurrent.atomic.AtomicReference<>();
        ledger.register(new TaskOwnedLockLedger.Lease() {
            @Override public void revokeAfterQuiescence() {
                throw new AssertionError("unexpected synchronous revocation");
            }
            @Override public java.util.concurrent.CompletionStage<Void>
                    settleAfterQuiescence() {
                attempts.incrementAndGet();
                nested.set(ledger.retireAfterQuiescence());
                return physical;
            }
        });
        var first = ledger.retireAfterQuiescence();
        assertSame(first, nested.get());
        assertSame(first, ledger.retireAfterQuiescence());
        assertEquals(1, attempts.get());
        assertFalse(first.isDone());
        physical.complete(null);
        first.join();
        assertEquals(0, ledger.outstanding());
    }

    @Test
    void ledgerRevocationRunsOutsideMonitorAndRetainsFailedPermit() {
        TaskOwnedLockLedger ledger = new TaskOwnedLockLedger();
        AtomicBoolean outsideMonitor = new AtomicBoolean();
        TaskOwnedLockLedger.Lease successful = () ->
                outsideMonitor.set(!Thread.holdsLock(ledger));
        TaskOwnedLockLedger.Lease failed = () -> {
            throw new IllegalStateException("failed to revoke");
        };
        ledger.register(successful);
        ledger.register(failed);
        ledger.fence();
        assertThrows(CancellationException.class, () -> ledger.register(() -> {}));
        assertThrows(IllegalStateException.class, ledger::revokeAfterQuiescence);
        assertTrue(outsideMonitor.get());
        assertEquals(1, ledger.outstanding(), "failed permit must stay quarantined");
    }
}
