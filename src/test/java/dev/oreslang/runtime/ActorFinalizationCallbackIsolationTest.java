package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies completion and host-exit notifications do not run under the
 * actor's lifecycle monitor. Without this check, a listener can indirectly
 * re-enter actor cancellation and form a cross-thread lock-order cycle.
 */
@Timeout(15)
final class ActorFinalizationCallbackIsolationTest {
    private static Object lifecycleLock(ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor) throws Exception {
        Field actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ActorRuntime.ActorId, ?> actors =
                (Map<ActorRuntime.ActorId, ?>) actorsField.get(runtime);
        Object cell = actors.get(actor.id());
        assertNotNull(cell);
        Field monitor = cell.getClass().getDeclaredField("lifecycleLock");
        monitor.setAccessible(true);
        return monitor.get(cell);
    }

    @Test
    void initializationReadyCallbackCannotOwnLifecycleMonitor() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> {
                factoryEntered.countDown();
                if (!releaseFactory.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("initialization test deadline");
                }
                return (message, context) -> { };
            });
            try {
                assertTrue(factoryEntered.await(2, TimeUnit.SECONDS));
                Object monitor = lifecycleLock(runtime, actor);
                AtomicBoolean observedUnderMonitor = new AtomicBoolean(true);
                CountDownLatch readyCallbackFinished = new CountDownLatch(1);
                actor.ready().whenCompleteRuntime((value, failure) -> {
                    try {
                        observedUnderMonitor.set(Thread.holdsLock(monitor));
                    } finally {
                        readyCallbackFinished.countDown();
                    }
                });
                releaseFactory.countDown();
                actor.ready().get(3, TimeUnit.SECONDS);
                assertTrue(readyCallbackFinished.await(2, TimeUnit.SECONDS));
                assertFalse(observedUnderMonitor.get(),
                        "actor ready callback must execute without lifecycleLock");
            } finally {
                releaseFactory.countDown();
                actor.stop();
            }
        }
    }

    @Test
    void externalStopPublishesFutureAndExitHookWithoutLifecycleMonitor()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            Object monitor = lifecycleLock(runtime, actor);
            AtomicBoolean callbackHeldMonitor = new AtomicBoolean(true);
            AtomicBoolean hookHeldMonitor = new AtomicBoolean(true);
            AtomicInteger exits = new AtomicInteger();
            CountDownLatch doneCallbackFinished = new CountDownLatch(1);

            actor.done().whenCompleteRuntime((value, error) -> {
                try {
                    callbackHeldMonitor.set(Thread.holdsLock(monitor));
                } finally {
                    doneCallbackFinished.countDown();
                }
            });
            runtime.setActorExitHook(domain -> {
                hookHeldMonitor.set(Thread.holdsLock(monitor));
                exits.incrementAndGet();
            });
            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(doneCallbackFinished.await(2, TimeUnit.SECONDS),
                    "terminal Future listener must run before assertions");
            assertEquals(1, exits.get(), "exit hook must run exactly once");
            assertFalse(hookHeldMonitor.get(), "exit hook under lifecycleLock");
            assertFalse(callbackHeldMonitor.get(), "done callback under lifecycleLock");
        }
    }

    @Test
    void callbackPublicationWaitsForTurnExecutorPhysicalExit() throws Exception {
        CountDownLatch guestFinished = new CountDownLatch(1);
        CountDownLatch guestStopRequested = new CountDownLatch(1);
        CountDownLatch releaseContext = new CountDownLatch(1);
        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            // Actor startup can cross TurnExecutor before any message; gate
            // only the specific turn that actually requested actor shutdown.
            if (guestStopRequested.getCount() == 0) {
                guestFinished.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        releaseContext.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64), gated)) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                context.self().stop();
                guestStopRequested.countDown();
            });
            Object monitor = lifecycleLock(runtime, actor);
            AtomicBoolean callbackUnderMonitor = new AtomicBoolean(true);
            CountDownLatch doneCallbackFinished = new CountDownLatch(1);
            actor.done().whenCompleteRuntime((value, error) -> {
                try {
                    callbackUnderMonitor.set(Thread.holdsLock(monitor));
                } finally {
                    doneCallbackFinished.countDown();
                }
            });
            actor.send("stop");
            try {
                assertTrue(guestFinished.await(2, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(),
                        "actor must remain unpublished inside TurnExecutor");
            } finally {
                releaseContext.countDown();
            }
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(doneCallbackFinished.await(2, TimeUnit.SECONDS));
            assertFalse(callbackUnderMonitor.get());
        }
    }

    @Test
    void actorDoneCannotPublishBeforeOwnedFutureCancellation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicReference<OresFuture<Void>> owned =
                    new java.util.concurrent.atomic.AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Void> never = new OresFuture<>();
                owned.set(context.runtime().ownCurrentActorFuture(never));
                context.self().stop();
            });
            actor.send("stop");
            actor.done().get(3, TimeUnit.SECONDS);
            assertNotNull(owned.get());
            assertTrue(owned.get().isCancelled(),
                    "actor done must be published after owned operation cancellation");
        }
    }

    @Test
    void simultaneousStopRequestsOnlyFinalizeOnce() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            AtomicInteger hookCount = new AtomicInteger();
            runtime.setActorExitHook(domain -> hookCount.incrementAndGet());
            Thread first = new Thread(actor::stop, "actor-shutdown-race-a");
            Thread second = new Thread(actor::stop, "actor-shutdown-race-b");
            first.start();
            second.start();
            first.join(2000);
            second.join(2000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, hookCount.get());
        }
    }
}
