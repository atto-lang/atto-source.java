package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresVMTest {
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
