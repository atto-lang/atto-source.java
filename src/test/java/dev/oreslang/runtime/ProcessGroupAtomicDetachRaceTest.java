package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-driven pg rejoin overlaps the old membership's physical Channel close.
 * The callback latch forces the critical interleaving instead of hoping a
 * randomized scheduler happens to reproduce the source-level race.
 *
 * Tests require exact-head JUnit execution; source presence is not a pass.
 */
@Timeout(20)
final class ProcessGroupAtomicDetachRaceTest {

    @Test
    void rejoinAfterLogicalDetachSurvivesBlockedOldSubscriptionCallback()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 3)) {
            ActorRuntime.ActorRef<Integer> keeper =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            pg.join("local", "room", keeper);
            ActorRuntime.ActorGroup group = onlyGroup(pg);
            ActorEventBus bus = group.events();
            bus.defineTopic("updates", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            Object registryLock = field(ProcessGroupRegistry.class, "lock").get(pg);
            Object groupLock = field(ActorRuntime.class, "groupMembershipLock").get(runtime);
            CountDownLatch callbackStarted = new CountDownLatch(1);
            CountDownLatch allowCallbackReturn = new CountDownLatch(1);
            CountDownLatch firstTurnDone = new CountDownLatch(1);
            CountDownLatch secondTurnDone = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ProcessGroupRegistry.Membership<Integer>> stale =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            if (message == 1) {
                                stale.set(pg.joinCurrent("local", "room"));
                                ActorEventBus.Subscription<Integer> sub =
                                        bus.subscribe("updates");
                                sub.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                                    try {
                                        assertFalse(Thread.holdsLock(registryLock),
                                                "Future callback under pg monitor");
                                        assertFalse(Thread.holdsLock(groupLock),
                                                "Future callback under ActorGroup membership lock");
                                        callbackStarted.countDown();
                                        assertTrue(allowCallbackReturn.await(5, TimeUnit.SECONDS),
                                                "test host must release the old callback");
                                    } catch (Throwable problem) {
                                        failure.compareAndSet(null, problem);
                                        callbackStarted.countDown();
                                    }
                                });
                                pg.leaveCurrent("local", "room");
                            } else {
                                stale.get().leave();
                                assertTrue(pg.contains("local", "room", context.self()),
                                        "stale epoch cannot remove post-detach host rejoin");
                                pg.leaveCurrent("local", "room");
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 1) firstTurnDone.countDown();
                            else secondTurnDone.countDown();
                        }
                    });

            actor.send(1);
            ExecutorService host = Executors.newSingleThreadExecutor();
            try {
                assertTrue(callbackStarted.await(5, TimeUnit.SECONDS),
                        "physical close callback must start after logical detach");
                Future<?> rejoin = host.submit(() -> pg.join("local", "room", actor));
                rejoin.get(4, TimeUnit.SECONDS);
                assertTrue(pg.contains("local", "room", actor),
                        "concurrent host join must finish while old callback is blocked");
                assertEquals(2, pg.memberCount("local", "room"));
            } finally {
                allowCallbackReturn.countDown();
                host.shutdownNow();
            }
            assertTrue(firstTurnDone.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            actor.send(2);
            assertTrue(secondTurnDone.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, pg.memberCount("local", "room"));
            assertTrue(keeper.isAlive());
            assertTrue(actor.isAlive(), "pg leave cannot own ordinary actor lifecycle");
            keeper.stop();
            actor.stop();
            assertTrue(keeper.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void explicitSubscriptionCloseSettlesPendingReadOutsideDeliveryLock()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("direct-close", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch callback = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            join.join();
                            ActorEventBus.Subscription<Integer> sub =
                                    bus.subscribe("direct-close");
                            Object topic = field(sub.getClass(), "topic").get(sub);
                            Object deliveryLock = field(topic.getClass(), "deliveryLock").get(topic);
                            sub.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                                try {
                                    assertFalse(Thread.holdsLock(deliveryLock),
                                            "explicit subscription close callback under topic lock");
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                } finally {
                                    callback.countDown();
                                }
                            });
                            sub.close();
                            assertTrue(sub.closed());
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ActorRuntime.ActorGroup onlyGroup(ProcessGroupRegistry pg)
            throws Exception {
        Object lock = field(ProcessGroupRegistry.class, "lock").get(pg);
        Map<?, ?> groups = (Map<?, ?>) field(ProcessGroupRegistry.class, "groups").get(pg);
        synchronized (lock) {
            assertEquals(1, groups.size());
            Object entry = groups.values().iterator().next();
            return (ActorRuntime.ActorGroup) field(entry.getClass(), "group").get(entry);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
