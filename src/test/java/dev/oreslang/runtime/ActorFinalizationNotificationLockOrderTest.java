package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finalization effects must be delivered after actor lifecycle monitors have
 * been released, and child DONE callbacks must precede parent physical DONE.
 */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
final class ActorFinalizationNotificationLockOrderTest {
    private static Object monitor(ActorRuntime runtime, ActorRuntime.ActorId id)
            throws Exception {
        Field actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(id);
        assertNotNull(cell, "actor must remain registered before finalization");
        Field lockField = cell.getClass().getDeclaredField("lifecycleLock");
        lockField.setAccessible(true);
        return lockField.get(cell);
    }

    @Test
    void blockedExitHookAndDoneWaiterNeverHoldActorMonitor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Object> monitorRef = new AtomicReference<>();
            CountDownLatch hookEntered = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            CountDownLatch waiterEntered = new CountDownLatch(1);
            CountDownLatch releaseWaiter = new CountDownLatch(1);
            AtomicBoolean hookHeldMonitor = new AtomicBoolean(true);
            AtomicBoolean waiterHeldMonitor = new AtomicBoolean(true);
            AtomicInteger hookCalls = new AtomicInteger();
            AtomicInteger doneCalls = new AtomicInteger();

            runtime.setActorExitHook(domain -> {
                hookCalls.incrementAndGet();
                hookHeldMonitor.set(Thread.holdsLock(monitorRef.get()));
                hookEntered.countDown();
                try {
                    if (!releaseHook.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("host exit hook never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() ->
                    (message, context) -> { });
            Object actorMonitor = monitor(runtime, actor.id());
            monitorRef.set(actorMonitor);
            actor.done().whenCompleteRuntime((ignored, failure) -> {
                doneCalls.incrementAndGet();
                waiterHeldMonitor.set(Thread.holdsLock(actorMonitor));
                waiterEntered.countDown();
                try {
                    if (!releaseWaiter.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("DONE waiter never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            Thread stop = new Thread(actor::stop, "actor-finalization-stop");
            try {
                stop.start();
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
                assertFalse(hookHeldMonitor.get(), "host exit hook held actor lifecycle monitor");
                synchronized (actorMonitor) {
                    assertFalse(actor.done().isDone(), "DONE published before host exit finished");
                }
                releaseHook.countDown();
                assertTrue(waiterEntered.await(5, TimeUnit.SECONDS));
                assertFalse(waiterHeldMonitor.get(), "DONE waiter held actor lifecycle monitor");
                assertEquals(1, hookCalls.get());
            } finally {
                releaseHook.countDown();
                releaseWaiter.countDown();
                stop.join(5_000);
            }
            assertFalse(stop.isAlive(), "host STOP could not finish finalization");
            actor.done().get(5, TimeUnit.SECONDS);
            assertEquals(1, hookCalls.get(), "exit hooks must never replay");
            assertEquals(1, doneCalls.get(), "DONE waiters may not be duplicated");
        }
    }

    @Test
    void parentDoneCannotOvertakeBlockedChildDoneCallback() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childSpawned = new CountDownLatch(1);
            CountDownLatch childWaiterEntered = new CountDownLatch(1);
            CountDownLatch releaseChildWaiter = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            AtomicBoolean childWaiterHeldParentMonitor = new AtomicBoolean(true);
            AtomicBoolean childWaiterHeldChildMonitor = new AtomicBoolean(true);
            AtomicInteger childDoneCallbacks = new AtomicInteger();

            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() ->
                    (message, context) -> {
                        ActorRuntime.ActorHandle<String> handle =
                                runtime.spawnChildShared(factory -> (mail, childContext) -> { });
                        childRef.set(handle.ref());
                        childSpawned.countDown();
                    });
            parent.send("spawn");
            assertTrue(childSpawned.await(5, TimeUnit.SECONDS));
            ActorRuntime.ActorRef<String> child = childRef.get();
            assertNotNull(child);
            Object parentMonitor = monitor(runtime, parent.id());
            Object childMonitor = monitor(runtime, child.id());

            child.done().whenCompleteRuntime((value, failure) -> {
                childDoneCallbacks.incrementAndGet();
                childWaiterHeldParentMonitor.set(Thread.holdsLock(parentMonitor));
                childWaiterHeldChildMonitor.set(Thread.holdsLock(childMonitor));
                childWaiterEntered.countDown();
                try {
                    if (!releaseChildWaiter.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("blocked child DONE waiter not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });

            Thread stopParent = new Thread(parent::stop, "parent-stop-child-done");
            try {
                stopParent.start();
                assertTrue(childWaiterEntered.await(5, TimeUnit.SECONDS),
                        "parent STOP should cancel child and enter child's DONE waiter");
                assertFalse(childWaiterHeldChildMonitor.get());
                assertFalse(childWaiterHeldParentMonitor.get());
                assertFalse(parent.done().isDone(),
                        "parent physical DONE cannot precede child DONE waiter completion");
                synchronized (parentMonitor) {
                    synchronized (childMonitor) {
                        assertFalse(parent.done().isDone());
                    }
                }
            } finally {
                releaseChildWaiter.countDown();
                stopParent.join(5_000);
            }
            assertFalse(stopParent.isAlive(), "parent and child locks deadlocked");
            assertEquals(1, childDoneCallbacks.get());
            assertTrue(child.done().isDone());
            parent.done().get(5, TimeUnit.SECONDS);
        }
    }
}
