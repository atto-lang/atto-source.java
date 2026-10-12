package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresVMTest {
    @Test
    void executorThrowingDuringPhysicalRootTurnQuarantinesHostAndRetirement()
            throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var letExit = new java.util.concurrent.CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var bodyRuns = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor broken = admitted -> {
            Thread carrier = Thread.ofPlatform().daemon(true).start(admitted);
            worker.set(carrier);
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "physical guest turn failed to start in test");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.RejectedExecutionException(
                        "executor interrupted while observing physical guest entry",
                        interrupted);
            }
            // The executor reports failure WHILE its accepted guest carrier
            // remains entered. It is not safe to replay or retire that turn.
            throw new java.util.concurrent.RejectedExecutionException(
                    "accepted CONTROL runnable is still physically executing");
        };
        OresVM vm = OresVM.create(Runnable::run, broken);
        OresVM.HostHandoffLease lease = vm.beginHostHandoff();
        try {
            vm.rootScheduler().startSync(() -> {
                bodyRuns.incrementAndGet();
                entered.countDown();
                if (!letExit.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release guest body");
                }
                return 83;
            });
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    lease::close,
                    "executor failure while entered cannot prove physical DONE");
            assertEquals(1, bodyRuns.get());
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    vm::beginHostHandoff,
                    "an uncertain root entry must permanently quarantine host admission");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, vm::requireHostHandoffReleased,
                    "an uncertain carrier may never be treated as quiescent");
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    lease::close,
                    "idempotent close may not replay an uncertain physical carrier");
            assertEquals(1, bodyRuns.get());
        } finally {
            letExit.countDown();
            Thread carrier = worker.get();
            if (carrier != null) {
                carrier.join(5000);
                assertTrue(!carrier.isAlive());
            }
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, vm::close,
                    "uncertain physical exit must block VM retirement");
        }
    }

    @Test
    void inlineExecutorThatRunsGuestThenThrowsCannotReplayCompletedTurn()
            throws Exception {
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor executor = runnable -> {
            runnable.run();
            if (submissions.incrementAndGet() == 1) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "executor reported rejection after running the guest");
            }
        };
        try (OresVM vm = OresVM.create(Runnable::run, executor)) {
            var host = vm.beginHostHandoff();
            var firstEffects = new java.util.concurrent.atomic.AtomicInteger();
            var secondEffects = new java.util.concurrent.atomic.AtomicInteger();
            try {
                OresFuture<Integer> first = vm.rootScheduler().startSync(() -> {
                    firstEffects.incrementAndGet();
                    return 11;
                });
                OresFuture<Integer> second = vm.rootScheduler().startSync(() -> {
                    secondEffects.incrementAndGet();
                    return 22;
                });
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        host::close);
                assertEquals(11, first.get(5, TimeUnit.SECONDS));
                assertEquals(1, firstEffects.get(),
                        "inline-executed guest must not be queued for replay");
                assertEquals(0, secondEffects.get());
                assertEquals(1, vm.queuedHostHandoffTurns(),
                        "only never-started second task remains in FIFO");
                host.close(); // idempotent owner retry, no second lease release
                assertEquals(22, second.get(5, TimeUnit.SECONDS));
                assertEquals(1, firstEffects.get());
                assertEquals(1, secondEffects.get());
                assertEquals(0, vm.queuedHostHandoffTurns());
                vm.requireHostHandoffReleased();
            } finally {
                host.close();
            }
        }
    }

    @Test
    void rejectedRootDrainCanRetryWithoutReleasingHostLeaseTwice()
            throws Exception {
        var accepted = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var rejectOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.Executor downstream = runnable -> {
            if (rejectOnce.getAndSet(false)) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "deterministic first submission rejection");
            }
            accepted.add(runnable);
        };
        try (OresVM vm = OresVM.create(Runnable::run, downstream)) {
            OresVM.HostHandoffLease lease = vm.beginHostHandoff();
            var guestEffects = new java.util.concurrent.atomic.AtomicInteger();
            try {
                OresFuture<Integer> task = vm.rootScheduler().startSync(() -> {
                    guestEffects.incrementAndGet();
                    return 23;
                });
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        lease::close);
                assertEquals(1, vm.queuedHostHandoffTurns(),
                        "failed executor acceptance must retain queued owner");
                assertEquals(0, guestEffects.get());
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, vm::requireHostHandoffReleased);
                vm.sealHostHandoffAdmission();
                lease.close(); // retry only: never decrement hostScopes twice
                assertEquals(0, vm.queuedHostHandoffTurns());
                Runnable submitted = accepted.poll(5, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertNotNull(submitted);
                submitted.run();
                assertEquals(23, task.get(5, TimeUnit.SECONDS));
                assertEquals(1, guestEffects.get(),
                        "retry must not duplicate guest execution");
                vm.requireHostHandoffReleased();
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void executorAcceptedBeforeHostScopeCannotEnterGuestAfterHostClaims()
            throws Exception {
        var admitted = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, admitted::add)) {
            var effects = new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> task = vm.rootScheduler().start(resume -> {
                effects.incrementAndGet();
                return OresScheduler.done(29);
            });
            Runnable acceptedBeforeHost = admitted.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(acceptedBeforeHost);
            var host = vm.beginHostHandoff();
            try {
                acceptedBeforeHost.run();
                acceptedBeforeHost.run();
                assertEquals(0, effects.get(),
                        "previously accepted CONTROL work cannot start inside host scope");
                assertEquals(1, vm.queuedHostHandoffTurns(),
                        "late executor invocation must return to bounded FIFO");
                assertTrue(!task.isDone());
                assertEquals(0, admitted.size());
            } finally {
                host.close();
            }
            Runnable released = admitted.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(released);
            released.run();
            released.run();
            assertEquals(29, task.get(5, TimeUnit.SECONDS));
            assertEquals(1, effects.get(), "guest body must execute exactly once");
            vm.requireHostHandoffReleased();
        }
    }

    @Test
    void contextRetirementSealsNewHostScopesWithoutDroppingAuthorizedTurns()
            throws Exception {
        var admitted = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, admitted::add)) {
            var lease = vm.beginHostHandoff();
            var effects = new java.util.concurrent.atomic.AtomicInteger();
            try {
                OresFuture<Integer> source = vm.rootScheduler().start(resume -> {
                    effects.incrementAndGet();
                    return OresScheduler.done(91);
                });
                vm.sealHostHandoffAdmission();
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        vm::beginHostHandoff,
                        "closing context must not admit a new host scope");
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, vm::requireHostHandoffReleased);
                assertEquals(0, effects.get());
                lease.close();
                var admittedTurn = admitted.poll(5, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertNotNull(admittedTurn);
                admittedTurn.run();
                assertEquals(91, source.get(5, TimeUnit.SECONDS));
                assertEquals(1, effects.get());
                vm.requireHostHandoffReleased();
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        vm::beginHostHandoff,
                        "cleanup drain never reopens sealed admission");
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void scopedHostEntryHoldsRootCarrierUntilAllNestedHandoffsExit() throws Exception {
        var admitted = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, admitted::add)) {
            var outer = vm.beginHostHandoff();
            var inner = vm.beginHostHandoff();
            var ran = new java.util.concurrent.atomic.AtomicInteger();
            try {
                OresFuture<Integer> task = vm.rootScheduler().start(resume -> {
                    ran.incrementAndGet();
                    return OresScheduler.done(73);
                });
                assertEquals(1, vm.queuedHostHandoffTurns(),
                        "root task must remain at executor admission");
                assertEquals(0, admitted.size(),
                        "no physical CONTROL turn may run under host Value.execute");
                assertEquals(0, ran.get());
                assertTrue(!task.isDone());
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        vm::requireHostHandoffReleased,
                        "outstanding host entry must block physical context retirement");
                inner.close();
                assertEquals(1, vm.queuedHostHandoffTurns(),
                        "nested inner exit must not authorize guest entry");
                assertEquals(0, admitted.size());
                outer.close();
                assertEquals(0, vm.queuedHostHandoffTurns());
                assertEquals(1, admitted.size());
                Runnable turn = admitted.poll(5, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertNotNull(turn);
                turn.run();
                assertEquals(73, task.get(5, TimeUnit.SECONDS));
                assertEquals(1, ran.get());
                vm.requireHostHandoffReleased();
            } finally {
                outer.close();
                inner.close();
            }
        }
    }

    @Test
    void cancelledDeferredRootTurnNeverExecutesGuestBodyAfterHostExit()
            throws Exception {
        var admitted = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, admitted::add)) {
            var handoff = vm.beginHostHandoff();
            var guestStarts = new java.util.concurrent.atomic.AtomicInteger();
            try {
                OresFuture<Integer> task = vm.rootScheduler().start(resume -> {
                    guestStarts.incrementAndGet();
                    return OresScheduler.done(5);
                });
                assertEquals(1, vm.queuedHostHandoffTurns());
                assertTrue(task.cancel(false));
                assertEquals(0, guestStarts.get());
                handoff.close();
                Runnable turn = admitted.poll(5, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertNotNull(turn);
                turn.run();
                assertEquals(0, guestStarts.get(),
                        "cancelled queued carrier cannot enter guest execution");
                assertTrue(task.isCancelled());
            } finally {
                handoff.close();
            }
        }
    }

    @Test
    void rootGuestAdmissionCanStayOnCallerAcrossPendingAwait() throws Exception {
        var turns = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, turns::add)) {
            Thread caller = Thread.currentThread();
            OresFuture<Integer> source = new OresFuture<>();
            var pc = new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> task = vm.rootScheduler().start(resume -> {
                assertEquals(caller, Thread.currentThread());
                assertEquals(vm.rootScheduler(), OresScheduler.current());
                if (pc.getAndIncrement() == 0) return OresScheduler.await(source);
                return OresScheduler.done((Integer) resume.value() + 1);
            });
            assertTrue(vm.controlCarrierCount() > 0);
            assertEquals(1, vm.rootScheduler().parallelism());
            turns.poll(5, TimeUnit.SECONDS).run();
            assertTrue(!task.isDone());
            Thread producer = Thread.ofPlatform().start(() -> source.completeFromRuntime(41));
            producer.join();
            turns.poll(5, TimeUnit.SECONDS).run();
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void vmCloseTimeoutRetainsPendingSourceCleanupAndAllowsRetirementRetry()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        OresFuture<Integer> ordinaryWork = new OresFuture<>();
        OresFuture<Integer> cleanup = new OresFuture<>();
        var cleanupEntered = new java.util.concurrent.CountDownLatch(1);
        var cleanupRuns = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> task = vm.rootScheduler().start(
                new OresScheduler.CancellationAwareTask<Integer>() {
                    private volatile boolean inCleanup;
                    @Override public boolean cleanupInProgress() { return inCleanup; }
                    @Override public void cancellationRequested(
                            OresScheduler.TaskCancelledException ignored) { }
                    @Override public OresScheduler.Step<Integer> resume(
                            OresScheduler.Resume resumed) {
                        if (resumed.initial()) return OresScheduler.await(ordinaryWork);
                        if (!inCleanup) {
                            org.junit.jupiter.api.Assertions.assertInstanceOf(
                                    OresScheduler.TaskCancelledException.class,
                                    resumed.failure());
                            inCleanup = true;
                            cleanupRuns.incrementAndGet();
                            cleanupEntered.countDown();
                            return OresScheduler.await(cleanup);
                        }
                        assertEquals(99, resumed.value());
                        org.junit.jupiter.api.Assertions.assertNull(resumed.failure());
                        inCleanup = false;
                        return OresScheduler.done(1);
                    }
                });
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (ordinaryWork.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < readyDeadline) Thread.sleep(1);
            assertEquals(1, ordinaryWork.pendingRuntimeWaiterCount());

            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, vm::close);
            assertTrue(failure.getMessage().contains("unfinished cancellation cleanup"),
                    failure.getMessage());
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, cleanupRuns.get());
            assertTrue(!task.isDone(), "a failed VM close cannot publish cleanup completion");
            assertEquals(0, ordinaryWork.pendingRuntimeWaiterCount());
            assertEquals(1, cleanup.pendingRuntimeWaiterCount());
            assertTrue(vm.controlCarrierCount() > 0,
                    "failed VM close must retain the only carriers for suspended cleanup");

            assertTrue(cleanup.completeFromRuntime(99));
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
            assertEquals(1, cleanupRuns.get());
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(vm::close,
                    "retry must retire CONTROL carriers after owned cleanup drains");
            assertEquals(0, vm.controlCarrierCount());
        } finally {
            cleanup.completeFromRuntime(99);
            vm.close();
        }
    }

    @Test
    void controlCarriersAreLiveBeforeFirstRootTaskTurn() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertTrue(vm.started());
            assertEquals(
                    vm.rootScheduler().parallelism(),
                    vm.controlCarrierCount(),
                    "all CONTROL carriers must be resident before root execution");

            OresFuture<String> first =
                    vm.rootScheduler().start(resume -> {
                        assertTrue(resume.initial());
                        assertTrue(OresScheduler.isSchedulerCarrierThread());
                        assertEquals(
                                vm.rootScheduler(),
                                OresScheduler.current());
                        return OresScheduler.done(Thread.currentThread().getName());
                    });

            String threadName = first.get(5, TimeUnit.SECONDS);
            assertTrue(threadName.startsWith("ores-control-plane-"));
        } finally {
            vm.close();
        }
    }

    @Test
    void startupIsIdempotentAndSchedulerDispatchIdsRemainFresh() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            OresFuture<Long> task = vm.rootScheduler().start(new OresScheduler.Task<>() {
                private int pc;
                @Override
                public OresScheduler.Step<Long> resume(OresScheduler.Resume resume) {
                    if (pc++ == 0) {
                        long first = OresScheduler.currentDispatchId();
                        return OresScheduler.await(OresFuture.completed(first));
                    }
                    long resumed = OresScheduler.currentDispatchId();
                    assertNotEquals(0L, resumed);
                    assertEquals(OresScheduler.currentTaskDomain(), OresScheduler.currentTaskDomain());
                    return OresScheduler.done(resumed);
                }
            });

            long dispatch = task.get(5, TimeUnit.SECONDS);
            assertTrue(dispatch > 0L);
            vm.startup();
            vm.startup();
            assertEquals(vm.rootScheduler().parallelism(), vm.controlCarrierCount());
        } finally {
            vm.close();
        }
    }
}
