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
 * Source-only, not yet JUnit-executed: exact entry retirement must remain safe
 * when a completion callback re-enters the same scoped human-readable name.
 */
@Timeout(35)
final class ProcessGroupRegistryReentrantNameReuseTest {
    @Test
    void pendingReadCallbackCanRecreateSameNameWhileOldGroupIsClosing()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var pg = new ProcessGroupRegistry(runtime, 1)) {
            var current = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            var replacement = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            pg.join("local", "reuse", current);
            var oldGroup = onlyGroup(pg);
            oldGroup.events().defineTopic(
                    "updates", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            AtomicReference<ActorRuntime.ActorGroup> latest = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean retainedRegistryLock = new AtomicBoolean();
            AtomicBoolean retainedMembershipLock = new AtomicBoolean();
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch callback = new CountDownLatch(1);
            Object registryLock = field(pg.getClass(), "lock").get(pg);
            Object membershipLock = field(runtime.getClass(), "groupMembershipLock").get(runtime);

            // Register an outstanding subscription read on a real SHARED actor,
            // but invoke same-name replacement from host-owned completion.
            var bus = oldGroup.events();
            var subscriber = runtime.<String>spawnSharedTrusted(factory -> (message, context) -> {
                try {
                    pg.joinCurrent("local", "reuse");
                    bus.<String>subscribe("updates").readAsync()
                            .whenCompleteRuntime((ignored, terminal) -> {
                                try {
                                    retainedRegistryLock.set(Thread.holdsLock(registryLock));
                                    retainedMembershipLock.set(Thread.holdsLock(membershipLock));
                                    // The old entry has already been unlinked, but
                                    // the old group is still retiring.
                                    assertEquals(0, pg.groupCount());
                                    pg.join("local", "reuse", replacement);
                                    latest.set(onlyGroup(pg));
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                } finally {
                                    callback.countDown();
                                }
                            });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    subscribed.countDown();
                }
            });
            subscriber.send("watch");
            assertTrue(subscribed.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            pg.delete("local", "reuse");
            assertTrue(callback.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(retainedRegistryLock.get());
            assertFalse(retainedMembershipLock.get());
            assertNotNull(latest.get());
            assertNotSame(oldGroup, latest.get());
            assertTrue(oldGroup.closed());
            assertFalse(latest.get().closed(),
                    "retirement of old exact entry cannot close replacement");
            assertTrue(pg.contains("local", "reuse", replacement));
            assertFalse(pg.contains("local", "reuse", current));
            assertTrue(current.isAlive());
            assertTrue(replacement.isAlive());
            assertTrue(subscriber.isAlive());

            pg.delete("local", "reuse");
            current.stop();
            replacement.stop();
            subscriber.stop();
            assertTrue(current.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(replacement.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleHandleAfterDeleteAndRecreateCannotRetireReplacement()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var pg = new ProcessGroupRegistry(runtime, 1)) {
            AtomicReference<ProcessGroupRegistry.Membership<String>> old =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch left = new CountDownLatch(1);
            var oldOwner = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            if ("join".equals(message)) {
                                old.set(pg.<String>joinCurrent("local", "stable"));
                            } else if ("leave-stale".equals(message)) {
                                assertDoesNotThrow(old.get()::leave);
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if ("join".equals(message)) joined.countDown();
                            if ("leave-stale".equals(message)) left.countDown();
                        }
                    });
            var replacement = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            oldOwner.send("join");
            assertTrue(joined.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            var first = onlyGroup(pg);
            pg.delete("local", "stable");
            pg.join("local", "stable", replacement);
            var newGroup = onlyGroup(pg);
            assertNotSame(first, newGroup);
            oldOwner.send("leave-stale");
            assertTrue(left.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(newGroup.closed());
            assertEquals(1, pg.memberCount("local", "stable"));
            assertTrue(pg.contains("local", "stable", replacement));
            assertFalse(pg.contains("local", "stable", oldOwner));

            pg.delete("local", "stable");
            oldOwner.stop();
            replacement.stop();
            assertTrue(oldOwner.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(replacement.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDeleteAndJoinNeverResurrectDetachedEntry() throws Exception {
        try (var runtime = new ActorRuntime();
             var pg = new ProcessGroupRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            var joining = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                for (int iteration = 0; iteration < 24; iteration++) {
                    pg.join("local", "racing", owner);
                    var oldGroup = onlyGroup(pg);
                    var start = new CountDownLatch(1);
                    var delete = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        pg.delete("local", "racing");
                        return null;
                    });
                    var join = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        pg.join("local", "racing", joining);
                        return null;
                    });
                    start.countDown();
                    delete.get(20, TimeUnit.SECONDS);
                    join.get(20, TimeUnit.SECONDS);
                    // The join may precede the delete (and then be revoked),
                    // or it may follow and join the replacement. Re-establish
                    // a live name to verify the detached group cannot win.
                    pg.join("local", "racing", joining);
                    var newGroup = onlyGroup(pg);
                    assertTrue(oldGroup.closed());
                    assertNotSame(oldGroup, newGroup);
                    assertFalse(newGroup.closed());
                    assertTrue(pg.contains("local", "racing", joining));
                    assertFalse(pg.contains("local", "racing", owner));
                    pg.delete("local", "racing");
                    assertEquals(0, pg.groupCount());
                }
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
                owner.stop();
                joining.stop();
                assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
                assertTrue(joining.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void registryAndMembershipTokensCannotCrossActorMessageOrFrozenDataBoundary()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var pg = new ProcessGroupRegistry(runtime, 1)) {
            var ownerToken = new AtomicReference<ProcessGroupRegistry.Membership<String>>();
            var failure = new AtomicReference<Throwable>();
            var issued = new CountDownLatch(1);
            var owner = runtime.<String>spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            ownerToken.set(pg.<String>joinCurrent("local", "private-capability"));
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            issued.countDown();
                        }
                    });
            var recipient = runtime.<Object>spawnSharedTrusted(
                    factory -> (message, context) -> { });
            owner.send("join");
            assertTrue(issued.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotNull(ownerToken.get());
            assertThrows(SecurityException.class, () -> recipient.send(pg));
            assertThrows(SecurityException.class, () -> recipient.send(ownerToken.get()));
            assertThrows(SecurityException.class,
                    () -> recipient.send(java.util.List.of(pg, ownerToken.get())));
            assertThrows(SecurityException.class,
                    () -> runtime.freezeActorGroupEventPayload(pg));
            assertThrows(SecurityException.class,
                    () -> runtime.freezeActorGroupEventPayload(
                            java.util.Map.of("authority", ownerToken.get())));
            assertEquals(1, pg.memberCount("local", "private-capability"),
                    "rejected transport cannot mutate membership authority");
            pg.delete("local", "private-capability");
            owner.stop();
            recipient.stop();
            assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(recipient.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static ActorRuntime.ActorGroup onlyGroup(ProcessGroupRegistry pg)
            throws Exception {
        Object monitor = field(pg.getClass(), "lock").get(pg);
        @SuppressWarnings("unchecked")
        Map<?, ?> groups = (Map<?, ?>) field(pg.getClass(), "groups").get(pg);
        synchronized (monitor) {
            assertEquals(1, groups.size(), "a single live name is expected");
            Object entry = groups.values().iterator().next();
            return (ActorRuntime.ActorGroup) field(entry.getClass(), "group").get(entry);
        }
    }

    private static Field field(Class<?> c, String name) throws Exception {
        var f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
