package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorPoolControlSchedulerTest {
    private static ActorRuntime.ActorId id() {
        return new ActorRuntime.ActorId(UUID.randomUUID());
    }

    @Test
    void eagerDescriptorHasNoThreadUntilFirstActorAndCloseIsColdSafe() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-cold");
        assertFalse(scheduler.started());
        assertEquals(0, scheduler.actorCount());
        scheduler.signalActorWork();
        scheduler.signalMasterControl();
        assertFalse(scheduler.started());
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> scheduler.registerActor(id()));
    }

    @Test
    void firstRegistrationStartsOneControlThreadAndWakeupsDoNotRunGuestCode() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-wakeup");
        try {
            var first = id();
            scheduler.registerActor(first);
            assertTrue(scheduler.started());
            assertEquals(1, scheduler.actorCount());
            var second = id();
            scheduler.registerActor(second);
            assertEquals(2, scheduler.actorCount());
            scheduler.signalActorWork();
            scheduler.signalMasterControl();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scheduler.handledWakeups() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(scheduler.handledWakeups() > 0);
            scheduler.unregisterActor(first);
            scheduler.unregisterActor(second);
            assertEquals(0, scheduler.actorCount());
            assertTrue(scheduler.started(), "activated scheduler stays alive until pool shutdown");
        } finally {
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSignalsAndShutdownCannotRestartScheduler() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-close-race");
        scheduler.registerActor(id());
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch begin = new CountDownLatch(1);
        try (var producers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                producers.submit(() -> {
                    try { begin.await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int j = 0; j < 300; j++) {
                        scheduler.signalActorWork();
                        scheduler.signalMasterControl();
                        accepted.incrementAndGet();
                    }
                });
            }
            begin.countDown();
            scheduler.shutdown();
        }
        assertEquals(2400, accepted.get());
        assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(0, scheduler.actorCount());
        assertThrows(IllegalStateException.class, () -> scheduler.registerActor(id()));
    }

    @Test
    void actorRuntimeStartsOnlyTheSelectedPoolControlThread() throws Exception {
        String previous = System.getProperty("ores.runtime.carriers");
        String affinity = System.getProperty("ores.actor.affinity");
        System.setProperty("ores.runtime.carriers", "java");
        System.setProperty("ores.actor.affinity", "lane");
        try {
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
                assertEquals(0, runtime.activatedActorPoolSchedulers());
                assertEquals(0, runtime.activatedActorCarrierPools());
                var ref = runtime.<String>spawnShared(() -> (message, context) -> {});
                assertEquals(1, runtime.activatedActorPoolSchedulers());
                assertEquals(0, runtime.activatedActorCarrierPools());
                runtime.stop(ref);
                assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS));
            }
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previous);
            if (affinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", affinity);
        }
    }
}
