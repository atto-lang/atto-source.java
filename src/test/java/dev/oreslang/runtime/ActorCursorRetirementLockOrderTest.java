package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The live actor stop boundary must fence channel admission before physical
 * DONE without executing source select waiters under lifecycleLock.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class ActorCursorRetirementLockOrderTest {

    private static Object lifecycleMonitor(ActorRuntime runtime,
                                           ActorRuntime.ActorId id) throws Exception {
        Field actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        Map<?, ?> cells = (Map<?, ?>) actorsField.get(runtime);
        Object cell = cells.get(id);
        assertNotNull(cell, "actor must still be registered before finalization");
        Field lockField = cell.getClass().getDeclaredField("lifecycleLock");
        lockField.setAccessible(true);
        return lockField.get(cell);
    }

    @Test
    void stopFencesPendingSelectAndDeliversCallbackOutsideLifecycleMonitor()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChannelRuntime.Channel<Integer> input = new ChannelRuntime.Channel<>(1);
            AtomicReference<OresFuture<ChannelRuntime.SelectResult>> pending =
                    new AtomicReference<>();
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch allowCallbackExit = new CountDownLatch(1);
            AtomicBoolean waiterHeldActorMonitor = new AtomicBoolean(true);

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() ->
                    (message, context) -> {
                        ChannelRuntime.FairCursor cursor =
                                runtime.actorLocalSelectCursor("select-site");
                        OresFuture<ChannelRuntime.SelectResult> select =
                                new ChannelRuntime.SelectSet(
                                        List.of(ChannelRuntime.read(input)), cursor)
                                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
                        pending.set(select);
                        armed.countDown();
                    });
            actor.send("arm");
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            Object monitor = lifecycleMonitor(runtime, actor.id());
            OresFuture<ChannelRuntime.SelectResult> select = pending.get();
            assertNotNull(select);
            select.whenCompleteRuntime((value, failure) -> {
                waiterHeldActorMonitor.set(Thread.holdsLock(monitor));
                callbackEntered.countDown();
                try {
                    if (!allowCallbackExit.await(8, TimeUnit.SECONDS)) {
                        throw new AssertionError("callback gate not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("callback interrupted", interrupted);
                }
            });

            Thread stopping = new Thread(actor::stop, "actor-stop-cursor-retirement");
            try {
                stopping.start();
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS),
                        "pending select's cancellation waiter did not execute");
                assertFalse(waiterHeldActorMonitor.get(),
                        "actor lifecycle monitor must be released before callback delivery");
                assertFalse(actor.done().isDone(),
                        "physical actor DONE must wait for the retirement waiter barrier");
                assertEquals(1, input.capacity());
            } finally {
                allowCallbackExit.countDown();
                stopping.join(5_000);
            }
            assertFalse(stopping.isAlive(), "stop must not deadlock the waiter");
            assertTrue(select.isCancelled());
            actor.done().get(5, TimeUnit.SECONDS);
            // The retired selection cannot consume a producer's later message.
            assertTrue(input.tryWrite(73));
            assertEquals(73, input.tryRead().orElseThrow());
        }
    }

    @Test
    void actorDoneWaitsForOrdinaryOwnedFutureCancellationCallbacks()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<OresFuture<Integer>> owned = new AtomicReference<>();
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch waiterEntered = new CountDownLatch(1);
            CountDownLatch releaseWaiter = new CountDownLatch(1);
            AtomicBoolean waiterHeldMonitor = new AtomicBoolean(true);

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() ->
                    (message, context) -> {
                        OresFuture<Integer> future = new OresFuture<>();
                        runtime.ownCurrentActorFuture(future);
                        owned.set(future);
                        armed.countDown();
                    });
            actor.send("arm");
            assertTrue(armed.await(5, TimeUnit.SECONDS));
            Object monitor = lifecycleMonitor(runtime, actor.id());
            OresFuture<Integer> future = owned.get();
            assertNotNull(future);
            future.whenCompleteRuntime((value, failure) -> {
                waiterHeldMonitor.set(Thread.holdsLock(monitor));
                waiterEntered.countDown();
                try {
                    if (!releaseWaiter.await(8, TimeUnit.SECONDS)) {
                        throw new AssertionError("owned waiter release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("owned waiter interrupted", interrupted);
                }
            });

            Thread stopping = new Thread(actor::stop, "actor-stop-owned-future");
            try {
                stopping.start();
                assertTrue(waiterEntered.await(5, TimeUnit.SECONDS),
                        "ordinary actor-owned Future cancellation must notify waiters");
                assertFalse(waiterHeldMonitor.get(),
                        "owned Future waiter may not run under lifecycle monitor");
                assertFalse(actor.done().isDone(),
                        "physical actor DONE must wait until all owned waiters settle");
            } finally {
                releaseWaiter.countDown();
                stopping.join(5_000);
            }
            assertFalse(stopping.isAlive());
            assertTrue(future.isCancelled());
            actor.done().get(5, TimeUnit.SECONDS);
        }
    }
}
