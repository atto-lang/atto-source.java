package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorLifecycleObserverIsolationTest {

    @Test
    void readyDoneOutputAndExitObserversNeverRunUnderActorLifecycleMonitor()
            throws Exception {
        AtomicInteger callbacksUnderLifecycleLock = new AtomicInteger();
        CountDownLatch readyObserved = new CountDownLatch(1);
        CountDownLatch doneObserved = new CountDownLatch(1);
        CountDownLatch outputClosedObserved = new CountDownLatch(1);
        CountDownLatch exitHookObserved = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            Object cell = actorCell(runtime, actor.id());
            Object lifecycleLock = lifecycleLock(cell);

            OresFuture<Void> rawReady = privateFuture(actor, "readyFuture");
            rawReady.whenCompleteRuntime((value, failure) -> {
                if (Thread.holdsLock(lifecycleLock))
                    callbacksUnderLifecycleLock.incrementAndGet();
                readyObserved.countDown();
            });

            actor.done().whenCompleteRuntime((value, failure) -> {
                if (Thread.holdsLock(lifecycleLock))
                    callbacksUnderLifecycleLock.incrementAndGet();
                doneObserved.countDown();
            });

            actor.outputs().next().whenCompleteRuntime((step, failure) -> {
                if (Thread.holdsLock(lifecycleLock))
                    callbacksUnderLifecycleLock.incrementAndGet();
                outputClosedObserved.countDown();
            });

            runtime.setActorExitHook(domain -> {
                if (Thread.holdsLock(lifecycleLock))
                    callbacksUnderLifecycleLock.incrementAndGet();
                exitHookObserved.countDown();
            });

            actor.getReady().get(5, TimeUnit.SECONDS);
            assertTrue(readyObserved.await(3, TimeUnit.SECONDS));

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(exitHookObserved.await(3, TimeUnit.SECONDS));
            assertTrue(outputClosedObserved.await(3, TimeUnit.SECONDS));
            assertTrue(doneObserved.await(3, TimeUnit.SECONDS));

            assertEquals(0, callbacksUnderLifecycleLock.get(),
                    "observer/collector callbacks must not inherit actor lifecycle ownership");
        }
    }

    @Test
    void blockedChildDoneObserverCannotHoldParentActorTurnOrStructuredFinalization()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch childDoneObserverEntered = new CountDownLatch(1);
        CountDownLatch releaseChildObserver = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<String>> child =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            child.set(context.runtime().spawnPrivate(
                                    () -> (childMessage, childContext) -> { }));
                            childCreated.countDown();
                            return;
                        }
                        if ("stop".equals(message)) {
                            context.self().stop();
                        }
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            parent.drain().get(3, TimeUnit.SECONDS);

            child.get().done().whenCompleteRuntime((value, failure) -> {
                childDoneObserverEntered.countDown();
                try {
                    releaseChildObserver.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            parent.send("stop");
            assertTrue(childDoneObserverEntered.await(3, TimeUnit.SECONDS),
                    "child observer should eventually see terminal publication");

            // The child observer is still blocked. Parent teardown must have
            // progressed independently because the child's publication was
            // dispatched on the child lane rather than inside the parent turn.
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS),
                    "blocked child observer must not pin parent structured teardown");
            assertTrue(child.get().awaitTermination(2, TimeUnit.SECONDS),
                    "terminal state is published before arbitrary DONE callbacks return");
        } finally {
            releaseChildObserver.countDown();
        }
    }

    @Test
    void failingExitHookIsTelemetryAndCannotStrandActorDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(domain -> {
                throw new IllegalStateException("collector hook failed");
            });
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            actor.getDone().get(5, TimeUnit.SECONDS);
            assertFalse(actor.isAlive());
            assertEquals(1L, runtime.runtimeStats().lifecyclePublicationFailures(),
                    "runtime cleanup anomaly must be visible without becoming actor liveness");
            assertEquals(1L, runtime.runtimeStats().normalTerminations());
        }
    }

    @Test
    void blockedOutputObserverCannotDelayParentOrDoneTerminalState()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch outputObserverEntered = new CountDownLatch(1);
        CountDownLatch releaseOutputObserver = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<String>> child =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            child.set(context.runtime().spawnPrivate(
                                    () -> (childMessage, childContext) -> { }));
                            childCreated.countDown();
                            return;
                        }
                        if ("stop".equals(message)) context.self().stop();
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            parent.drain().get(3, TimeUnit.SECONDS);

            child.get().outputs().next().whenCompleteRuntime((step, failure) -> {
                outputObserverEntered.countDown();
                try {
                    releaseOutputObserver.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            parent.send("stop");
            assertTrue(outputObserverEntered.await(3, TimeUnit.SECONDS),
                    "child output close should eventually publish EOF/error");

            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS),
                    "output observer cannot delay structured parent progress");
            assertTrue(child.get().awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(child.get().done().isDone(),
                    "DONE terminal state must precede deferred output observers");
        } finally {
            releaseOutputObserver.countDown();
        }
    }

    @Test
    void saturatedChildDispatcherNeverFallsBackToParentActorObserverExecution()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch privateCarrierEntered = new CountDownLatch(1);
        CountDownLatch releasePrivateCarrier = new CountDownLatch(1);
        CountDownLatch childObserverRan = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<String>> child =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean observerSawActorExecution =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> observerThread =
                new java.util.concurrent.atomic.AtomicReference<>();

        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16));
        try {
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            child.set(context.runtime().spawnPrivate(
                                    () -> (childMessage, childContext) -> { }));
                            childCreated.countDown();
                            return;
                        }
                        if ("stop".equals(message)) context.self().stop();
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            parent.drain().get(3, TimeUnit.SECONDS);

            ExecutorService privateDispatcher = dispatcher(runtime, "privateDispatcher");
            privateDispatcher.execute(() -> {
                privateCarrierEntered.countDown();
                try {
                    releasePrivateCarrier.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(privateCarrierEntered.await(3, TimeUnit.SECONDS));

            int queued = 0;
            for (;;) {
                try {
                    privateDispatcher.execute(() -> { });
                    queued++;
                } catch (RejectedExecutionException full) {
                    break;
                }
            }
            assertTrue(queued > 0, "fixture must actually saturate the child dispatcher");

            child.get().done().whenCompleteRuntime((value, failure) -> {
                observerSawActorExecution.set(ActorRuntime.inActorExecution());
                observerThread.set(Thread.currentThread().getName());
                childObserverRan.countDown();
            });

            parent.send("stop");

            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS),
                    "saturated child dispatcher must not hold structured parent progress");
            assertTrue(child.get().awaitTermination(3, TimeUnit.SECONDS),
                    "child authoritative finalization must not require its saturated dispatcher");
            assertTrue(childObserverRan.await(3, TimeUnit.SECONDS),
                    "terminal observer must execute on the lifecycle observer domain");
            assertFalse(observerSawActorExecution.get(),
                    "child terminal observer must never inherit the parent actor execution context");
            assertTrue(observerThread.get().startsWith("ores-lifecycle-observer-"),
                    "observer must run on the dedicated bounded lifecycle domain");
        } finally {
            releasePrivateCarrier.countDown();
            runtime.close();
        }
    }

    @Test
    void blockedLifecycleObserverRetainsBoundedActorCapacityUntilItRetires()
            throws Exception {
        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 1))) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });

            actor.done().whenCompleteRuntime((value, failure) -> {
                observerEntered.countDown();
                try {
                    releaseObserver.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            actor.stop();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "logical actor finalization precedes arbitrary observer completion");
            assertTrue(observerEntered.await(3, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class,
                    () -> runtime.<String>spawnShared(
                            () -> (message, context) -> { }),
                    "observer backlog must consume bounded runtime capacity rather than spawn threads without bound");

            releaseObserver.countDown();
            actor.getDone().get(3, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> replacement = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (replacement == null && System.nanoTime() < deadline) {
                try {
                    replacement = runtime.<String>spawnShared(
                            () -> (message, context) -> { });
                } catch (IllegalStateException stillCharged) {
                    Thread.sleep(1);
                }
            }
            assertNotNull(replacement,
                    "actor capacity must be released after terminal observers retire");
            replacement.stop();
            assertTrue(replacement.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            releaseObserver.countDown();
        }
    }

    @Test
    void lifecycleObserverCannotSynchronouslyCloseItsOwnRuntime()
            throws Exception {
        CountDownLatch callbackRan = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> observed =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            actor.done().whenCompleteRuntime((value, failure) -> {
                try {
                    runtime.close();
                } catch (Throwable expected) {
                    observed.set(expected);
                } finally {
                    callbackRan.countDown();
                }
            });

            actor.stop();
            assertTrue(callbackRan.await(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, observed.get(),
                    "observer callback must fail fast instead of self-joining its executor");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void doneObserverMayReenterSupervisorApisWithoutLifecycleDeadlock()
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });

            actor.done().whenCompleteRuntime((value, failure) -> {
                try {
                    // These acquire runtime/supervisor state and would create a
                    // lock-order cycle if DONE were published under lifecycleLock.
                    runtime.runtimeStats();
                    runtime.pollTerminationEvent();
                    runtime.snapshot(actor);
                } catch (Throwable unexpected) {
                    callbackFailure.set(unexpected);
                } finally {
                    callbackEntered.countDown();
                }
            });

            actor.stop();
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            assertNull(callbackFailure.get(),
                    "DONE callback must safely re-enter supervisor observability");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private static OresFuture<Void> privateFuture(
            ActorRuntime.ActorRef<?> actor,
            String fieldName) throws ReflectiveOperationException {
        Field field = actor.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (OresFuture<Void>) field.get(actor);
    }

    private static Object actorCell(
            ActorRuntime runtime,
            ActorRuntime.ActorId id) throws ReflectiveOperationException {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        Object cell = ((Map<?, ?>) actors.get(runtime)).get(id);
        assertNotNull(cell);
        return cell;
    }

    private static ExecutorService dispatcher(
            ActorRuntime runtime,
            String fieldName) throws ReflectiveOperationException {
        Field field = ActorRuntime.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ExecutorService) field.get(runtime);
    }

    private static Object lifecycleLock(Object cell)
            throws ReflectiveOperationException {
        Field field = cell.getClass().getDeclaredField("lifecycleLock");
        field.setAccessible(true);
        return field.get(cell);
    }
}
