package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ActorAffinityDispatcherTest {
    @Test
    void aBusyLaneDoesNotMigrateItsQueuedWorkAndShutdownReclaimsCapacity() throws Exception {
        var dispatcher = new ActorAffinityDispatcher(2, 1, new int[0], i -> Executors.newSingleThreadExecutor());
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger abandonedRuns = new AtomicInteger();
        Runnable pending = abandonedRuns::incrementAndGet;
        try {
            dispatcher.executeOnLane(0, () -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            dispatcher.executeOnLane(0, pending);
            assertThrows(RejectedExecutionException.class, () -> dispatcher.executeOnLane(1, () -> {}));
            List<Runnable> abandoned = dispatcher.shutdownNow();
            assertEquals(List.of(pending), abandoned);
            assertEquals(0, abandonedRuns.get());
            assertThrows(RejectedExecutionException.class, () -> dispatcher.executeOnLane(1, () -> {}));
            assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); dispatcher.shutdownNow(); }
    }

    @Test
    void nioHostNotificationsReuseAlreadyStartedLanesWithoutBootstrappingColdLanes()
            throws Exception {
        var executors = new java.util.ArrayList<java.util.concurrent.ThreadPoolExecutor>();
        var dispatcher = new ActorAffinityDispatcher(3, 64, new int[0], index -> {
            var pool = new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 60L, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
                    task -> new Thread(task, "test-affinity-lane-" + index));
            executors.add(pool);
            return pool;
        });
        try {
            var initial = new CountDownLatch(1);
            dispatcher.executeOnLane(2, initial::countDown);
            assertTrue(initial.await(2, TimeUnit.SECONDS));
            assertEquals(0, executors.get(0).getPoolSize());
            assertEquals(0, executors.get(1).getPoolSize());
            assertEquals(1, executors.get(2).getPoolSize());

            var delivered = new CountDownLatch(24);
            var wrongLane = new AtomicInteger();
            for (int i = 0; i < 24; i++) {
                dispatcher.executeHostOnWarmLane(() -> {
                    if (!Thread.currentThread().getName().equals("test-affinity-lane-2"))
                        wrongLane.incrementAndGet();
                    delivered.countDown();
                });
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(0, wrongLane.get());
            assertEquals(0, executors.get(0).getPoolSize(),
                    "host-only notifications must not bootstrap an unused actor lane");
            assertEquals(0, executors.get(1).getPoolSize(),
                    "host-only notifications must not bootstrap an unused actor lane");
        } finally {
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void nativeNioHostCompletionsReuseExistingPthreadWithoutStartingColdLanes()
            throws Exception {
        var nativeLanes = new java.util.ArrayList<NativeCarrierExecutor>();
        var dispatcher = new ActorAffinityDispatcher(3, 64, new int[0], index -> {
            var lane = new NativeCarrierExecutor(
                    1, 1, 64, "ores-native-host-affinity-" + index + "-");
            nativeLanes.add(lane);
            return lane;
        });
        try {
            assertEquals(0, dispatcher.startedCarrierCount());
            var running = new CountDownLatch(1);
            var nativeTid = new java.util.concurrent.atomic.AtomicLong();
            dispatcher.executeOnLane(2, () -> {
                nativeTid.set(NativeCarrierExecutor.currentNativeThreadId());
                running.countDown();
            });
            assertTrue(running.await(3, TimeUnit.SECONDS));
            assertTrue(nativeTid.get() > 0);
            assertEquals(0, nativeLanes.get(0).getStartedCarrierCount());
            assertEquals(0, nativeLanes.get(1).getStartedCarrierCount());
            assertTrue(nativeLanes.get(2).readyForNoBootstrapAdmission());

            var allDelivered = new CountDownLatch(48);
            var wrongPthread = new AtomicInteger();
            for (int i = 0; i < 48; i++) {
                dispatcher.executeHostOnWarmLane(() -> {
                    if (NativeCarrierExecutor.currentNativeThreadId() != nativeTid.get())
                        wrongPthread.incrementAndGet();
                    allDelivered.countDown();
                });
            }
            assertTrue(allDelivered.await(5, TimeUnit.SECONDS));
            assertEquals(0, wrongPthread.get());
            assertEquals(0, nativeLanes.get(0).getStartedCarrierCount(),
                    "host completions must not create native lane zero");
            assertEquals(0, nativeLanes.get(1).getStartedCarrierCount(),
                    "host completions must not create native lane one");
            assertEquals(1, dispatcher.startedCarrierCount(),
                    "all 48 host completions reuse exactly one native pthread");
        } finally {
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void busyHostSubscriberCannotHideAnIdleAlreadyWarmLane() throws Exception {
        var executors = new java.util.ArrayList<java.util.concurrent.ThreadPoolExecutor>();
        var dispatcher = new ActorAffinityDispatcher(3, 32, new int[0], index -> {
            var pool = new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 60L, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
                    task -> new Thread(task, "test-warm-host-lane-" + index));
            executors.add(pool);
            return pool;
        });
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            dispatcher.executeOnLane(0, () -> {
                blocked.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            var warm = new CountDownLatch(1);
            dispatcher.executeOnLane(2, warm::countDown);
            assertTrue(warm.await(2, TimeUnit.SECONDS));

            var notification = new CountDownLatch(1);
            var chosen = new java.util.concurrent.atomic.AtomicReference<String>();
            dispatcher.executeHostOnWarmLane(() -> {
                chosen.set(Thread.currentThread().getName());
                notification.countDown();
            });
            assertTrue(notification.await(2, TimeUnit.SECONDS),
                    "idle warm lane must not wait behind a blocked subscriber");
            assertEquals("test-warm-host-lane-2", chosen.get());
            assertEquals(0, executors.get(1).getPoolSize(),
                    "host notifications must not bootstrap a third native lane");
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void transientWarmLaneAdmissionContentionFallsBackToAnotherWarmLane()
            throws Exception {
        var executors = new java.util.ArrayList<AdaptiveCarrierExecutor>();
        var dispatcher = new ActorAffinityDispatcher(2, 16, new int[0], index -> {
            var pool = new AdaptiveCarrierExecutor(
                    1, 1, 16,
                    task -> Thread.ofPlatform().daemon(true)
                            .name("test-fallback-lane-" + index).unstarted(task));
            executors.add(pool);
            return pool;
        });
        var releaseLock = new CountDownLatch(1);
        Thread lockHolder = null;
        try {
            assertEquals(1, executors.get(0).prestartAllCoreThreads());
            assertEquals(1, executors.get(1).prestartAllCoreThreads());

            var field = AdaptiveCarrierExecutor.class.getDeclaredField("admission");
            field.setAccessible(true);
            var admission =
                    (java.util.concurrent.locks.ReentrantLock) field.get(executors.get(0));
            var lockHeld = new CountDownLatch(1);
            lockHolder = Thread.ofPlatform().daemon(true).start(() -> {
                admission.lock();
                try {
                    lockHeld.countDown();
                    try { releaseLock.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    admission.unlock();
                }
            });
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS));

            var delivered = new CountDownLatch(1);
            var chosen = new java.util.concurrent.atomic.AtomicReference<String>();
            dispatcher.executeHostOnWarmLane(() -> {
                chosen.set(Thread.currentThread().getName());
                delivered.countDown();
            });
            assertTrue(delivered.await(2, TimeUnit.SECONDS),
                    "a contended best lane must not hide another warm lane");
            assertEquals("test-fallback-lane-1", chosen.get());
            assertEquals(1, executors.get(0).getPoolSize());
            assertEquals(1, executors.get(1).getPoolSize());
        } finally {
            releaseLock.countDown();
            if (lockHolder != null) lockHolder.join(3000);
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void firstColdNioNotificationDeterministicallyStartsLaneZero() throws Exception {
        var executors = new java.util.ArrayList<java.util.concurrent.ThreadPoolExecutor>();
        var dispatcher = new ActorAffinityDispatcher(3, 16, new int[0], index -> {
            var pool = new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 60L, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
                    task -> new Thread(task, "test-cold-host-lane-" + index));
            executors.add(pool);
            return pool;
        });
        try {
            var delivered = new CountDownLatch(1);
            dispatcher.executeHostOnWarmLane(delivered::countDown);
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(1, executors.get(0).getPoolSize());
            assertEquals(0, executors.get(1).getPoolSize());
            assertEquals(0, executors.get(2).getPoolSize());
        } finally {
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void defaultRuntimeReportsLaneAffinityWithoutClaimingCpuBinding() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.spawnShared(() -> (message, context) -> {});
            try {
                var placement = runtime.placementFor(actor).orElseThrow();
                assertEquals("lane", placement.affinity());
                assertTrue(placement.lane() >= 0);
                assertEquals(-1, placement.cpu());
            } finally { actor.stop(); }
        }
    }

    @Test
    void invalidOrUnsupportedCpuBindingFailsBeforeStartingLanes() {
        AtomicInteger created = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new ActorAffinityDispatcher(
                2, 8, new int[] { -1 }, lane -> {
                    created.incrementAndGet(); return Executors.newSingleThreadExecutor();
                }));
        assertEquals(0, created.get());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void linuxActorExecutesOnItsConfiguredCpu() throws Exception {
        int cpu = NativeCarrierExecutor.currentCpu();
        assertTrue(cpu >= 0);
        String previousMode = System.getProperty("ores.actor.affinity");
        String previousCpus = System.getProperty("ores.actor.cpus");
        try {
            System.setProperty("ores.actor.affinity", "cpu");
            System.setProperty("ores.actor.cpus", Integer.toString(cpu));
            try (var runtime = new ActorRuntime()) {
                AtomicInteger observed = new AtomicInteger(-1);
                var actor = runtime.spawnShared(() -> (message, context) -> {
                    observed.set(NativeCarrierExecutor.currentCpu());
                    context.self().stop();
                });
                var placement = runtime.placementFor(actor).orElseThrow();
                assertEquals("cpu", placement.affinity());
                assertEquals(cpu, placement.cpu());
                actor.send("run");
                actor.done().get(2, TimeUnit.SECONDS);
                assertEquals(cpu, observed.get());
            }
        } finally {
            if (previousMode == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousMode);
            if (previousCpus == null) System.clearProperty("ores.actor.cpus");
            else System.setProperty("ores.actor.cpus", previousCpus);
        }
    }

}
