package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ActorFinalizationPostMonitorTest {
    @Test
    void reentrantExitHookCannotDeadlockOrBlockOnTerminalReceipts() throws Exception {
        var actorRef = new AtomicReference<ActorRuntime.ActorRef<Integer>>();
        var hookEntered = new CountDownLatch(1);
        var hookReentered = new CountDownLatch(1);
        var releaseHook = new CountDownLatch(1);
        var hookFailure = new AtomicReference<Throwable>();
        var stopFailure = new AtomicReference<Throwable>();

        try (var runtime = new ActorRuntime()) {
            runtime.setActorExitHook(ignored -> {
                hookEntered.countDown();
                try {
                    var actor = actorRef.get();
                    assertNotNull(actor);

                    // Reentrant lifecycle requests are allowed, but retirement
                    // callbacks may never park on the DONE they help publish.
                    actor.stop();
                    assertThrows(
                            IllegalStateException.class,
                            () -> actor.awaitTermination(1, TimeUnit.SECONDS));
                    assertThrows(
                            IllegalStateException.class,
                            () -> actor.getDone().get(1, TimeUnit.SECONDS));
                    assertThrows(IllegalStateException.class, runtime::close);
                    hookReentered.countDown();

                    if (!releaseHook.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("exit-hook release timed out");
                    }
                } catch (Throwable failure) {
                    hookFailure.compareAndSet(null, failure);
                    hookReentered.countDown();
                }
            });

            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actorRef.set(actor);
            actor.ready().get(5, TimeUnit.SECONDS);

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            }, "actor-finalization-reentrant-stop");
            stopper.start();
            try {
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
                assertTrue(hookReentered.await(5, TimeUnit.SECONDS));
                assertFalse(
                        actor.getDone().isDone(),
                        "DONE must remain unpublished while exit cleanup is blocked");
                assertFalse(
                        actor.awaitTermination(75, TimeUnit.MILLISECONDS),
                        "host termination wait must observe the still-running retirement callback");
            } finally {
                releaseHook.countDown();
                stopper.join(5_000);
            }

            assertFalse(stopper.isAlive(), "reentrant exit-hook stop must not deadlock");
            assertNull(hookFailure.get(), () -> String.valueOf(hookFailure.get()));
            assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.getDone().isDone());
        } finally {
            releaseHook.countDown();
        }
    }

    @Test
    void exitHookMayRetireAnotherActorWithoutCrossActorLockInversion() throws Exception {
        var first = new AtomicReference<ActorRuntime.ActorRef<Integer>>();
        var second = new AtomicReference<ActorRuntime.ActorRef<Integer>>();
        var hooks = new AtomicInteger();

        try (var runtime = new ActorRuntime()) {
            runtime.setActorExitHook(ignored -> {
                int invocation = hooks.incrementAndGet();
                if (invocation == 1) {
                    var other = second.get();
                    assertNotNull(other);
                    other.stop();
                }
            });

            first.set(runtime.<Integer>spawnShared(() -> (message, context) -> { }));
            second.set(runtime.<Integer>spawnShared(() -> (message, context) -> { }));
            first.get().ready().get(5, TimeUnit.SECONDS);
            second.get().ready().get(5, TimeUnit.SECONDS);

            first.get().stop();

            assertTrue(first.get().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.get().awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, hooks.get(), "both retirement hooks must execute exactly once");
        }
    }

    @Test
    void doneCallbacksNeverRunUnderActorLifecycleMonitor() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);

            Object lifecycleLock = lifecycleLock(runtime, actor.id());
            var callbacks = new AtomicInteger();
            var underMonitor = new AtomicInteger();
            var delivered = new CountDownLatch(1);
            actor.getDone().whenCompleteRuntime((ignored, failure) -> {
                if (Thread.holdsLock(lifecycleLock)) underMonitor.incrementAndGet();
                callbacks.incrementAndGet();
                delivered.countDown();
            });

            actor.stop();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals(
                    0,
                    underMonitor.get(),
                    "DONE callbacks must be published after lifecycleLock is released");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void hostStopDoesNotTreatRegistryRemovalAsDone() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);

            @SuppressWarnings("unchecked")
            Map<ActorRuntime.ActorId, Object> actors =
                    (Map<ActorRuntime.ActorId, Object>) actorsField().get(runtime);
            Object cell = actors.remove(actor.id());
            assertNotNull(cell);

            var stopStarted = new CountDownLatch(1);
            var stopReturned = new CountDownLatch(1);
            var stopFailure = new AtomicReference<Throwable>();
            Thread stopper = new Thread(() -> {
                stopStarted.countDown();
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                } finally {
                    stopReturned.countDown();
                }
            }, "actor-stop-registry-retirement-window");
            stopper.start();

            try {
                assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
                assertFalse(
                        stopReturned.await(100, TimeUnit.MILLISECONDS),
                        "missing registry entry is not a DONE receipt");
                assertFalse(actor.getDone().isDone());
            } finally {
                assertNull(actors.put(actor.id(), cell));
                actor.stop();
                stopper.join(5_000);
            }

            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
            assertTrue(actor.getDone().isDone());
        }
    }

    @Test
    void hostStopWaitsWhileReadyFailureObserverIsStillPublishing() throws Exception {
        var initializerEntered = new CountDownLatch(1);
        var releaseInitializer = new CountDownLatch(1);
        var readyObserverEntered = new CountDownLatch(1);
        var releaseReadyObserver = new CountDownLatch(1);
        var stopReturned = new CountDownLatch(1);
        var stopFailure = new AtomicReference<Throwable>();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> {
                initializerEntered.countDown();
                try {
                    if (!releaseInitializer.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("initializer release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("initializer interrupted", interrupted);
                }
                throw new IllegalStateException("deliberate initializer failure");
            });

            actor.ready();
            assertTrue(initializerEntered.await(5, TimeUnit.SECONDS));
            actor.getReady().whenCompleteRuntime((ignored, failure) -> {
                readyObserverEntered.countDown();
                try {
                    releaseReadyObserver.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            Thread stopper = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                } finally {
                    stopReturned.countDown();
                }
            }, "actor-stop-ready-publication-barrier");
            stopper.start();

            try {
                releaseInitializer.countDown();
                assertTrue(readyObserverEntered.await(5, TimeUnit.SECONDS));
                assertFalse(
                        actor.getDone().isDone(),
                        "DONE must follow READY failure publication");
                assertFalse(
                        stopReturned.await(100, TimeUnit.MILLISECONDS),
                        "host stop must not return before DONE is published");
            } finally {
                releaseInitializer.countDown();
                releaseReadyObserver.countDown();
                stopper.join(5_000);
            }

            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
            assertTrue(actor.getDone().isDone());
            assertTrue(actor.failure().isPresent());
        } finally {
            releaseInitializer.countDown();
            releaseReadyObserver.countDown();
        }
    }

    private static Object lifecycleLock(
            ActorRuntime runtime,
            ActorRuntime.ActorId actorId) throws Exception {
        @SuppressWarnings("unchecked")
        Map<ActorRuntime.ActorId, Object> actors =
                (Map<ActorRuntime.ActorId, Object>) actorsField().get(runtime);
        Object cell = actors.get(actorId);
        assertNotNull(cell);
        Field lifecycle = cell.getClass().getDeclaredField("lifecycleLock");
        lifecycle.setAccessible(true);
        return lifecycle.get(cell);
    }

    private static Field actorsField() throws Exception {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        return actors;
    }
}
