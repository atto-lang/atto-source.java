package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorCell fail-stop integration, with reflection confined to the host
 * test. In particular, Future cancellation callbacks may need lifecycleLock
 * from a DIFFERENT thread: calling terminateTree while an outer caller holds
 * that monitor would deadlock even though terminateTree drops its inner lock.
 */
@Timeout(20)
final class ActorSourceCompletionStopLockTest {
    private static Object actorCell(
            ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) throws Exception {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        return ((Map<?, ?>) actors.get(runtime)).get(actor.id());
    }

    private static Object field(Object cell, String name) throws Exception {
        Field target = cell.getClass().getDeclaredField(name);
        target.setAccessible(true);
        return target.get(cell);
    }

    private static void failIfRunning(Object cell, Throwable cause) throws Exception {
        Method method = cell.getClass().getDeclaredMethod("failIfRunning", Throwable.class);
        method.setAccessible(true);
        try {
            method.invoke(cell, cause);
        } catch (InvocationTargetException failure) {
            Throwable target = failure.getCause();
            if (target instanceof Exception checked) throw checked;
            if (target instanceof Error error) throw error;
            throw new AssertionError(target);
        }
    }

    @Test
    void failStopCancelsFutureWithoutHoldingLifecycleLockAcrossCallback() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 16, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {});
            actor.ready().get(3, TimeUnit.SECONDS);
            Object cell = actorCell(runtime, actor);
            Object lifecycleLock = field(cell, "lifecycleLock");
            @SuppressWarnings("unchecked")
            Set<OresFuture<?>> pending =
                    (Set<OresFuture<?>>) field(cell, "pendingOperations");

            OresFuture<Integer> producer = new OresFuture<>();
            AtomicBoolean callbackCouldEnterMonitor = new AtomicBoolean();
            CountDownLatch callbackEntered = new CountDownLatch(1);
            // The Future cancellation callback is invoked synchronously by
            // ActorCell termination; its observer needs the lifecycle monitor
            // from a separate carrier, not reentrant acquisition on the same
            // thread.
            producer.whenCompleteRuntime((ignored, failure) -> {
                callbackEntered.countDown();
                CountDownLatch acquired = new CountDownLatch(1);
                Thread observer = Thread.ofPlatform().daemon(true).start(() -> {
                    synchronized (lifecycleLock) {
                        acquired.countDown();
                    }
                });
                try {
                    callbackCouldEnterMonitor.set(
                            acquired.await(2, TimeUnit.SECONDS));
                    observer.join(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            pending.add(producer);
            failIfRunning(cell, new IllegalStateException("forced completion admission failure"));

            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            assertTrue(callbackCouldEnterMonitor.get(),
                    "a Future cancellation callback cannot run under the actor lifecycle monitor");
            assertTrue(producer.isCancelled());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent(),
                    "fail-stop must preserve the original completion admission failure");
        }
    }

    @Test
    void lateFailedCompletionCannotPoisonAnAlreadyStoppingActor() throws Exception {
        CountDownLatch inReceive = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 16, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                inReceive.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            });
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                actor.send(1);
                assertTrue(inReceive.await(3, TimeUnit.SECONDS));
                Object cell = actorCell(runtime, actor);
                actor.stop(); // already stopping, but the carrier has not exited
                failIfRunning(cell, new IllegalStateException(
                        "stale source completion must not override orderly stop"));
            } finally {
                release.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),
                    "late rejected completion must not upgrade orderly stop to failure");
        }
    }
}
