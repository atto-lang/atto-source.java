package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorRuntimeGroupParallelismTest {
    private static ActorGroupPlacementPolicy limit(int turns) {
        return new ActorGroupPlacementPolicy(
                ActorGroupPlacementPolicy.CpuAffinity.ANY, turns,
                ActorGroupPlacementPolicy.Accelerator.CPU_ONLY, null, 0);
    }

    @Test
    void groupSerializesPhysicalTurnsWithoutNewCarrierThreads() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ActorRuntime.TurnExecutor host = turn -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstEntered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS),
                        "test host did not release the admitted carrier");
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, interrupted);
            } finally {
                active.decrementAndGet();
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 16), host)) {
            var group = runtime.createActorGroup(limit(1));
            var one = group.manageWorker(runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> processed.countDown()));
            var two = group.manageWorker(runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> processed.countDown()));
            one.send(1);
            two.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, active.get(),
                    "second actor must remain outside its TurnExecutor until permit release");
            release.countDown();
            assertTrue(processed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, maxActive.get());
            group.close();
            assertTrue(one.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(two.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void groupAllowsTwoParallelTurnsButDefersThird() throws Exception {
        CountDownLatch twoEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ActorRuntime.TurnExecutor host = turn -> {
            int inFlight = active.incrementAndGet();
            maxActive.accumulateAndGet(inFlight, Math::max);
            twoEntered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("host release timed out");
                }
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                active.decrementAndGet();
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 3, 8, 16), host)) {
            var group = runtime.createActorGroup(limit(2));
            var actors = new java.util.ArrayList<ActorRuntime.ActorRef<Integer>>();
            for (int i = 0; i < 3; i++) {
                actors.add(group.manageWorker(runtime.spawnSharedTrusted(
                        IsolatePolicy.developer(),
                        factory -> (message, context) -> processed.countDown())));
            }
            for (var actor : actors) actor.send(1);
            assertTrue(twoEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, active.get());
            release.countDown();
            assertTrue(processed.await(5, TimeUnit.SECONDS));
            assertEquals(2, maxActive.get());
            group.close();
            for (var actor : actors) assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void activeActorCannotBeRetrofittedIntoBoundedGroupAndTransferFailsClosed()
            throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ActorRuntime.TurnExecutor host = turn -> {
            seen.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 16), host)) {
            var group = runtime.createActorGroup(limit(1));
            var running = runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(), factory -> (message, context) -> { });
            running.send(1);
            assertTrue(seen.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> group.manageWorker(running));
            var cold = group.manageWorker(runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(), factory -> (message, context) -> { }));
            assertThrows(UnsupportedOperationException.class,
                    () -> group.releaseWorker(cold));
            release.countDown();
            group.close();
            assertTrue(cold.awaitTermination(5, TimeUnit.SECONDS));
            running.stop();
            assertTrue(running.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
