package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A previously issued runnable cannot consume or clear a newer actor turn. */
@Timeout(20)
final class ActorDispatchIdentityTest {
    private static Object cell(ActorRuntime runtime, ActorRuntime.ActorId id) throws Exception {
        Field field = ActorRuntime.class.getDeclaredField("actors");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(runtime)).get(id);
    }

    private static Object activeTicket(Object actorCell) throws Exception {
        Class<?> type = actorCell.getClass();
        Field monitor = type.getDeclaredField("lifecycleLock");
        Field ticket = type.getDeclaredField("scheduledDispatchToken");
        monitor.setAccessible(true);
        ticket.setAccessible(true);
        synchronized (monitor.get(actorCell)) {
            return ticket.get(actorCell);
        }
    }

    @Test
    void staleDispatchReplayCannotStealOrCancelANewActorTurn() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger executing = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<Object> cellForReplay = new AtomicReference<>();
        AtomicReference<Object> staleTicketForReplay = new AtomicReference<>();

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 32))) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int simultaneous = executing.incrementAndGet();
                peak.accumulateAndGet(simultaneous, Math::max);
                try {
                    deliveries.incrementAndGet();
                    assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                    if (message == 1) {
                        firstEntered.countDown();
                        assertTrue(releaseFirst.await(3, TimeUnit.SECONDS));
                    } else if (message == 2) {
                        // Host regression: even a stale invocation nested
                        // within an active actor must not clear ACTOR_CARRIER
                        // or alter its identity/capability context.
                        Object currentCell = cellForReplay.get();
                        Object staleTicket = staleTicketForReplay.get();
                        assertNotNull(currentCell);
                        assertNotNull(staleTicket);
                        try {
                            Method replay = currentCell.getClass().getDeclaredMethod(
                                    "runBatch", Object.class);
                            replay.setAccessible(true);
                            replay.invoke(currentCell, staleTicket);
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError("nested stale replay must be inert", error);
                        }
                        assertTrue(ActorRuntime.isActorCarrierThread(),
                                "stale dispatch must not clear outer carrier marker");
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertEquals(context.self().id(),
                                ActorRuntime.currentActorId().orElseThrow());

                        // Even a direct hostile nested entrypoint must reject
                        // BEFORE changing the outer actor's execution context.
                        try {
                            Method enterAgain = currentCell.getClass().getDeclaredMethod(
                                    "runBatchEntered");
                            enterAgain.setAccessible(true);
                            InvocationTargetException rejected = assertThrows(
                                    InvocationTargetException.class,
                                    () -> enterAgain.invoke(currentCell));
                            assertInstanceOf(IllegalStateException.class, rejected.getCause());
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError("actor nested-entry test setup", error);
                        }
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertEquals(context.self().id(),
                                ActorRuntime.currentActorId().orElseThrow());
                        secondEntered.countDown();
                        assertTrue(releaseSecond.await(3, TimeUnit.SECONDS));
                    } else if (message == 3) {
                        context.self().stop();
                    }
                } finally {
                    executing.decrementAndGet();
                }
            });
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                Object actorCell = cell(runtime, actor.id());
                cellForReplay.set(actorCell);

                actor.send(1);
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
                Object oldTicket = activeTicket(actorCell);
                assertNotNull(oldTicket);
                staleTicketForReplay.set(oldTicket);

                actor.send(2);
                releaseFirst.countDown();
                assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
                Object newTicket = activeTicket(actorCell);
                assertNotNull(newTicket);
                assertNotSame(oldTicket, newTicket);

                // Re-invoke a previously emitted runnable while the new one
                // owns the actor's physical carrier: no guest callback and
                // no change to the newer scheduling identity is permitted.
                Method runBatch = actorCell.getClass().getDeclaredMethod(
                        "runBatch", Object.class);
                runBatch.setAccessible(true);
                runBatch.invoke(actorCell, oldTicket);
                assertSame(newTicket, activeTicket(actorCell));
                assertEquals(2, deliveries.get());

                actor.send(3);
                releaseSecond.countDown();
                actor.done().get(4, TimeUnit.SECONDS);
                assertEquals(3, deliveries.get());
                assertEquals(1, peak.get());
                assertEquals(0, executing.get());
                assertTrue(actor.failure().isEmpty());
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
                actor.cancel();
            }
        }
    }
}
