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

    @Test
    void closeFromTerminalFutureCallbackOnOwnCarrierFailsPromptlyWithoutClosing()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> source = new OresFuture<>();
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try {
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(source);
                return OresScheduler.done(42);
            });
            result.whenCompleteRuntime((value, failure) -> {
                try {
                    assertNull(failure);
                    assertEquals(42, value);
                    assertNull(OresScheduler.current(),
                            "guest task binding should already be restored");
                    IllegalStateException rejected = assertThrows(
                            IllegalStateException.class, scheduler::close);
                    assertTrue(rejected.getMessage().contains("terminal Future callback"),
                            rejected.getMessage());
                    assertFalse(scheduler.isClosed(),
                            "a rejected self-close must not poison host shutdown");
                } catch (Throwable problem) {
                    callbackFailure.set(problem);
                } finally {
                    notified.countDown();
                }
            });
            assertTrue(source.completeFromRuntime(1));
            assertTrue(notified.await(3, TimeUnit.SECONDS),
                    "terminal callback must not block on its own carrier");
            assertNull(callbackFailure.get());
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void terminalCallbackSelfCloseCannotBlockBehindConcurrentHostClose()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> upstream = new OresFuture<>();
        CountDownLatch callbackFinished = new CountDownLatch(1);
        CountDownLatch hostCloseFinished = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<Throwable> hostCloseFailure = new AtomicReference<>();
        AtomicInteger cleanupTurns = new AtomicInteger();

        OresFuture<Integer> task = scheduler.start(
                new OresScheduler.CancellationAwareTask<>() {
                    @Override public boolean cleanupInProgress() { return false; }
                    @Override public void cancellationRequested(
                            OresScheduler.TaskCancelledException reason) { }
                    @Override public OresScheduler.Step<Integer> resume(
                            OresScheduler.Resume input) {
                        if (input.initial()) return OresScheduler.await(upstream);
                        assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                input.failure());
                        cleanupTurns.incrementAndGet();
                        return OresScheduler.done(41);
                    }
                });
        task.whenCompleteRuntime((value, failure) -> {
            try {
                assertInstanceOf(OresScheduler.TaskCancelledException.class, failure);
                assertTrue(scheduler.isClosed(),
                        "host shutdown owns the scheduler before terminal callback");
                assertNull(OresScheduler.current(),
                        "terminal callback runs after guest turn was unbound");
                var rejected = assertThrows(IllegalStateException.class,
                        scheduler::close);
                assertTrue(rejected.getMessage().contains("terminal Future callback"));
            } catch (Throwable problem) {
                callbackFailure.set(problem);
            } finally {
                callbackFinished.countDown();
            }
        });

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (upstream.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, upstream.pendingRuntimeWaiterCount());

            Thread closer = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable problem) {
                    hostCloseFailure.set(problem);
                } finally {
                    hostCloseFinished.countDown();
                }
            });

            assertTrue(callbackFinished.await(4, TimeUnit.SECONDS),
                    "scheduler callback must reject self-close before acquiring the close lock");
            assertTrue(hostCloseFinished.await(4, TimeUnit.SECONDS),
                    "host close must drain without deadlocking terminal callback");
            closer.join(1000);
            assertNull(callbackFailure.get());
            assertNull(hostCloseFailure.get());
            assertTrue(task.isCancelled());
            assertEquals(1, cleanupTurns.get());
            assertFalse(upstream.isDone(), "cancellation does not cancel shared producer");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void cancellingUnclaimedQueuedTaskNeverEntersVmAndSettlesWithoutCarrier() throws Exception {
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger contextEntries = new AtomicInteger();
        AtomicInteger guestRuns = new AtomicInteger();
        carrier.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "queued-cancel-context-fence", 1, carrier,
                turn -> {
                    contextEntries.incrementAndGet();
                    turn.run();
                });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Integer> task = scheduler.start(input -> {
                guestRuns.incrementAndGet();
                return OresScheduler.done(42);
            });
            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled(),
                    "revoked unclaimed carrier must allow immediate cancellation");
            assertEquals(0, contextEntries.get());
            release.countDown();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(0, contextEntries.get(),
                    "revoked queued carrier must never enter the VM context");
            assertEquals(0, guestRuns.get());
        } finally {
            release.countDown();
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
        }
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

    @Test
    void cancellationReentersOwningLaneAndPublishesAfterAwaitedCleanup() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Void> cleanupGate = new OresFuture<>();
            AtomicInteger cleanupEntries = new AtomicInteger();
            AtomicReference<OresScheduler> cleanupLane = new AtomicReference<>();
            CountDownLatch enteredAwait = new CountDownLatch(1);

            OresScheduler.CancellationAwareTask<Integer> work =
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cleaning;
                        private volatile boolean cancellationRequested;

                        @Override
                        public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) {
                            cancellationRequested = true;
                        }

                        @Override
                        public boolean cleanupInProgress() {
                            return cleaning;
                        }

                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume input) {
                            if (input.initial()) {
                                enteredAwait.countDown();
                                return OresScheduler.await(source);
                            }
                            if (!cleaning) {
                                assertTrue(cancellationRequested);
                                assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                        input.failure());
                                cleaning = true;
                                cleanupLane.set(OresScheduler.current());
                                cleanupEntries.incrementAndGet();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertNull(input.failure());
                            return OresScheduler.done(42);
                        }
                    };

            OresFuture<Integer> task = scheduler.start(work);
            assertTrue(enteredAwait.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, source.pendingRuntimeWaiterCount());

            assertTrue(task.cancel(false));
            assertFalse(task.cancel(false), "a second cancellation cannot re-enter cleanup");
            assertFalse(task.isDone(), "task cancellation is not terminal before cleanup");
            assertFalse(source.isCancelled(), "shared producer remains independent");

            assertTrue(cleanupGate.completeFromRuntime(null));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertSame(scheduler, cleanupLane.get());
            assertEquals(1, cleanupEntries.get());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void schedulerCloseDrainsAnAdmittedCancellationContinuation() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> awaited = new OresFuture<>();
        AtomicInteger cleaned = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        OresFuture<Integer> task = scheduler.start(
                new OresScheduler.CancellationAwareTask<>() {
                    @Override public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) { }
                    @Override public boolean cleanupInProgress() { return false; }
                    @Override public OresScheduler.Step<Integer> resume(
                            OresScheduler.Resume input) {
                        if (input.initial()) {
                            started.countDown();
                            return OresScheduler.await(awaited);
                        }
                        assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                input.failure());
                        cleaned.incrementAndGet();
                        return OresScheduler.done(12);
                    }
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        scheduler.close();
        assertTrue(task.isCancelled());
        assertEquals(1, cleaned.get());
        assertFalse(awaited.isDone());
    }

    @Test
    void cancellationDuringSuspendedCleanupWaitsForOwningLaneAndNeverReplaysIt()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> normalWork = new OresFuture<>();
            OresFuture<Integer> cleanupGate = new OresFuture<>();
            CountDownLatch enteredCleanup = new CountDownLatch(1);
            AtomicInteger cleanupEntries = new AtomicInteger();
            AtomicReference<OresScheduler> cleanupLane = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cleaning;
                        private volatile boolean cancelSeen;

                        @Override
                        public boolean cleanupInProgress() {
                            return cleaning;
                        }

                        @Override
                        public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) {
                            cancelSeen = true;
                        }

                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (resume.initial()) {
                                return OresScheduler.await(normalWork);
                            }
                            if (!cleaning) {
                                assertNull(resume.failure());
                                assertEquals(12, resume.value());
                                cleaning = true;
                                cleanupLane.set(OresScheduler.current());
                                cleanupEntries.incrementAndGet();
                                enteredCleanup.countDown();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertTrue(cancelSeen);
                            assertNull(resume.failure(),
                                    "cancellation may not replace an admitted cleanup await");
                            assertEquals(7, resume.value());
                            assertSame(cleanupLane.get(), OresScheduler.current());
                            return OresScheduler.done(42);
                        }
                    });

            assertTrue(normalWork.completeFromRuntime(12));
            assertTrue(enteredCleanup.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cleanupGate.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, cleanupGate.pendingRuntimeWaiterCount());

            assertTrue(result.cancel(false));
            assertFalse(result.cancel(false));
            assertFalse(result.isDone(),
                    "pending finally/defer cleanup must retain the task lifetime");
            assertEquals(1, cleanupGate.pendingRuntimeWaiterCount(),
                    "cancellation must not detach an already-running cleanup await");
            assertTrue(cleanupGate.completeFromRuntime(7));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(1, cleanupEntries.get());
            assertNotNull(cleanupLane.get());
        }
    }

    @Test
    void runtimeOwnedCloseWaitsForCarrierExitBeforeRetiringCompletedTask() throws Exception {
        // Runtime-owned schedulers have no owned carrier executor to await.
        // A task can report Done while its guest turn is still leaving the
        // entered Truffle Context. close() must not observe a drained task
        // registry or publish the task Future until after that turn unwinds.
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        CountDownLatch guestBodyReturned = new CountDownLatch(1);
        CountDownLatch permitCarrierExit = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "close-carrier-exit-test", 1, carrier,
                turn -> {
                    turn.run();
                    guestBodyReturned.countDown();
                    try {
                        if (!permitCarrierExit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("guest carrier exit gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("guest carrier exit interrupted",
                                interrupted);
                    }
                });
        try {
            OresFuture<Integer> completed = scheduler.start(
                    resume -> OresScheduler.done(42));
            assertTrue(guestBodyReturned.await(5, TimeUnit.SECONDS));

            Thread closing = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeFinished.countDown();
                }
            });
            assertFalse(closeFinished.await(150, TimeUnit.MILLISECONDS),
                    "close must keep the completed-but-still-entered task live");
            assertFalse(completed.isDone(),
                    "Future publication must happen after guest carrier exit");

            permitCarrierExit.countDown();
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
            closing.join(5000);
            assertNull(closeFailure.get(), "normal guest unwind must drain cleanly");
            assertEquals(42, completed.get(5, TimeUnit.SECONDS));
        } finally {
            permitCarrierExit.countDown();
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeOwnedCloseWaitsForFailedTaskCarrierExitBeforeRetirement() throws Exception {
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        CountDownLatch guestTurnExited = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        IllegalStateException guestFailure =
                new IllegalStateException("guest task failure before context exit");
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "close-failed-carrier-exit-test", 1, carrier,
                turn -> {
                    turn.run();
                    guestTurnExited.countDown();
                    try {
                        if (!allowExit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("failed task carrier exit gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("failed task carrier exit interrupted",
                                interrupted);
                    }
                });
        try {
            OresFuture<Integer> failed = scheduler.start(resume -> {
                throw guestFailure;
            });
            assertTrue(guestTurnExited.await(5, TimeUnit.SECONDS));

            Thread closing = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeFinished.countDown();
                }
            });
            assertFalse(closeFinished.await(150, TimeUnit.MILLISECONDS),
                    "close must retain a failed task until its entered context exits");
            assertFalse(failed.isDone(), "failure must not publish before context exit");

            allowExit.countDown();
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
            closing.join(5000);
            assertNull(closeFailure.get());
            var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> failed.get(5, TimeUnit.SECONDS));
            assertSame(guestFailure, observed.getCause(),
                    "carrier-exit settlement must preserve original task failure identity");
        } finally {
            allowExit.countDown();
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void cancellingQueuedSourceBeforeItsFirstTurnNeverRunsGuestCode()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        CountDownLatch enteredCarrier = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        AtomicInteger sourceEntries = new AtomicInteger();
        try {
            OresFuture<Void> occupying = scheduler.startSync(() -> {
                enteredCarrier.countDown();
                assertTrue(releaseCarrier.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(enteredCarrier.await(5, TimeUnit.SECONDS));

            OresFuture<Integer> queued = scheduler.start(
                    new OresScheduler.CancellationAwareTask<>() {
                        @Override public boolean cleanupInProgress() { return false; }
                        @Override public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) { }
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume input) {
                            sourceEntries.incrementAndGet();
                            return OresScheduler.done(1);
                        }
                    });

            assertTrue(queued.cancel(false));
            assertTrue(queued.isCancelled(),
                    "unclaimed carrier can be revoked without running guest code");
            assertEquals(0, sourceEntries.get());
            releaseCarrier.countDown();
            assertNull(occupying.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> queued.get(5, TimeUnit.SECONDS));
            assertTrue(queued.isCancelled());
            assertEquals(0, sourceEntries.get(),
                    "abandoned queue entry must never dispatch guest source");
        } finally {
            releaseCarrier.countDown();
            scheduler.close();
        }
    }

    @Test
    void cancellationAfterCommittedDoneCannotOverrideTerminalOutcome()
            throws Exception {
        ExecutorService carrier = Executors.newSingleThreadExecutor();
        CountDownLatch doneReturned = new CountDownLatch(1);
        CountDownLatch leaveContext = new CountDownLatch(1);
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-after-terminal-commit", 1, carrier, turn -> {
                    turn.run();
                    doneReturned.countDown();
                    try {
                        if (!leaveContext.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("context exit gate timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
        try {
            OresFuture<Integer> done = scheduler.start(
                    input -> OresScheduler.done(42));
            assertTrue(doneReturned.await(5, TimeUnit.SECONDS));
            assertFalse(done.isDone(), "task Future waits for carrier exit");
            assertFalse(done.cancel(false),
                    "terminal outcome has already been committed");
            leaveContext.countDown();
            assertEquals(42, done.get(5, TimeUnit.SECONDS));
            assertFalse(done.isCancelled());
        } finally {
            leaveContext.countDown();
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentAwaitSettlementAndCancellationCannotDiscardCleanup()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            ExecutorService producers = Executors.newFixedThreadPool(2);
            try {
                for (int iteration = 0; iteration < 48; iteration++) {
                    OresFuture<Integer> source = new OresFuture<>();
                    CountDownLatch suspended = new CountDownLatch(1);
                    AtomicInteger cleanups = new AtomicInteger();
                    AtomicBoolean normalResultSelected = new AtomicBoolean();
                    OresFuture<Integer> task = scheduler.start(
                            new OresScheduler.CancellationAwareTask<>() {
                                private volatile boolean cleanup;
                                private volatile boolean requested;
                                private int stage;

                                @Override public boolean cleanupInProgress() {
                                    return cleanup;
                                }

                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) {
                                    requested = true;
                                }

                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume input) {
                                    if (stage++ == 0) {
                                        suspended.countDown();
                                        return OresScheduler.await(source);
                                    }
                                    if (requested || input.failure() != null) {
                                        assertSame(scheduler, OresScheduler.current());
                                        cleanup = true;
                                        cleanups.incrementAndGet();
                                        return OresScheduler.done(123);
                                    }
                                    normalResultSelected.set(true);
                                    return OresScheduler.done(55);
                                }
                            });
                    assertTrue(suspended.await(5, TimeUnit.SECONDS));
                    CountDownLatch start = new CountDownLatch(1);
                    var settling = producers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        source.completeFromRuntime(1);
                        return null;
                    });
                    var cancelling = producers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return task.cancel(false);
                    });
                    start.countDown();
                    settling.get(5, TimeUnit.SECONDS);
                    boolean admitted = cancelling.get(5, TimeUnit.SECONDS);
                    if (admitted) {
                        assertThrows(java.util.concurrent.CancellationException.class,
                                () -> task.get(5, TimeUnit.SECONDS));
                        assertTrue(cleanups.get() == 1 || normalResultSelected.get(),
                                "a cancellation may omit synthetic cleanup only when the "
                                + "guest had already selected its normal Done result");
                        assertTrue(cleanups.get() <= 1,
                                "a racing await may never replay cleanup");
                    } else {
                        assertEquals(55, task.get(5, TimeUnit.SECONDS));
                        assertEquals(0, cleanups.get());
                    }
                    assertEquals(0, source.pendingRuntimeWaiterCount());
                }
            } finally {
                producers.shutdownNow();
                assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentCloseCallersWaitForSameSuspendedCancellationCleanup() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> cleanupGate = new OresFuture<>();
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch secondCloseStarted = new CountDownLatch(1);
        CountDownLatch firstCloseDone = new CountDownLatch(1);
        CountDownLatch secondCloseDone = new CountDownLatch(1);
        AtomicReference<Throwable> firstCloseFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondCloseFailure = new AtomicReference<>();
        AtomicInteger cleanupEntries = new AtomicInteger();

        try {
            OresFuture<Integer> task = scheduler.start(
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cleaning;

                        @Override public boolean cleanupInProgress() {
                            return cleaning;
                        }

                        @Override public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) { }

                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume input) {
                            if (input.initial()) return OresScheduler.await(source);
                            if (!cleaning) {
                                assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                        input.failure());
                                cleaning = true;
                                cleanupEntries.incrementAndGet();
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertSame(scheduler, OresScheduler.current());
                            assertNull(input.failure());
                            assertEquals(7, input.value());
                            return OresScheduler.done(42);
                        }
                    });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (source.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, source.pendingRuntimeWaiterCount());

            Thread first = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    firstCloseFailure.set(failure);
                } finally {
                    firstCloseDone.countDown();
                }
            });
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cleanupGate.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, cleanupGate.pendingRuntimeWaiterCount());

            Thread second = Thread.ofPlatform().start(() -> {
                secondCloseStarted.countDown();
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    secondCloseFailure.set(failure);
                } finally {
                    secondCloseDone.countDown();
                }
            });
            assertTrue(secondCloseStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondCloseDone.await(150, TimeUnit.MILLISECONDS),
                    "second close must not return before the first has drained cleanup");
            assertFalse(firstCloseDone.await(25, TimeUnit.MILLISECONDS));
            assertFalse(task.isDone(), "cancellation is pending until cleanup completes");

            assertTrue(cleanupGate.completeFromRuntime(7));
            assertTrue(firstCloseDone.await(5, TimeUnit.SECONDS));
            assertTrue(secondCloseDone.await(5, TimeUnit.SECONDS));
            first.join(5000);
            second.join(5000);
            assertNull(firstCloseFailure.get());
            assertNull(secondCloseFailure.get());
            assertTrue(task.isCancelled());
            assertEquals(1, cleanupEntries.get());
            assertFalse(source.isDone(), "shared producer must not be cancelled");
        } finally {
            cleanupGate.completeFromRuntime(7);
            scheduler.close();
        }
    }

}
