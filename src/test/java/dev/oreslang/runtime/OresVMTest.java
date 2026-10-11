package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertEquals(0, vm.controlCarrierCount(),
                    "caller-thread admission must not start CONTROL workers");
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
    void controlCarriersStartOnFirstRunnableRootTurn() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertTrue(vm.started());
            assertEquals(0, vm.controlCarrierCount(),
                    "logical CONTROL scheduling must not prestart OS workers");
            assertEquals(OresVM.Pool.CONTROL,
                    vm.poolScheduler(OresVM.Pool.CONTROL).domain());
            assertEquals(vm.rootScheduler().parallelism(),
                    vm.poolScheduler(OresVM.Pool.CONTROL).maximumCarriers());

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
            assertTrue(vm.controlCarrierCount() >= 1);
            assertTrue(vm.controlCarrierCount() <=
                    vm.poolScheduler(OresVM.Pool.CONTROL).maximumCarriers());
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
            assertTrue(vm.controlCarrierCount() >= 1);
            assertTrue(vm.controlCarrierCount() <= vm.rootScheduler().parallelism());
        } finally {
            vm.close();
        }
    }
    @Test
    void vmOwnsActorRuntimeAndClosesAllThreeActorDomainsBeforeControl() {
        OresVM vm = OresVM.create(Runnable::run);
        OresScheduler root = vm.rootScheduler();
        ActorRuntime actors = vm.createActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 3, 64, 128),
                ActorRuntime.TurnExecutor.direct());
        try {
            assertEquals(0, vm.poolScheduler(OresVM.Pool.SHARED).createdCarriers());
            assertEquals(0, vm.poolScheduler(OresVM.Pool.TRUSTED_PRIVATE).createdCarriers());
            assertEquals(0, vm.poolScheduler(OresVM.Pool.UNTRUSTED).createdCarriers());
            assertEquals(3, vm.poolScheduler(OresVM.Pool.SHARED).maximumCarriers());
            assertEquals(2, vm.poolScheduler(OresVM.Pool.TRUSTED_PRIVATE).maximumCarriers());
            assertEquals(2, actors.carrierParallelism(ActorRuntime.ActorKind.PRIVATE));
            assertEquals(3, actors.carrierParallelism(ActorRuntime.ActorKind.SHARED));
            assertEquals(
                    Integer.getInteger("ores.runtime.actor.untrusted.parallelism", 5),
                    actors.carrierParallelism(ActorRuntime.ActorKind.UNTRUSTED));
            assertThrows(IllegalStateException.class, () ->
                    vm.createActorRuntime(
                            IsolatePolicy.developer(),
                            ActorRuntime.DispatcherConfig.defaults(),
                            ActorRuntime.TurnExecutor.direct()));
        } finally {
            vm.close();
        }
        assertTrue(actors.isClosed(), "VM close must retire the owned actor runtime");
        assertTrue(root.isClosed(), "VM close must retire CONTROL");
        assertThrows(IllegalStateException.class, () ->
                vm.createActorRuntime(
                        IsolatePolicy.developer(),
                        ActorRuntime.DispatcherConfig.defaults(),
                        ActorRuntime.TurnExecutor.direct()));
    }

    @Test
    void defaultPoolsHaveIndependentFiveCarrierBudgets() {
        var config = ActorRuntime.DispatcherConfig.defaults();
        assertEquals(
                Integer.getInteger("ores.runtime.actor.private.parallelism", 5),
                config.privateParallelism());
        assertEquals(
                Integer.getInteger("ores.runtime.actor.shared.parallelism", 5),
                config.sharedParallelism());
        try (OresVM vm = OresVM.create(Runnable::run)) {
            assertEquals(
                    Integer.getInteger("ores.runtime.control.parallelism", 5),
                    vm.rootScheduler().parallelism());
            assertEquals(0, vm.controlCarrierCount());
            for (OresVM.Pool pool : OresVM.Pool.values()) {
                assertTrue(vm.poolScheduler(pool).maximumCarriers() > 0);
                assertEquals(0, vm.poolScheduler(pool).createdCarriers());
            }
        }
    }
}
