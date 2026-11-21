package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry-lock-only regression. ActorGroup's deeper groupMembershipLock /
 * topic.deliveryLock callback safety is covered separately by #1230.
 * Authored assertions are NOT proof of execution until an exact-head run.
 */
@Timeout(20)
final class ProcessGroupRegistryRetirementTest {
    @Test
    void managerDeleteCompletesPendingReadAfterRegistryMonitorIsReleased() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            Object monitor = privateField(ProcessGroupRegistry.class, "lock").get(pg);
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch callback = new CountDownLatch(1);
            AtomicBoolean underMonitor = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus> bus = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            ActorEventBus.Subscription<Integer> sub =
                                    bus.get().subscribe("retirement");
                            sub.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                                try {
                                    underMonitor.set(Thread.holdsLock(monitor));
                                    assertEquals(0, pg.groupCount(),
                                            "callback may reenter registry observation");
                                } catch (Throwable callbackFailure) {
                                    failure.compareAndSet(null, callbackFailure);
                                } finally {
                                    callback.countDown();
                                }
                            });
                        } catch (Throwable setupFailure) {
                            failure.compareAndSet(null, setupFailure);
                        } finally {
                            registered.countDown();
                        }
                    });

            pg.join("local", "workers", actor);
            ActorRuntime.ActorGroup group = onlyGroup(pg);
            group.events().defineTopic(
                    "retirement", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            bus.set(group.events());
            actor.send(1);
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            pg.delete("local", "workers");
            assertTrue(callback.await(5, TimeUnit.SECONDS),
                    "pending read must be settled by group retirement");
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(underMonitor.get(),
                    "event-bus completion must not run under pg registry monitor");
            assertEquals(0, pg.groupCount());
            assertTrue(actor.isAlive(),
                    "pg membership never owns the actor lifecycle");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void lastMemberLeaveDoesNotInvokeCallbackUnderRegistryMonitor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            Object monitor = privateField(ProcessGroupRegistry.class, "lock").get(pg);
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch callback = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean underMonitor = new AtomicBoolean();
            AtomicReference<ActorEventBus> bus = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            ActorEventBus.Subscription<Integer> sub =
                                    bus.get().subscribe("leave");
                            sub.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                                underMonitor.set(Thread.holdsLock(monitor));
                                callback.countDown();
                            });
                            pg.leaveCurrent("local", "solo");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            completed.countDown();
                        }
                    });

            pg.join("local", "solo", actor);
            ActorRuntime.ActorGroup group = onlyGroup(pg);
            group.events().defineTopic(
                    "leave", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            bus.set(group.events());
            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(callback.await(5, TimeUnit.SECONDS),
                    "leave must settle pending read");
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(underMonitor.get(),
                    "leave must release registry monitor before callback-capable teardown");
            assertEquals(0, pg.groupCount());
            assertTrue(actor.isAlive());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deletedGroupCannotRetireSameNameReplacement() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 1)) {
            ActorRuntime.ActorRef<Integer> first =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            ActorRuntime.ActorRef<Integer> second =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            pg.join("local", "reused", first);
            ActorRuntime.ActorGroup oldGroup = onlyGroup(pg);
            pg.delete("local", "reused");
            assertTrue(oldGroup.closed());
            assertEquals(0, pg.groupCount());
            pg.join("local", "reused", second);
            ActorRuntime.ActorGroup newGroup = onlyGroup(pg);
            assertNotSame(oldGroup, newGroup);
            assertFalse(newGroup.closed());
            assertTrue(pg.contains("local", "reused", second));
            assertFalse(pg.contains("local", "reused", first));
            assertEquals(1, pg.memberCount("local", "reused"));
            assertTrue(first.isAlive(), "group retirement cannot stop old members");
            assertTrue(second.isAlive());
            first.stop();
            second.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ActorRuntime.ActorGroup onlyGroup(ProcessGroupRegistry registry)
            throws Exception {
        Field groupsField = privateField(ProcessGroupRegistry.class, "groups");
        Map<?, ?> groups = (Map<?, ?>) groupsField.get(registry);
        Object monitor = privateField(ProcessGroupRegistry.class, "lock").get(registry);
        synchronized (monitor) {
            assertEquals(1, groups.size());
            Object entry = groups.values().iterator().next();
            return (ActorRuntime.ActorGroup) privateField(
                    entry.getClass(), "group").get(entry);
        }
    }

    private static Field privateField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
