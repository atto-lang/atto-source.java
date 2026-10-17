package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

    private static Object lifecycleLock(Object cell)
            throws ReflectiveOperationException {
        Field field = cell.getClass().getDeclaredField("lifecycleLock");
        field.setAccessible(true);
        return field.get(cell);
    }
}
