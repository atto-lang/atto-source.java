package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the physical stop barrier, not a mocked actor cell. */
@Timeout(20)
final class ActorTwoPhaseFinalizationTest {
    private static Object cell(ActorRuntime runtime, ActorRuntime.ActorRef<?> actor)
            throws Exception {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        return ((Map<?, ?>) actors.get(runtime)).get(actor.id());
    }

    private static Object lifecycleLock(Object cell) throws Exception {
        Field lock = cell.getClass().getDeclaredField("lifecycleLock");
        lock.setAccessible(true);
        return lock.get(cell);
    }

    @Test
    void actorExitHookNeverOwnsLifecycleMonitorAndCompletionWaitsForHook()
            throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 16, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {});
            actor.ready().get(3, TimeUnit.SECONDS);
            Object lock = lifecycleLock(cell(runtime, actor));
            AtomicInteger invocations = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> hookError = new AtomicReference<>();
            runtime.setActorExitHook(domain -> {
                invocations.incrementAndGet();
                entered.countDown();
                CountDownLatch acquired = new CountDownLatch(1);
                Thread probe = Thread.ofPlatform().daemon(true).start(() -> {
                    synchronized (lock) {
                        acquired.countDown();
                    }
                });
                try {
                    if (!acquired.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("terminal hook retained lifecycle monitor");
                    }
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("release never signaled");
                    }
                    probe.join(2_000L);
                } catch (Throwable failure) {
                    hookError.compareAndSet(null, failure);
                }
            });

            Thread stopper = Thread.ofPlatform().daemon(true).start(actor::stop);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "real actor exit hook must be entered");
                assertFalse(actor.done().isDone(),
                        "actor done cannot publish while finalization hook is blocked");
                assertFalse(actor.awaitTermination(50, TimeUnit.MILLISECONDS),
                        "physical finalization barrier cannot settle early");
                actor.stop(); // idempotent, while initial finalization owns the barrier
            } finally {
                release.countDown();
                stopper.join(3_000L);
            }
            assertNull(hookError.get(), () -> "hook failure: " + hookError.get());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertEquals(1, invocations.get(),
                    "only one carrier may claim physical finalization");
        }
    }

    @Test
    void terminalFutureObserverRunsOutsideActorLifecycleMonitor()
            throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 16, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {});
            actor.ready().get(3, TimeUnit.SECONDS);
            Object lock = lifecycleLock(cell(runtime, actor));
            CountDownLatch callbackRan = new CountDownLatch(1);
            AtomicReference<Throwable> callbackError = new AtomicReference<>();
            actor.done().whenCompleteRuntime((ignored, failure) -> {
                CountDownLatch acquired = new CountDownLatch(1);
                Thread probe = Thread.ofPlatform().daemon(true).start(() -> {
                    synchronized (lock) {
                        acquired.countDown();
                    }
                });
                try {
                    if (!acquired.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("terminal observer retained lifecycle monitor");
                    }
                    probe.join(2_000);
                } catch (Throwable problem) {
                    callbackError.compareAndSet(null, problem);
                } finally {
                    callbackRan.countDown();
                }
            });
            actor.stop();
            assertTrue(callbackRan.await(3, TimeUnit.SECONDS));
            assertNull(callbackError.get(), () -> "callback failure: " + callbackError.get());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
