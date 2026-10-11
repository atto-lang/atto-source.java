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

    @Test
    void cpuAffinityPoolBootstrapDoesNotRequireNativeLibraryBeforeFirstTurn() throws Exception {
        String previousAffinity = System.getProperty("ores.actor.affinity");
        String previousCpus = System.getProperty("ores.actor.cpus");
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousPath = System.getProperty("ores.thread.native.path");
        System.setProperty("ores.actor.affinity", "cpu");
        System.setProperty("ores.actor.cpus", "0");
        System.setProperty("ores.runtime.carriers", "java");
        System.setProperty("ores.thread.native.path", "/does-not-exist/ores-pool-affinity-test.so");
        try {
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(1, 1, 8, 16))) {
                assertEquals(0, runtime.activatedActorCarrierPools());
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {});
                assertEquals(1, runtime.activatedActorPoolSchedulers());
                assertEquals(0, runtime.activatedActorCarrierPools(),
                        "CPU affinity must not load JNI or start a carrier until runnable work");
                runtime.stop(actor);
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            }
        } finally {
            restore("ores.actor.affinity", previousAffinity);
            restore("ores.actor.cpus", previousCpus);
            restore("ores.runtime.carriers", previousBackend);
            restore("ores.thread.native.path", previousPath);
        }
    }

    @Test
    void defaultPoolWorkerLimitsDoNotExceedFive() {
        var config = ActorRuntime.DispatcherConfig.defaults();
        assertTrue(config.privateParallelism() >= 1 && config.privateParallelism() <= 5);
        assertTrue(config.sharedParallelism() >= 1 && config.sharedParallelism() <= 5);
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    @Test
    void admissionIsIssuedBySchedulerButGuestWorkRunsOnWorker() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-worker-handoff", 8);
        scheduler.registerActor(id());
        try (var workers = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ores-test-worker");
            t.setDaemon(true);
            return t;
        })) {
            CountDownLatch executed = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> executionThread =
                    new java.util.concurrent.atomic.AtomicReference<>();
            scheduler.dispatch(workers, -1, () -> {
                executionThread.set(Thread.currentThread().getName());
                executed.countDown();
            });
            assertTrue(executed.await(3, TimeUnit.SECONDS));
            assertEquals("ores-test-worker", executionThread.get());
            assertTrue(scheduler.started());
        } finally {
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownRejectsFurtherDispatchWithoutRunningGuestOnScheduler() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-reject", 1);
        scheduler.registerActor(id());
        try (var workers = Executors.newSingleThreadExecutor()) {
            scheduler.shutdown();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scheduler.dispatch(workers, -1,
                            () -> fail("rejected task must not execute")));
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownRejectsQueuedAdmissionButCompletesAnAlreadySelectedRequest() throws Exception {
        var scheduler = new ActorPoolControlScheduler("test-admission-race", 4);
        scheduler.registerActor(id());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ran = new CountDownLatch(1);
        var firstFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var queuedFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        java.util.concurrent.ThreadPoolExecutor workers =
                new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                        TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4),
                        runnable -> {
                            Thread t = new Thread(runnable, "test-admission-worker");
                            t.setDaemon(true);
                            return t;
                        }) {
                    @Override
                    public void execute(Runnable task) {
                        entered.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) {
                                throw new java.util.concurrent.RejectedExecutionException(
                                        "test admission gate timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.RejectedExecutionException(interrupted);
                        }
                        super.execute(task);
                    }
                };
        var first = Thread.ofPlatform().start(() -> {
            try {
                scheduler.dispatch(workers, -1, ran::countDown);
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.dispatch(workers, -1,
                            () -> fail("shutdown must fence queued dispatch"));
                } catch (Throwable failure) {
                    queuedFailure.set(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (scheduler.pendingDispatches() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, scheduler.pendingDispatches());
            scheduler.shutdown();
            release.countDown();
            first.join(3000);
            second.join(3000);
            assertFalse(first.isAlive(), "selected admission must be acknowledged");
            assertFalse(second.isAlive(), "shutdown must unblock queued admission");
            assertNull(firstFailure.get(), "pre-shutdown selected request can finish admission");
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    queuedFailure.get());
            assertTrue(ran.await(3, TimeUnit.SECONDS));
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            scheduler.shutdown();
            workers.shutdownNow();
            first.join(3000);
            assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
