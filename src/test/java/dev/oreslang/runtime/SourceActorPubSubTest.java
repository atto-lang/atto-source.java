package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class SourceActorPubSubTest {
    @Test
    void namedGroupLookupDoesNotBootstrapActorMembership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("payments");

            CountDownLatch rejected = new CountDownLatch(1);
            CountDownLatch resolved = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("before".equals(message)) {
                        assertThrows(SecurityException.class,
                                () -> pubsub.group("payments"));
                        rejected.countDown();
                        return;
                    }
                    SourceActorPubSub.Group found = pubsub.group("payments");
                    assertSame(group, found);
                    resolved.countDown();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });

            actor.send("before");
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            group.join(actor);
            actor.send("after");
            assertTrue(resolved.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            group.leave(actor);
            assertFalse(group.contains(actor));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void namedGroupMembershipIsIndependentAcrossGroups() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group a = pubsub.group("a");
            SourceActorPubSub.Group b = pubsub.group("b");

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> pubsub.group("a"));
                    assertSame(b, pubsub.group("b"));
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            });

            a.join(actor);
            b.join(actor);
            a.leave(actor);

            assertFalse(a.contains(actor));
            assertTrue(b.contains(actor));

            actor.send("check");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, b.memberCount());
        }
    }

    @Test
    void cachedActorGroupHandleCannotOutliveMembershipRevocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("revoked");
            group.topic("events", "latest", 1);

            CountDownLatch cached = new CountDownLatch(1);
            CountDownLatch revoked = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<SourceActorPubSub.Group> actorHandle = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("cache".equals(message)) {
                        actorHandle.set(pubsub.group("revoked"));
                        cached.countDown();
                        return;
                    }
                    assertThrows(SecurityException.class,
                            () -> actorHandle.get().topic("events"));
                    assertThrows(SecurityException.class,
                            () -> actorHandle.get().memberCount());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    if ("check".equals(message)) checked.countDown();
                }
            });

            group.join(actor);
            actor.send("cache");
            assertTrue(cached.await(5, TimeUnit.SECONDS));
            group.leave(actor);
            revoked.countDown();

            actor.send("check");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertEquals(0L, revoked.getCount());
            assertNull(failure.get());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void topicPolicyIsBoundedAndDefinitionIsStable() {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("limits");
            SourceActorPubSub.Topic topic =
                    group.topic("updates", "latest", 1);
            assertEquals("updates", topic.name());

            assertThrows(IllegalStateException.class,
                    () -> group.topic("updates", "reliable", 4));
            assertThrows(IllegalArgumentException.class,
                    () -> group.topic("bad", "unbounded", 1));
            assertThrows(IllegalArgumentException.class,
                    () -> group.topic("bad-capacity", "reliable", 0));
        }
    }
    @Test
    void closingDirectoryRevokesBothGroupsWithoutStoppingIndependentActor()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group a = pubsub.group("a");
            SourceActorPubSub.Group b = pubsub.group("b");
            a.topic("events", "latest", 1);
            b.topic("events", "latest", 1);
            CountDownLatch received = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        if ("still-alive".equals(message)) received.countDown();
                    });

            a.join(actor);
            b.join(actor);
            assertTrue(a.contains(actor));
            assertTrue(b.contains(actor));

            pubsub.close();
            pubsub.close(); // Idempotent: never closes a group twice.
            assertFalse(a.contains(actor), "closing A must retire its membership");
            assertFalse(b.contains(actor), "closing B must retire its membership");
            assertThrows(IllegalStateException.class, () -> pubsub.group("a"));
            assertThrows(IllegalStateException.class, () -> a.topic("events"));
            assertThrows(IllegalStateException.class, () -> b.topic("events"));
            assertTrue(actor.isAlive(),
                    "cooperation membership must not imply actor lifecycle ownership");

            actor.send("still-alive");
            assertTrue(received.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void memberActorCannotCloseTheRuntimePubSubDirectory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("owned-by-host");
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, pubsub::close);
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            checked.countDown();
                        }
                    });

            group.join(actor);
            actor.send("attempt-close");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertSame(group, pubsub.group("owned-by-host"),
                    "unauthorized close must not retire the directory");
            assertTrue(group.contains(actor));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
