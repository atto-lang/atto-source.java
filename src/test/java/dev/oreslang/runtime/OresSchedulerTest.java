package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerTest {

    @Test
    void rejectedGuestContextAdmissionSettlesTheOwningTask() throws Exception {
        IllegalStateException failure = new IllegalStateException("guest context entry rejected");
        try (OresScheduler scheduler = OresScheduler.managed(1, runnable -> { throw failure; })) {
            OresFuture<Integer> task = scheduler.start(resume -> OresScheduler.done(42));
            var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertSame(failure, observed.getCause());
        }
    }

    @Test
    void mixedLinkageScopeSurvivesResumeWithoutLeakingToOtherTasks() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicInteger state = new AtomicInteger();
            OresFuture<Object> task;
            try (var scope = dev.oreslang.interop.MixedInteropBridge.open(java.util.Map.of(
                    "unit", (name, args) -> 42))) {
                task = scheduler.start(resume -> {
                    if (state.getAndIncrement() == 0) return OresScheduler.await(gate);
                    return OresScheduler.done(dev.oreslang.interop.MixedInteropBridge.invoke("unit", "answer"));
                });
            }
            gate.completeFromRuntime(1);
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertTrue(dev.oreslang.interop.MixedInteropBridge.capture().isEmpty());
            assertTrue(scheduler.start(resume -> OresScheduler.done(
                    dev.oreslang.interop.MixedInteropBridge.capture().isEmpty())).get(5, TimeUnit.SECONDS));
        }
    }

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
            assertEquals(
                    0,
                    completed.pendingRuntimeWaiterCount(),
                    "awaiting an already-settled Future must not retain a claimed continuation waiter");
        }
    }

    @Test
    void completedAwaitMayReuseSameCarrierButAlwaysGetsFreshDispatch() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> completed = OresFuture.completed(7);
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();
            AtomicReference<Long> firstDispatch = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    firstCarrier.set(Thread.currentThread());
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    assertNotEquals(0L, firstDispatch.get().longValue());
                    return OresScheduler.await(completed);
                }

                // A one-thread pool guarantees physical carrier reuse. The
                // logical scheduler dispatch must nevertheless be new.
                assertSame(firstCarrier.get(), Thread.currentThread());
                assertNotEquals(
                        firstDispatch.get().longValue(),
                        OresScheduler.currentDispatchId(),
                        "await must unwind and re-enter through a fresh scheduler dispatch");
                assertEquals(7, resume.value());
                return OresScheduler.done(8);
            });

            assertEquals(8, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void manyCompletedAwaitsStayStacklessAndRequireFreshDispatches() throws Exception {
        final int awaits = 512;
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger state = new AtomicInteger();
            AtomicLong previousDispatch = new AtomicLong();

            OresFuture<Integer> result = scheduler.start(resume -> {
                long dispatch = OresScheduler.currentDispatchId();
                assertNotEquals(0L, dispatch);
                long prior = previousDispatch.getAndSet(dispatch);
                if (prior != 0L) {
                    assertNotEquals(
                            prior,
                            dispatch,
                            "every await must re-enter through a fresh scheduler dispatch");
                }

                int step = state.getAndIncrement();
                if (step < awaits) {
                    OresFuture<Integer> completed = OresFuture.completed(step);
                    assertEquals(0, completed.pendingRuntimeWaiterCount());
                    return OresScheduler.await(completed);
                }

                assertEquals(awaits - 1, resume.value());
                return OresScheduler.done(step);
            });

            assertEquals(awaits, result.get(10, TimeUnit.SECONDS));
            assertEquals(awaits + 1, state.get());
        }
    }

    @Test
    void cooperateAlwaysCreatesFreshDispatchWithoutAllocatingFuture() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger pc = new AtomicInteger();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicReference<Object> firstTaskDomain = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int step = pc.getAndIncrement();
                if (step == 0) {
                    assertTrue(resume.initial());
                    firstCarrier.set(Thread.currentThread());
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    firstTaskDomain.set(OresScheduler.currentTaskDomain());
                    return OresScheduler.cooperate();
                }

                assertFalse(resume.initial());
                assertNull(resume.value());
                assertNull(resume.failure());
                assertSame(firstCarrier.get(), Thread.currentThread(),
                        "one-carrier scheduler may reuse the same physical carrier");
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId(),
                        "cooperate must resume through a fresh logical dispatch");
                assertSame(firstTaskDomain.get(), OresScheduler.currentTaskDomain(),
                        "cooperate must preserve logical task identity");
                return OresScheduler.done(42);
            });

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void manyCooperatesRemainStacklessAndMakeProgress() throws Exception {
        final int cooperates = 1024;
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger pc = new AtomicInteger();
            AtomicLong previousDispatch = new AtomicLong();

            OresFuture<Integer> result = scheduler.start(resume -> {
                long dispatch = OresScheduler.currentDispatchId();
                long prior = previousDispatch.getAndSet(dispatch);
                if (prior != 0L) {
                    assertNotEquals(prior, dispatch,
                            "every cooperate must establish a fresh dispatch");
                }

                int step = pc.getAndIncrement();
                if (step < cooperates) return OresScheduler.cooperate();
                return OresScheduler.done(step);
            });

            assertEquals(cooperates, result.get(10, TimeUnit.SECONDS));
            assertEquals(cooperates + 1, pc.get());
        }
    }

    @Test
    void defaultCooperateQueuesBehindAlreadyRunnablePeerWork() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            java.util.List<String> order =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            CountDownLatch firstTurnEntered = new CountDownLatch(1);
            CountDownLatch peerQueued = new CountDownLatch(1);
            AtomicInteger aPc = new AtomicInteger();

            OresFuture<Void> a = scheduler.start(resume -> {
                if (aPc.getAndIncrement() == 0) {
                    order.add("a1");
                    firstTurnEntered.countDown();
                    assertTrue(peerQueued.await(5, TimeUnit.SECONDS));
                    return OresScheduler.cooperate();
                }
                order.add("a2");
                return OresScheduler.done(null);
            });

            assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> b = scheduler.start(resume -> {
                order.add("b");
                return OresScheduler.done(null);
            });
            peerQueued.countDown();

            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(java.util.List.of("a1", "b", "a2"), order,
                    "default cooperate must requeue behind already-runnable work");
        }
    }

    @Test
    void runtimeOwnedCompletionPublishesOnlyAfterGuestTurnAdmissionExits() throws Exception {
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        AtomicBoolean insideGuestTurn = new AtomicBoolean();
        CountDownLatch completionObserved = new CountDownLatch(1);
        AtomicBoolean completionPublishedInsideGuestTurn = new AtomicBoolean();

        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "test-runtime-owned",
                1,
                carrier,
                turn -> {
                    assertFalse(
                            insideGuestTurn.get(),
                            "runtime scheduler guest turns must not nest context admission");
                    insideGuestTurn.set(true);
                    try {
                        turn.run();
                    } finally {
                        insideGuestTurn.set(false);
                    }
                })) {
            OresFuture<Integer> result =
                    scheduler.start(resume -> OresScheduler.done(42));

            result.whenCompleteRuntime((value, failure) -> {
                completionPublishedInsideGuestTurn.set(insideGuestTurn.get());
                completionObserved.countDown();
            });

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertTrue(completionObserved.await(5, TimeUnit.SECONDS));
            assertFalse(
                    completionPublishedInsideGuestTurn.get(),
                    "terminal Future publication must happen only after guest/context exit");
        } finally {
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
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
    void logicalTaskDomainSurvivesAwaitButIsDistinctPerTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<Object> firstDomain = new AtomicReference<>();
            AtomicReference<Object> resumedDomain = new AtomicReference<>();
            AtomicReference<Object> secondTaskDomain = new AtomicReference<>();
            AtomicInteger firstPc = new AtomicInteger();

            OresFuture<Integer> first = scheduler.start(resume -> {
                if (firstPc.getAndIncrement() == 0) {
                    firstDomain.set(OresScheduler.currentTaskDomain());
                    assertNotNull(firstDomain.get());
                    return OresScheduler.await(gate);
                }
                resumedDomain.set(OresScheduler.currentTaskDomain());
                return OresScheduler.done(1);
            });

            OresFuture<Integer> second = scheduler.start(resume -> {
                secondTaskDomain.set(OresScheduler.currentTaskDomain());
                return OresScheduler.done(2);
            });

            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            gate.completeFromRuntime(0);
            assertEquals(1, first.get(5, TimeUnit.SECONDS));

            assertSame(firstDomain.get(), resumedDomain.get(),
                    "await/resume must preserve the logical task execution domain");
            assertNotSame(firstDomain.get(), secondTaskDomain.get(),
                    "two tasks on one scheduler must not share mutex/borrow ownership");
        }
    }

    @Test
    void schedulerCannotCloseItselfFromOwnTaskTurn() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Boolean> result = scheduler.startSync(() -> {
                IllegalStateException failure =
                        assertThrows(IllegalStateException.class, scheduler::close);
                return failure.getMessage().contains("outside/root");
            });

            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertFalse(scheduler.isClosed(),
                    "failed self-close must leave scheduler usable");
        } finally {
            scheduler.close();
        }
    }


    /**
     * Models a producer that claimed its first registration but had not yet
     * invoked the callback when the task started waiting on a second Future.
     * A detach alone cannot revoke an already-claimed registration.
     */
    @Test
    void staleAlreadyClaimedCallbackCannotSatisfyAnotherAwait() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> first = new OresFuture<>();
            OresFuture<Integer> second = new OresFuture<>();
            AtomicInteger segments = new AtomicInteger();
            OresFuture<Integer> task = scheduler.start(resume -> {
                int pc = segments.getAndIncrement();
                if (pc == 0) return OresScheduler.await(first);
                if (pc == 1) {
                    assertEquals(7, resume.value());
                    return OresScheduler.await(second);
                }
                assertEquals(2, pc, "a stale callback executed an extra task segment");
                assertEquals(11, resume.value(),
                        "an earlier await callback borrowed the new suspension");
                return OresScheduler.done((Integer) resume.value());
            });

            awaitOneWaiter(first);
            java.util.function.BiConsumer<Object, Throwable> stale =
                    capturedWaiterCallback(first);
            assertTrue(first.completeFromRuntime(7));
            awaitOneWaiter(second);

            // This is the exact race that detaching a registration does not
            // exclude: its notification was already claimed before detachment.
            stale.accept(99, null);
            stale.accept(null, new IllegalStateException("stale producer failure"));
            assertFalse(task.isDone(),
                    "the second await must remain blocked by its own producer");
            assertEquals(2, segments.get());

            assertTrue(second.completeFromRuntime(11));
            assertEquals(11, task.get(5, TimeUnit.SECONDS));
            assertEquals(3, segments.get());
        }
    }

    @Test
    void staleAwaitCallbackCannotReenterAfterSchedulerCancellation() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresScheduler scheduler = new OresScheduler(1);
        AtomicInteger segments = new AtomicInteger();
        try {
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (segments.getAndIncrement() == 0) return OresScheduler.await(source);
                throw new AssertionError("cancelled task resumed through stale producer");
            });
            awaitOneWaiter(source);
            java.util.function.BiConsumer<Object, Throwable> stale =
                    capturedWaiterCallback(source);
            scheduler.close();
            assertTrue(task.isCancelled());
            stale.accept(19, null);
            stale.accept(null, new IllegalStateException("late failure"));
            assertEquals(1, segments.get());
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(source.isDone(), "cancellation cannot poison a shared producer");
        } finally {
            scheduler.close();
        }
    }

    private static void awaitOneWaiter(OresFuture<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() != 1
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, future.pendingRuntimeWaiterCount(),
                "scheduler did not suspend on the expected Future");
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.BiConsumer<Object, Throwable> capturedWaiterCallback(
            OresFuture<?> future) throws Exception {
        // Test-only forced delivery of a callback already claimed by the
        // producer. Never expose this callback to guest code or host APIs.
        var waitersField = OresFuture.class.getDeclaredField("waiters");
        waitersField.setAccessible(true);
        var waiters = (java.util.Queue<?>) waitersField.get(future);
        Object registered = waiters.peek();
        assertNotNull(registered);
        var callbackField = registered.getClass().getDeclaredField("callback");
        callbackField.setAccessible(true);
        return (java.util.function.BiConsumer<Object, Throwable>) callbackField.get(registered);
    }

    @Test
    void closingSchedulerDetachesSuspendedWaiterWithoutCancellingSharedFuture() throws Exception {
        OresFuture<Integer> shared = new OresFuture<>();
        OresScheduler scheduler = new OresScheduler(1);
        AtomicInteger pc = new AtomicInteger();

        OresFuture<Integer> task = scheduler.start(resume -> {
            if (pc.getAndIncrement() == 0) {
                return OresScheduler.await(shared);
            }
            return OresScheduler.done((Integer) resume.value());
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (shared.pendingRuntimeWaiterCount() != 1
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, shared.pendingRuntimeWaiterCount());

        scheduler.close();

        assertTrue(task.isCancelled());
        assertEquals(0, shared.pendingRuntimeWaiterCount(),
                "closing the scheduler must detach its continuation waiter");
        assertFalse(shared.isDone(),
                "detaching a waiter must not cancel the shared producer Future");

        assertTrue(shared.completeFromRuntime(9));
        assertEquals(1, pc.get(),
                "detached continuation must never resume after producer completion");
    }
}
