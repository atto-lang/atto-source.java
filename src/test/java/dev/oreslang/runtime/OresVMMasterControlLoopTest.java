package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class OresVMMasterControlLoopTest {
    @Test
    void eagerlyStartsOneMasterWithoutActivatingActorWorkers() throws Exception {
        var vm = OresVM.create(Runnable::run);
        try (var actors = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            assertEquals(1, vm.masterSchedulerThreadCount());
            assertEquals(0, actors.activatedActorPoolSchedulers());
            assertEquals(0, actors.activatedActorCarrierPools());
            vm.attachActorRuntime(actors);

            var ref = actors.<String>spawnShared(() -> (message, context) -> {});
            assertEquals(1, actors.activatedActorPoolSchedulers());
            assertEquals(0, actors.activatedActorCarrierPools(),
                    "master wakeups and actor registration must not start workers");
            actors.stop(ref);
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            vm.close();
        }
    }

    @Test
    void blockedMasterStopsWithoutAnActorOrPoolScheduler() throws Exception {
        var master = new OresVMMasterControlLoop();
        master.start();
        assertEquals(1, master.startedThreads());
        master.signal(ActorRuntime.ActorKind.PRIVATE);
        master.shutdown();
        assertTrue(master.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(1, master.startedThreads(),
                "a master scheduler is created only once in its lifetime");
        assertThrows(IllegalStateException.class, master::start);
    }

    @Test
    void concurrentPoolWakeupsCoalesceWithoutCreatingAdditionalSchedulers() throws Exception {
        var master = new OresVMMasterControlLoop();
        var actors = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
        master.start();
        try {
            master.attach(actors);
            actors.setMasterPoolWakeup(master::signal);
            var first = actors.<String>spawnShared(() -> (message, context) -> {});
            var second = actors.<String>spawnShared(() -> (message, context) -> {});
            for (int i = 0; i < 1000; i++) {
                master.signal(ActorRuntime.ActorKind.SHARED);
                master.signal(ActorRuntime.ActorKind.PRIVATE);
            }
            assertEquals(1, master.startedThreads());
            assertEquals(1, actors.activatedActorPoolSchedulers());
            assertEquals(0, actors.activatedActorCarrierPools());
            actors.stop(first);
            actors.stop(second);
            assertTrue(first.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            actors.close();
            master.shutdown();
            assertTrue(master.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
