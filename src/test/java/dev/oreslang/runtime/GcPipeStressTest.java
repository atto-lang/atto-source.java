package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional deterministic GC/actor teardown pressure tests. These exercise
 * explicit cleanup and scheduler races, not nondeterministic JVM collection.
 */
final class GcPipeStressTest {
    @Test
    @Timeout(30)
    void concurrentReleaseAndSweepNeverDoubleCloseForeignResources() throws Exception {
        final int count = 240;
        AtomicInteger hostCollections = new AtomicInteger();
        AtomicIntegerArray finalized = new AtomicIntegerArray(count);
        List<RuntimeGarbageCollector.CleanupHandle> handles = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                hostCollections::incrementAndGet, Duration.ofHours(1));
             ExecutorService pool = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < count; i++) {
                final int index = i;
                Object owner = new Object();
                owners.add(owner);
                handles.add(gc.track(owner, () -> {
                    if (finalized.incrementAndGet(index) != 1)
                        throw new AssertionError("double resource cleanup " + index);
                }));
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (var handle : handles) {
                tasks.add(pool.submit(() -> {
                    try {
                        go.await();
                        handle.close();
                        handle.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> {
                    try {
                        go.await();
                        for (int j = 0; j < 24; j++) {
                            var report = gc.collectPeriodic();
                            assertTrue(report.inspected() <= 256);
                            assertFalse(report.jvmGcRequested());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            go.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
            for (int i = 0; i < count; i++) {
                assertEquals(1, finalized.get(i), "exactly-once cleanup " + i);
            }
            assertEquals(0, gc.collectPeriodic().trackedAfter());
            assertEquals(0, hostCollections.get());
            java.lang.ref.Reference.reachabilityFence(owners);
        }
    }

    @Test
    @Timeout(30)
    void repeatedEmptyAndFullResourceWavesDoNotLeakRegistryOrStartJvmGc() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger jvmGc = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                jvmGc::incrementAndGet, Duration.ofHours(1))) {
            for (int wave = 0; wave < 30; wave++) {
                List<RuntimeGarbageCollector.CleanupHandle> hooks = new ArrayList<>();
                List<Object> owners = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    Object owner = new Object();
                    owners.add(owner);
                    hooks.add(gc.track(owner, closes::incrementAndGet));
                }
                for (int i = hooks.size() - 1; i >= 0; i--) hooks.get(i).close();
                var report = gc.collectPeriodic();
                assertEquals(0, report.trackedAfter(), "registry leaked at wave " + wave);
                assertFalse(report.jvmGcRequested());
                java.lang.ref.Reference.reachabilityFence(owners);
            }
            assertEquals(1920, closes.get());
            assertEquals(0, jvmGc.get());
        }
    }

    @Test
    @Timeout(30)
    void actorExitWithRetainedOwnerOnlyReclaimsItsOwnForeignResources() throws Exception {
        AtomicInteger firstCleanups = new AtomicInteger();
        AtomicInteger secondCleanups = new AtomicInteger();
        AtomicInteger jvmCollections = new AtomicInteger();
        CountDownLatch firstRegistered = new CountDownLatch(1);
        CountDownLatch secondRegistered = new CountDownLatch(1);
        List<Object> owners = new ArrayList<>();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                    jvmCollections::incrementAndGet, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);
            var first = runtime.<String>spawn(() -> (mail, ctx) -> {
                for (int i = 0; i < 96; i++) {
                    Object owner = new Object();
                    owners.add(owner);
                    gc.track(owner, firstCleanups::incrementAndGet);
                }
                firstRegistered.countDown();
                ctx.self().stop();
            });
            var second = runtime.<String>spawn(() -> (mail, ctx) -> {
                Object owner = new Object();
                owners.add(owner);
                gc.track(owner, secondCleanups::incrementAndGet);
                secondRegistered.countDown();
                // The second actor deliberately remains alive.
            });
            first.send("retire");
            second.send("stay");
            assertTrue(firstRegistered.await(10, TimeUnit.SECONDS));
            assertTrue(secondRegistered.await(10, TimeUnit.SECONDS));
            assertTrue(first.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(32, firstCleanups.get(), "actor finalization must remain bounded");
            assertEquals(0, secondCleanups.get(), "unrelated live actor must not be collected");
            for (int i = 0; i < 8 && firstCleanups.get() < 96; i++)
                assertFalse(gc.collectPeriodic().jvmGcRequested());
            assertEquals(96, firstCleanups.get());
            assertEquals(0, secondCleanups.get(), "living actor's foreign owner remains reachable");
            assertEquals(0, jvmCollections.get());
            second.stop();
            assertTrue(second.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, secondCleanups.get(), "second actor cleaned at its own termination");
            java.lang.ref.Reference.reachabilityFence(owners);
        }
    }
}
