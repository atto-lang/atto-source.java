package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresVMTest {

    @Test
    void shutdownNowReportsQueuedPhysicalStopNeverStartedExactlyOnce()
            throws Exception {
        var runningEntered = new java.util.concurrent.CountDownLatch(1);
        var runningFinished = new java.util.concurrent.CountDownLatch(1);
        var releaseRunning = new java.util.concurrent.CountDownLatch(1);
        var discarded = new java.util.concurrent.CountDownLatch(1);
        var queuedRan = new java.util.concurrent.atomic.AtomicInteger();
        var onDiscard = new java.util.concurrent.atomic.AtomicInteger();
        var runningStarted = new java.util.concurrent.atomic.AtomicInteger();
        var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        // One worker, one queue slot. Deterministic without changing process
        // properties or depending on availableProcessors().
        OresVM vm = OresVM.createWithPhysicalStopLimits(1, 1);
        try {
            vm.executePhysicalStop(() -> {
                runningStarted.incrementAndGet();
                runningEntered.countDown();
                try {
                    releaseRunning.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    // shutdownNow() interrupts an ACTUAL running worker;
                    // the worker's own finally, not the discard callback,
                    // accounts for physical exit.
                    Thread.currentThread().interrupt();
                } finally {
                    runningFinished.countDown();
                }
            }, cause -> unexpected.compareAndSet(null,
                    new AssertionError("RUNNING worker treated as discarded", cause)));
            assertTrue(runningEntered.await(3, TimeUnit.SECONDS));
            vm.executePhysicalStop(queuedRan::incrementAndGet, failure -> {
                onDiscard.incrementAndGet();
                if (!(failure instanceof java.util.concurrent.RejectedExecutionException)) {
                    unexpected.compareAndSet(null, failure);
                }
                discarded.countDown();
            });
            assertEquals(0, queuedRan.get(), "second job must be queued");
            assertEquals(0, onDiscard.get(), "queued job is not failed before shutdown");
            vm.close();
            assertTrue(discarded.await(3, TimeUnit.SECONDS));
            assertTrue(runningFinished.await(3, TimeUnit.SECONDS));
            assertEquals(1, runningStarted.get());
            assertEquals(0, queuedRan.get(), "cancelled-before-start job must never run");
            assertEquals(1, onDiscard.get(),
                    "shutdown must deliver exactly one failure receipt");
            org.junit.jupiter.api.Assertions.assertNull(unexpected.get(),
                    () -> String.valueOf(unexpected.get()));
        } finally {
            releaseRunning.countDown();
            vm.close();
        }
    }

    @Test
    void concurrentWorkerStartAndShutdownClaimOneExclusiveReceipt()
            throws Exception {
        // CAS winner must own the terminal witness. Shutdown may either
        // discard the queued task BEFORE worker entry or let the worker start;
        // it cannot report both, repeat either, or lose the task entirely.
        for (int round = 0; round < 32; round++) {
            var holdingEntered = new java.util.concurrent.CountDownLatch(1);
            var releaseHolding = new java.util.concurrent.CountDownLatch(1);
            var started = new java.util.concurrent.atomic.AtomicInteger();
            var discarded = new java.util.concurrent.atomic.AtomicInteger();
            var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            OresVM vm = OresVM.createWithPhysicalStopLimits(1, 1);
            try {
                vm.executePhysicalStop(() -> {
                    holdingEntered.countDown();
                    try {
                        releaseHolding.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, failure -> unexpected.compareAndSet(null,
                        new AssertionError("first RUNNING job was discarded", failure)));
                assertTrue(holdingEntered.await(3, TimeUnit.SECONDS));
                vm.executePhysicalStop(started::incrementAndGet,
                        failure -> discarded.incrementAndGet());
                Thread closer = Thread.ofVirtual().start(() -> {
                    try {
                        vm.close();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                releaseHolding.countDown();
                closer.join(TimeUnit.SECONDS.toMillis(3));
                assertTrue(!closer.isAlive(), "VM close hung in round " + round);
                assertEquals(1, started.get() + discarded.get(),
                        "physical STOP start and cancel-before-start are exclusive in round " + round);
                org.junit.jupiter.api.Assertions.assertNull(unexpected.get(),
                        () -> String.valueOf(unexpected.get()));
            } finally {
                releaseHolding.countDown();
                vm.close();
            }
        }
    }

    @Test
    void saturatedPhysicalStopQueueRejectsNewSubmissionWithoutFabricatingReceipt()
            throws Exception {
        var runningEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseRunning = new java.util.concurrent.CountDownLatch(1);
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        var thirdRan = new java.util.concurrent.atomic.AtomicInteger();
        var queuedDiscarded = new java.util.concurrent.atomic.AtomicInteger();
        OresVM vm = OresVM.createWithPhysicalStopLimits(1, 1);
        try {
            vm.executePhysicalStop(() -> {
                runningEntered.countDown();
                try {
                    releaseRunning.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, cause -> org.junit.jupiter.api.Assertions.fail(
                    "active worker must not receive neverStarted"));
            assertTrue(runningEntered.await(3, TimeUnit.SECONDS));
            vm.executePhysicalStop(() -> { }, failure -> queuedDiscarded.incrementAndGet());
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> vm.executePhysicalStop(thirdRan::incrementAndGet,
                            failure -> rejected.incrementAndGet()));
            assertEquals(0, rejected.get(),
                    "failed admission is a synchronous exception, not an accepted-discard callback");
            assertEquals(0, thirdRan.get());
            vm.close();
            assertEquals(1, queuedDiscarded.get(),
                    "shutdown must report the one accepted queued job");
            assertEquals(0, rejected.get());
            assertEquals(0, thirdRan.get());
        } finally {
            releaseRunning.countDown();
            vm.close();
        }
    }


    @Test
    void blockedPhysicalStopDoesNotStarveVmControlAndShutdownRevokesAdmission()
            throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        OresVM vm = OresVM.create(Runnable::run);
        try {
            vm.executePhysicalStop(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        failure.set(new AssertionError("test physical STOP never released"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.set(interrupted);
                } finally {
                    stopped.countDown();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(73, vm.rootScheduler()
                    .startSync(() -> 73).get(3, TimeUnit.SECONDS),
                    "blocked resource teardown must not occupy CONTROL");
        } finally {
            release.countDown();
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            vm.close();
        }
        org.junit.jupiter.api.Assertions.assertNull(failure.get(),
                () -> String.valueOf(failure.get()));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> vm.executePhysicalStop(() -> { }),
                "a closed VM must not admit another physical STOP");
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
    void closeFromRootCarrierFailsBeforeRevokingVmAndHostCanStillClose() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            OresFuture<Integer> first = vm.rootScheduler().start(resume -> {
                IllegalStateException denied = org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, vm::close);
                assertTrue(denied.getMessage().contains("outside actor, Mailman and scheduler"));
                assertTrue(vm.started(),
                        "failed self-close must never poison the live VM's STARTED state");
                return OresScheduler.done(41);
            });
            assertEquals(41, first.get(5, TimeUnit.SECONDS));
            assertTrue(vm.started());

            OresFuture<Integer> next =
                    vm.rootScheduler().start(resume -> OresScheduler.done(42));
            assertEquals(42, next.get(5, TimeUnit.SECONDS),
                    "a denied self-close must not revoke subsequent CONTROL turns");
        } finally {
            vm.close();
        }
        assertTrue(!vm.started(), "external teardown must complete normally");
        vm.close(); // idempotent
    }

    @Test
    void rootClosePreflightDoesNotWaitForVmMonitorHeldByAnotherThread() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread holdingMonitor = Thread.ofPlatform().start(() -> {
            synchronized (vm) {
                acquired.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test monitor-release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        });
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            var task = vm.rootScheduler().start(resume -> {
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, vm::close);
                return OresScheduler.done(9);
            });
            assertEquals(9, task.get(2, TimeUnit.SECONDS),
                    "self-close rejection must run BEFORE waiting on VM monitor");
        } finally {
            release.countDown();
            holdingMonitor.join(5000);
            assertTrue(!holdingMonitor.isAlive());
            vm.close();
        }
    }

    @Test
    void independentUserSchedulerCannotCloseVmWhileItsOwnTaskIsExecuting() throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             OresScheduler user = new OresScheduler(1)) {
            OresFuture<Integer> attempted = user.startSync(() -> {
                assertEquals(user, OresScheduler.current());
                IllegalStateException denied = org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, vm::close);
                assertTrue(denied.getMessage().contains("outside actor, Mailman and scheduler"));
                return 87;
            });
            assertEquals(87, attempted.get(5, TimeUnit.SECONDS));
            assertTrue(vm.started(), "foreign scheduler cannot poison VM lifecycle state");
            assertEquals(88, vm.rootScheduler()
                    .startSync(() -> 88).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorAndMailmanCallbacksCannotCloseTheOwningVm() throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {
            var actorDenied = new java.util.concurrent.CountDownLatch(1);
            var mailmanDenied = new java.util.concurrent.CountDownLatch(1);
            var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.<Integer>installMailman(8, (mail, context) -> {
                try {
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, vm::close);
                    assertTrue(vm.started());
                    assertEquals(group.id(), context.groupId());
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                } finally {
                    mailmanDenied.countDown();
                }
            });
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    group.joinCurrent(join);
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, vm::close);
                    assertTrue(vm.started());
                    group.emit(message);
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                } finally {
                    actorDenied.countDown();
                }
            });
            actor.send(17);
            assertTrue(actorDenied.await(5, TimeUnit.SECONDS));
            assertTrue(mailmanDenied.await(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertNull(callbackFailure.get(),
                    "the actor and CONTROL Mailman must both reject VM close");
            assertEquals(23, vm.rootScheduler().startSync(() -> 23)
                    .get(5, TimeUnit.SECONDS));
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
