package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Startup thread contract for actor pools: no actor carriers at boot, the
 * first admitted actor of a kind starts exactly one carrier in that kind's
 * pool, idle actors add none, and runnable work grows lanes up to the limit.
 */
@Timeout(30)
final class ActorPoolLazyStartTest {

    private static boolean nativePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") || os.contains("mac") || os.contains("darwin");
    }

    private static long liveThreadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(prefix))
                .count();
    }

    private interface Body { void run() throws Exception; }

    private static void withNativeCarriers(Body body) throws Exception {
        assumeTrue(nativePlatform());
        String previousCarriers = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        System.setProperty("ores.runtime.carriers", "native");
        System.clearProperty("ores.actor.affinity");
        try {
            body.run();
        } finally {
            if (previousCarriers == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousCarriers);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test
    void nativeExecutorCreatesNoPthreadUntilStarted() throws Exception {
        assumeTrue(nativePlatform());
        String prefix = "ores-lazy-probe-" + System.nanoTime() + "-";
        try (var executor = new NativeCarrierExecutor(1, 1, 8, prefix)) {
            assertFalse(executor.isStarted());
            assertEquals(0, liveThreadsNamed(prefix), "construction must not create a pthread");
            assertEquals(0, executor.getLargestPoolSize());

            CountDownLatch ran = new CountDownLatch(1);
            executor.execute(ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertTrue(executor.isStarted());
            assertEquals(1, liveThreadsNamed(prefix));
            assertFalse(executor.ensureStarted(), "a second start is a no-op");
            assertEquals(1, liveThreadsNamed(prefix));
            executor.shutdownNow();
            assertThrows(RejectedExecutionException.class, executor::ensureStarted,
                    "a retired native handle must not masquerade as a live carrier");
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        }
    }

    @Test
    void neverStartedExecutorShutsDownCleanly() throws Exception {
        assumeTrue(nativePlatform());
        var executor = new NativeCarrierExecutor(2, 2, 8, "ores-lazy-unused-");
        executor.setCorePoolSize(1);
        assertTrue(executor.shutdownNow().isEmpty());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(0, TimeUnit.SECONDS));
        assertEquals(0L, executor.carrierCpuTimeNanos(0));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        assertThrows(RejectedExecutionException.class, executor::ensureStarted);
        assertFalse(executor.isStarted());
    }

    @Test
    void runtimeBootsWithZeroActorCarriersAndFirstActorStartsOne() throws Exception {
        withNativeCarriers(() -> {
            var config = new ActorRuntime.DispatcherConfig(2, 4, 8, 1024);
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                assertEquals(ActorRuntime.CarrierBackend.NATIVE_PTHREAD, runtime.carrierBackend());
                assertEquals(new ActorRuntime.ActorCarrierCounts(0, 0, 0),
                        runtime.startedActorCarriers(), "boot must not create actor carriers");

                var first = runtime.spawnShared(() -> (message, context) -> {});
                assertEquals(new ActorRuntime.ActorCarrierCounts(0, 1, 0),
                        runtime.startedActorCarriers(),
                        "first shared actor starts exactly one carrier, in the shared pool only");

                List<ActorRuntime.ActorRef<Object>> idle = new ArrayList<>();
                for (int i = 0; i < 500; i++) idle.add(runtime.spawnShared(() -> (message, context) -> {}));
                assertEquals(1, runtime.startedActorCarriers().sharedCarriers(),
                        "idle actors must not add carriers");

                var privateActor = runtime.spawn(() -> (message, context) -> {});
                assertEquals(new ActorRuntime.ActorCarrierCounts(1, 1, 0),
                        runtime.startedActorCarriers());

                // UNTRUSTED must activate only its own worker domain. This
                // proves dispatcher separation, not an OS/process sandbox.
                var untrustedActor = runtime.<Object>spawnUntrusted(
                        factoryContext -> (message, turnContext) -> {});
                assertEquals(new ActorRuntime.ActorCarrierCounts(1, 1, 1),
                        runtime.startedActorCarriers());

                first.stop();
                privateActor.stop();
                untrustedActor.stop();
                idle.forEach(ActorRuntime.ActorRef::stop);
            }
        });
    }

    @Test
    void concurrentRunnableWorkGrowsLanesUpToParallelism() throws Exception {
        withNativeCarriers(() -> {
            int parallelism = 4;
            var config = new ActorRuntime.DispatcherConfig(1, parallelism, 8, 1024);
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                CountDownLatch entered = new CountDownLatch(parallelism);
                CountDownLatch release = new CountDownLatch(1);
                List<ActorRuntime.ActorRef<String>> actors = new ArrayList<>();
                // Spawned while idle, so all of them start out placed on lane 0.
                for (int i = 0; i < parallelism; i++) {
                    actors.add(runtime.<String>spawnShared(() -> (message, context) -> {
                        entered.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }
                assertEquals(1, runtime.startedActorCarriers().sharedCarriers());

                for (var actor : actors) actor.send("go");
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS),
                            "blocked turns must trigger lane growth instead of queueing behind lane 0");
                    assertEquals(parallelism, runtime.startedActorCarriers().sharedCarriers());
                    // Each moved actor adopts its new lane, so later turns stay put.
                    var homes = new java.util.HashSet<Integer>();
                    for (var actor : actors) homes.add(runtime.placementFor(actor).orElseThrow().lane());
                    assertEquals(parallelism, homes.size(), "actors must re-home onto the lanes they moved to");
                } finally {
                    release.countDown();
                }

                // More runnable work cannot exceed the configured limit.
                CountDownLatch more = new CountDownLatch(64);
                var burst = runtime.<Integer>spawnShared(() -> (message, context) -> more.countDown());
                for (int i = 0; i < 64; i++) burst.send(i);
                assertTrue(more.await(10, TimeUnit.SECONDS));
                assertEquals(parallelism, runtime.startedActorCarriers().sharedCarriers());

                actors.forEach(ActorRuntime.ActorRef::stop);
                burst.stop();
            }
        });
    }

    @Test
    void concurrentFirstSpawnsStartOneCarrier() throws Exception {
        withNativeCarriers(() -> {
            var config = new ActorRuntime.DispatcherConfig(1, 4, 8, 1024);
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                int callers = 16;
                CountDownLatch go = new CountDownLatch(1);
                ExecutorService spawners = Executors.newFixedThreadPool(callers);
                try {
                    List<java.util.concurrent.Future<ActorRuntime.ActorRef<Object>>> refs = new ArrayList<>();
                    for (int i = 0; i < callers; i++) {
                        refs.add(spawners.submit(() -> {
                            go.await();
                            return runtime.spawnShared(() -> (message, context) -> {});
                        }));
                    }
                    go.countDown();
                    for (var ref : refs) ref.get(10, TimeUnit.SECONDS).stop();
                } finally {
                    spawners.shutdownNow();
                }
                assertEquals(1, runtime.startedActorCarriers().sharedCarriers());
            }
        });
    }

    /** Lane executor whose carrier cannot be started, standing in for EAGAIN from pthread_create. */
    private static final class UnstartableLane extends ThreadPoolExecutor {
        UnstartableLane() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
        }
        @Override public boolean prestartCoreThread() {
            throw new IllegalStateException("pthread_create failed (simulated)");
        }
    }

    @Test
    void failedLaneGrowthKeepsServingOnStartedLanes() throws Exception {
        var dispatcher = new ActorAffinityDispatcher(2, 64, new int[0],
                lane -> lane == 0
                        ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64))
                        : new UnstartableLane(),
                true);
        try {
            assertEquals(0, dispatcher.startedLanes());
            dispatcher.ensureLaneStarted(0);
            assertEquals(1, dispatcher.startedLanes());

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch queuedRan = new CountDownLatch(1);
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Lane 0 is busy, so this tries to grow; growth fails and the turn
            // queues on lane 0 rather than being rejected.
            dispatcher.executeOnLane(0, queuedRan::countDown);
            assertEquals(1, dispatcher.laneStartFailures());
            assertEquals(1, dispatcher.startedLanes());
            release.countDown();
            assertTrue(queuedRan.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> dispatcher.ensureLaneStarted(1),
                    "admission must surface a carrier start failure");
        } finally {
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void idleLaneIsPreferredOverGrowth() throws Exception {
        List<ThreadPoolExecutor> laneExecutors = new ArrayList<>();
        var dispatcher = new ActorAffinityDispatcher(4, 64, new int[0], lane -> {
            var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
            laneExecutors.add(executor);
            return executor;
        }, true);
        try {
            dispatcher.ensureLaneStarted(0);
            ThreadPoolExecutor lane0 = laneExecutors.get(0);
            for (int i = 0; i < 200; i++) {
                dispatcher.executeOnLane(dispatcher.reserveLane(), () -> {});
                // Completed count includes the dispatcher's own bookkeeping
                // after the turn, so the lane is truly idle once it advances.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (lane0.getCompletedTaskCount() < i + 1L) {
                    assertTrue(System.nanoTime() < deadline, "lane 0 stalled");
                    Thread.onSpinWait();
                }
            }
            assertEquals(1, dispatcher.startedLanes(),
                    "sequential work on an idle lane must not start more lanes");
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** A JVM lane that refuses prestart must not be mistaken for a live actor carrier. */
    @Test
    void falsePrestartFailsClosedWithoutPublishingLane() {
        var lane = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16)) {
            @Override public boolean prestartCoreThread() { return false; }
        };
        var dispatcher = new ActorAffinityDispatcher(1, 16, new int[0], ignored -> lane, true);
        try {
            assertThrows(RejectedExecutionException.class, () -> dispatcher.ensureLaneStarted(0));
            assertEquals(0, dispatcher.startedLanes());
            assertEquals(0, lane.getPoolSize());
        } finally {
            dispatcher.shutdownNow();
        }
    }

    /** Close cannot pass an in-flight first-worker bootstrap and then admit a new worker. */
    @Test
    void firstLaneStartAndShutdownShareOneLifecycleGate() throws Exception {
        CountDownLatch prestartEntered = new CountDownLatch(1);
        CountDownLatch releasePrestart = new CountDownLatch(1);
        var lane = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16)) {
            @Override public boolean prestartCoreThread() {
                prestartEntered.countDown();
                try {
                    if (!releasePrestart.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("prestart admission hung");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("prestart interrupted", interrupted);
                }
                return super.prestartCoreThread();
            }
        };
        var dispatcher = new ActorAffinityDispatcher(1, 16, new int[0], ignored -> lane, true);
        ExecutorService actors = Executors.newFixedThreadPool(2);
        try {
            Future<?> start = actors.submit(() -> dispatcher.ensureLaneStarted(0));
            assertTrue(prestartEntered.await(5, TimeUnit.SECONDS));
            Future<List<Runnable>> shutdown = actors.submit(dispatcher::shutdownNow);
            releasePrestart.countDown();
            start.get(5, TimeUnit.SECONDS);
            assertTrue(shutdown.get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(lane.isShutdown());
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> dispatcher.ensureLaneStarted(0));
        } finally {
            releasePrestart.countDown();
            actors.shutdownNow();
            dispatcher.shutdownNow();
        }
    }

    /** Every successful enqueue must either execute or be returned by shutdownNow(). */
    @Test
    void nativeCloseVersusSubmitAccountsForEveryAcceptedRunnable() throws Exception {
        withNativeCarriers(() -> {
            for (int round = 0; round < 8; ++round) {
                var executor = new NativeCarrierExecutor(1, 1, 512,
                        "ores-lazy-shutdown-race-" + round + "-");
                ExecutorService producers = Executors.newFixedThreadPool(8);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                try {
                    executor.execute(() -> {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
                    assertTrue(entered.await(5, TimeUnit.SECONDS));

                    class Tagged implements Runnable {
                        private final AtomicInteger executions = new AtomicInteger();
                        @Override public void run() { executions.incrementAndGet(); }
                    }
                    var admitted = Collections.synchronizedList(new ArrayList<Tagged>());
                    CountDownLatch go = new CountDownLatch(1);
                    List<Future<?>> submissions = new ArrayList<>();
                    for (int i = 0; i < 256; ++i) {
                        submissions.add(producers.submit(() -> {
                            go.await();
                            Tagged task = new Tagged();
                            try {
                                executor.execute(task);
                                admitted.add(task);
                            } catch (RejectedExecutionException closed) {
                                // Close or capacity may win admission. Rejection owns no task.
                            }
                            return null;
                        }));
                    }
                    go.countDown();
                    List<Runnable> abandoned = executor.shutdownNow();
                    for (Future<?> submission : submissions) submission.get(10, TimeUnit.SECONDS);
                    release.countDown();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                    Set<Runnable> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
                    discarded.addAll(abandoned);
                    for (Tagged task : admitted) {
                        assertEquals(1, task.executions.get() + (discarded.contains(task) ? 1 : 0),
                                "accepted native task was lost or executed more than once");
                    }
                } finally {
                    release.countDown();
                    producers.shutdownNow();
                    executor.shutdownNow();
                }
            }
        });
    }
}
