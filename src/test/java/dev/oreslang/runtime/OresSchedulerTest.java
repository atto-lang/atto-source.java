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
    void explicitFixedSchedulerPrestartsExactlyRequestedCarriers() {
        OresScheduler scheduler = new OresScheduler(3);
        try {
            assertEquals(3, scheduler.parallelism());
            assertEquals(3, scheduler.ownedCarrierCount(),
                    "explicit OresScheduler(N) owns exactly N prestarted carriers");
        } finally {
            scheduler.close();
        }
        assertEquals(0, scheduler.ownedCarrierCount(),
                "closing the explicit scheduler retires its owned carriers");
    }

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
    void logicalExecutionContextSurvivesAwaitAndIsNotAHeapOrDispatch() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<ExecutionContext> firstContext = new AtomicReference<>();
            AtomicReference<ExecutionContext> resumedContext = new AtomicReference<>();
            AtomicReference<ExecutionContext> secondContext = new AtomicReference<>();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicLong resumedDispatch = new AtomicLong();
            AtomicInteger firstPc = new AtomicInteger();

            OresFuture<Integer> first = scheduler.start(resume -> {
                ExecutionContext current = OresScheduler.currentExecutionContext();
                assertNotNull(current);
                assertSame(current, OresScheduler.currentTaskDomain(),
                        "legacy task-domain token must be the stable execution context");
                assertEquals(ExecutionContext.AllocationKind.NONE,
                        current.allocationDomain().kind(),
                        "ordinary scheduler tasks must not be assigned a fake heap");
                if (firstPc.getAndIncrement() == 0) {
                    firstContext.set(current);
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    return OresScheduler.await(gate);
                }
                resumedContext.set(current);
                resumedDispatch.set(OresScheduler.currentDispatchId());
                return OresScheduler.done(1);
            });

            OresFuture<Integer> second = scheduler.start(resume -> {
                secondContext.set(OresScheduler.currentExecutionContext());
                return OresScheduler.done(2);
            });

            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            gate.completeFromRuntime(0);
            assertEquals(1, first.get(5, TimeUnit.SECONDS));

            assertSame(firstContext.get(), resumedContext.get(),
                    "await/resume must preserve one logical execution context");
            assertNotEquals(firstDispatch.get(), resumedDispatch.get(),
                    "physical scheduler dispatch identity must change across await");
            assertNotSame(firstContext.get(), secondContext.get(),
                    "two tasks on one scheduler must have distinct execution contexts");
            assertEquals(firstContext.get().id().runtimeDomain(),
                    secondContext.get().id().runtimeDomain(),
                    "tasks share a scheduler domain without sharing execution identity");
            assertNotEquals(firstContext.get().id().execution(),
                    secondContext.get().id().execution());
            assertFalse(firstContext.get().allocationDomain().isBound());
            assertEquals(0L, firstContext.get().parentExecution());
            assertEquals(0L, firstContext.get().continuation());
        }
    }

    @Test
    void logicalExecutionSurvivesTwoAwaitsAndFailureOnSeparateDispatches() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> gateOne = new OresFuture<>();
            OresFuture<Integer> gateTwo = new OresFuture<>();
            java.util.List<ExecutionContext> contexts =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.List<Long> dispatches =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicInteger phase = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                ExecutionContext current = OresScheduler.currentExecutionContext();
                assertNotNull(current);
                contexts.add(current);
                dispatches.add(OresScheduler.currentDispatchId());
                int step = phase.getAndIncrement();
                if (step == 0) return OresScheduler.await(gateOne);
                if (step == 1) return OresScheduler.await(gateTwo);
                if (step == 2) throw new IllegalStateException("two-await-trace-marker");
                throw new AssertionError("task must never resume after failure");
            });

            gateOne.completeFromRuntime(1);
            gateTwo.completeFromRuntime(2);
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals("two-await-trace-marker", failure.getCause().getMessage());
            assertEquals(3, contexts.size());
            assertEquals(3, dispatches.size());
            assertSame(contexts.get(0), contexts.get(1));
            assertSame(contexts.get(1), contexts.get(2));
            assertNotEquals(dispatches.get(0), dispatches.get(1));
            assertNotEquals(dispatches.get(1), dispatches.get(2));
            assertEquals(ExecutionContext.AllocationKind.NONE,
                    contexts.get(2).allocationDomain().kind());
        }
    }

    @Test
    void actorAllocationDomainPermitsZeroBasedOwnerWithoutBecomingExecutionIdentity() {
        ExecutionContext context = ExecutionContext.actor(
                11, 22, 11, 0, 3, 0, 0);

        assertEquals(11L, context.id().runtimeDomain());
        assertEquals(22L, context.id().execution());
        assertEquals(ExecutionContext.AllocationKind.ACTOR,
                context.allocationDomain().kind());
        assertEquals(0L, context.allocationDomain().owner());
        assertEquals(3L, context.allocationDomain().generation());
        assertNotEquals(context.id().execution(), context.allocationDomain().owner(),
                "execution and allocation-owner identities must never be conflated");
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
    void producerCompletionDuringSuspendingTurnNeverReentersGuestInline() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch inFirstTurn = new CountDownLatch(1);
            CountDownLatch releaseFirstTurn = new CountDownLatch(1);
            AtomicBoolean insideFirstTurn = new AtomicBoolean();
            AtomicInteger turns = new AtomicInteger();
            AtomicLong initialDispatch = new AtomicLong();

            OresFuture<Integer> task = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    initialDispatch.set(OresScheduler.currentDispatchId());
                    insideFirstTurn.set(true);
                    inFirstTurn.countDown();
                    try {
                        assertTrue(releaseFirstTurn.await(5, TimeUnit.SECONDS));
                        return OresScheduler.await(producer);
                    } finally {
                        insideFirstTurn.set(false);
                    }
                }
                assertFalse(insideFirstTurn.get(),
                        "producer completed while the prior guest turn was still executing");
                assertSame(scheduler, OresScheduler.current());
                assertNotEquals(initialDispatch.get(), OresScheduler.currentDispatchId(),
                        "await must dispatch a fresh logical scheduler turn");
                assertEquals(19, resume.value());
                return OresScheduler.done(20);
            });

            try {
                assertTrue(inFirstTurn.await(5, TimeUnit.SECONDS));
                Thread producerThread = Thread.ofPlatform().start(
                        () -> assertTrue(producer.completeFromRuntime(19)));
                producerThread.join(5000);
                assertFalse(producerThread.isAlive(), "producer completion stalled");
                assertFalse(task.isDone(),
                        "a completed producer must not reenter the active guest turn");
            } finally {
                releaseFirstTurn.countDown();
            }

            assertEquals(20, task.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void cancellationRevocationCompletesBeforeSuspendedAwaitResumes() throws Exception {
        CountDownLatch revocationStarted = new CountDownLatch(1);
        CountDownLatch permitRevocation = new CountDownLatch(1);
        OresFuture<Integer> producer = new OresFuture<>(
                () -> true,
                () -> {
                    revocationStarted.countDown();
                    try {
                        if (!permitRevocation.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("cancel hook could not finish");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
        try (OresScheduler scheduler = new OresScheduler(2)) {
            AtomicInteger turns = new AtomicInteger();
            OresFuture<String> task = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) return OresScheduler.await(producer);
                assertInstanceOf(java.util.concurrent.CancellationException.class,
                        resume.failure());
                return OresScheduler.done("revoked");
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (producer.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, producer.pendingRuntimeWaiterCount());
            Thread canceller = Thread.ofPlatform().start(() -> assertTrue(producer.cancel(false)));
            try {
                assertTrue(revocationStarted.await(5, TimeUnit.SECONDS));
                assertFalse(task.isDone(),
                        "await must not resume before producer revocation is complete");
                assertEquals(1, turns.get(),
                        "no guest continuation may run during the cancellation hook");
            } finally {
                permitRevocation.countDown();
            }
            canceller.join(5000);
            assertFalse(canceller.isAlive(), "cancellation hook could not unwind");
            assertEquals("revoked", task.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        } finally {
            permitRevocation.countDown();
        }
    }

    @Test
    void awaitRegisteredDuringProducerCancellationHookResumesAfterRevocation() throws Exception {
        CountDownLatch revocationStarted = new CountDownLatch(1);
        CountDownLatch revocationMayFinish = new CountDownLatch(1);
        OresFuture<Integer> producer = new OresFuture<>(
                () -> true,
                () -> {
                    revocationStarted.countDown();
                    try {
                        if (!revocationMayFinish.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("producer revocation timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread canceller = Thread.ofPlatform().start(
                () -> cancelled.set(producer.cancel(false)));
        try {
            assertTrue(revocationStarted.await(5, TimeUnit.SECONDS));
            assertTrue(producer.isCancelled(),
                    "cancel terminal CAS precedes revocation completion");
            try (OresScheduler scheduler = new OresScheduler(2)) {
                AtomicInteger turns = new AtomicInteger();
                OresFuture<String> task = scheduler.start(resume -> {
                    if (turns.getAndIncrement() == 0) return OresScheduler.await(producer);
                    assertInstanceOf(java.util.concurrent.CancellationException.class,
                            resume.failure());
                    return OresScheduler.done("revocation-finished");
                });

                long registeredDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (producer.pendingRuntimeWaiterCount() == 0
                        && System.nanoTime() < registeredDeadline) Thread.sleep(1);
                assertEquals(1, producer.pendingRuntimeWaiterCount(),
                        "await after cancellation CAS must register behind revocation fence");
                assertFalse(task.isDone(),
                        "cancelled producer cannot resume guest before hook finishes");
                assertEquals(1, turns.get());

                revocationMayFinish.countDown();
                canceller.join(5000);
                assertFalse(canceller.isAlive(), "revocation thread failed to exit");
                assertTrue(cancelled.get());
                assertEquals("revocation-finished", task.get(5, TimeUnit.SECONDS));
                assertEquals(2, turns.get());
                assertEquals(0, producer.pendingRuntimeWaiterCount());
            }
        } finally {
            revocationMayFinish.countDown();
            canceller.join(5000);
        }
    }

    @Test
    void completionRacingAwaitRegistrationNeverLosesOrDuplicatesResume() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int round = 0; round < 192; round++) {
                OresFuture<Integer> producer = new OresFuture<>();
                CountDownLatch firstTurnEntered = new CountDownLatch(1);
                CountDownLatch allowAwait = new CountDownLatch(1);
                AtomicInteger turns = new AtomicInteger();
                final int expected = round;
                OresFuture<Integer> task = scheduler.start(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        firstTurnEntered.countDown();
                        assertTrue(allowAwait.await(5, TimeUnit.SECONDS));
                        return OresScheduler.await(producer);
                    }
                    assertSame(scheduler, OresScheduler.current());
                    assertNull(resume.failure());
                    assertEquals(expected, resume.value());
                    return OresScheduler.done(expected);
                });
                try {
                    assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS));
                    if (round % 3 == 0) {
                        // Settlement strictly before scheduler waiter registration.
                        assertTrue(producer.completeFromRuntime(expected));
                        allowAwait.countDown();
                    } else if (round % 3 == 1) {
                        // Producer and suspending turn compete for publication.
                        AtomicBoolean settled = new AtomicBoolean();
                        Thread completer = Thread.ofPlatform().start(() -> {
                            allowAwait.countDown();
                            settled.set(producer.completeFromRuntime(expected));
                        });
                        completer.join(5000);
                        assertFalse(completer.isAlive(), "producer stalled at round " + round);
                        assertTrue(settled.get(), "producer did not settle at round " + round);
                    } else {
                        // Force the pending registration to exist before
                        // settlement, covering the other side of the race.
                        allowAwait.countDown();
                        long registeredDeadline = System.nanoTime()
                                + TimeUnit.SECONDS.toNanos(5);
                        while (producer.pendingRuntimeWaiterCount() == 0
                                && System.nanoTime() < registeredDeadline)
                            Thread.sleep(1);
                        assertEquals(1, producer.pendingRuntimeWaiterCount(),
                                "await never registered at round " + round);
                        assertTrue(producer.completeFromRuntime(expected));
                    }
                } finally {
                    allowAwait.countDown();
                }
                assertEquals(expected, task.get(5, TimeUnit.SECONDS));
                assertEquals(2, turns.get(), "duplicate or missing resume at round " + round);
                assertEquals(0, producer.pendingRuntimeWaiterCount(),
                        "producer retained a waiter at round " + round);
            }
        }
    }

    @Test
    void cancellationRacingAwaitRegistrationNeverLosesOrDuplicatesResume() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int round = 0; round < 96; round++) {
                CountDownLatch revocationEntered = new CountDownLatch(1);
                CountDownLatch finishRevocation = new CountDownLatch(1);
                OresFuture<Integer> producer = new OresFuture<>(
                        () -> true,
                        () -> {
                            revocationEntered.countDown();
                            try {
                                if (!finishRevocation.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("revocation did not finish");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
                CountDownLatch firstTurnEntered = new CountDownLatch(1);
                CountDownLatch allowAwait = new CountDownLatch(1);
                AtomicInteger turns = new AtomicInteger();
                OresFuture<Integer> task = scheduler.start(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        firstTurnEntered.countDown();
                        assertTrue(allowAwait.await(5, TimeUnit.SECONDS));
                        return OresScheduler.await(producer);
                    }
                    assertInstanceOf(java.util.concurrent.CancellationException.class,
                            resume.failure());
                    return OresScheduler.done(1);
                });

                AtomicBoolean cancelled = new AtomicBoolean();
                Thread canceller = null;
                try {
                    assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS));
                    if (round % 3 == 0) {
                        // Cancellation CAS and revocation begin before the
                        // awaiting turn can install its waiter.
                        canceller = Thread.ofPlatform().start(
                                () -> cancelled.set(producer.cancel(false)));
                        assertTrue(revocationEntered.await(5, TimeUnit.SECONDS));
                        allowAwait.countDown();
                    } else if (round % 3 == 1) {
                        // Race the registration against cancellation.
                        canceller = Thread.ofPlatform().start(() -> {
                            allowAwait.countDown();
                            cancelled.set(producer.cancel(false));
                        });
                        assertTrue(revocationEntered.await(5, TimeUnit.SECONDS));
                    } else {
                        // Await waiter is already installed when cancellation
                        // revocation starts.
                        allowAwait.countDown();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (producer.pendingRuntimeWaiterCount() == 0
                                && System.nanoTime() < deadline) Thread.sleep(1);
                        assertEquals(1, producer.pendingRuntimeWaiterCount());
                        canceller = Thread.ofPlatform().start(
                                () -> cancelled.set(producer.cancel(false)));
                        assertTrue(revocationEntered.await(5, TimeUnit.SECONDS));
                    }
                    assertFalse(task.isDone(),
                            "await resumed before revocation drained at round " + round);
                    assertEquals(1, turns.get(),
                            "guest continuation entered cancel hook at round " + round);
                } finally {
                    allowAwait.countDown();
                    finishRevocation.countDown();
                    if (canceller != null) {
                        canceller.join(5000);
                        assertFalse(canceller.isAlive(), "canceller stuck at round " + round);
                    }
                }
                assertTrue(cancelled.get(), "cancel CAS lost at round " + round);
                assertEquals(1, task.get(5, TimeUnit.SECONDS));
                assertEquals(2, turns.get(), "duplicate resume at round " + round);
                assertEquals(0, producer.pendingRuntimeWaiterCount(),
                        "waiter retained after cancellation at round " + round);
            }
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
}
