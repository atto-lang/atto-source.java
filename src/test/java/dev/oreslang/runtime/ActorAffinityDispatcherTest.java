package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ActorAffinityDispatcherTest {
    @Test
    void aBusyLaneDoesNotMigrateItsQueuedWorkAndShutdownReclaimsCapacity() throws Exception {
        // Queued work stays home only with idle stealing off (ActorLaneIdleStealTest
        // covers migration of a turn stuck behind a busy lane).
        var dispatcher = new ActorAffinityDispatcher(2, 1, new int[0],
                i -> Executors.newSingleThreadExecutor(), false, -1L);
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
    void rejectedLaneAwareCallbackCannotBeReplayedAfterShutdown() throws Exception {
        var dispatcher = new ActorAffinityDispatcher(1, 2, new int[0],
                i -> Executors.newSingleThreadExecutor(), false, -1L);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger callbackRuns = new AtomicInteger();
        try {
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) {
                        // Keep the carrier occupied until shutdown has reclaimed
                        // the rejected queued callback, even if interrupted.
                    }
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            dispatcher.executeOnLane(0, (int observedLane) -> callbackRuns.incrementAndGet());
            List<Runnable> abandoned = dispatcher.shutdownNow();
            assertEquals(1, abandoned.size());
            assertEquals(0, callbackRuns.get());
            // The returned task is already rejected and must stay inert.
            abandoned.get(0).run();
            assertEquals(0, callbackRuns.get());
            assertEquals(2, dispatcher.availableQueueSlots());
        } finally {
            release.countDown();
            dispatcher.shutdownNow();
            assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
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


    @Test
    void nativeLaneMayRequestOwnDispatcherShutdownWithoutLosingQueuedWork()
            throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                os.contains("linux") || os.contains("mac") || os.contains("darwin"));
        var dispatcher = new ActorAffinityDispatcher(1, 8, new int[0],
                i -> new NativeCarrierExecutor(
                        1, 1, 8, "ores-affinity-self-close-" + System.nanoTime() + "-"), true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch authorizeClose = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<List<Runnable>> abandoned = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicInteger queuedRuns = new AtomicInteger();
        Runnable pending = queuedRuns::incrementAndGet;
        try {
            dispatcher.ensureLaneStarted(0);
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                try {
                    if (!authorizeClose.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to request self-shutdown");
                    }
                    abandoned.set(dispatcher.shutdownNow());
                } catch (Throwable failure) {
                    unexpected.set(failure);
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            dispatcher.executeOnLane(0, pending);
            authorizeClose.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertNull(unexpected.get(), () -> String.valueOf(unexpected.get()));
            assertEquals(List.of(pending), abandoned.get(),
                    "self-shutdown must return the queued, unexecuted command");
            assertEquals(0, queuedRuns.get());
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS),
                    "all native pthread joins must complete after the active turn unwinds");
            assertTrue(dispatcher.isTerminated());
        } finally {
            authorizeClose.countDown();
            dispatcher.shutdownNow();
        }
    }
}
