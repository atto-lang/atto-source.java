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
    void portableTopicNamesAreExactBoundedAsciiIdentifiers() {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("scope");
            SourceActorPubSub.Topic topic = group.topic("events", "latest", 1);
            assertEquals("events", topic.name());
            for (String invalid : java.util.List.of(
                    "", " events", "events ", "#ready", "1events",
                    "a:b", "a b", "A".repeat(129))) {
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(invalid, "latest", 1), invalid);
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(invalid), invalid);
            }
            assertEquals("events", group.topic("events").name(),
                    "invalid aliases must not affect an existing topic");
            assertEquals("A", group.topic("A", "latest", 1).name());
            assertEquals("E".repeat(128),
                    group.topic("E".repeat(128), "latest", 1).name());
            assertEquals("orders.created/v1_test-2",
                    group.topic("orders.created/v1_test-2", "latest", 1).name());
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
    void pubSubRejectsClosuresAndBoundMethodsBeforeEnqueueIncludingNestedValues() {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Topic topic =
                    pubsub.group("data-only").topic("values", "reliable", 2);
            java.util.List<String> captured = new java.util.ArrayList<>();
            Runnable closure = () -> captured.add("must-not-execute");
            Runnable boundMethod = captured::clear;

            assertThrows(IllegalArgumentException.class,
                    () -> topic.publish(closure));
            assertThrows(IllegalArgumentException.class,
                    () -> topic.publish(boundMethod));
            assertThrows(IllegalArgumentException.class,
                    () -> topic.publish(java.util.List.of(closure)));
            assertThrows(IllegalArgumentException.class,
                    () -> topic.publish(java.util.Map.of("nested", java.util.List.of(boundMethod))));
            assertEquals(0, captured.size(),
                    "rejected callable payloads must not invoke their lexical captures");
            assertTrue(topic.publish("data-only").isDone(),
                    "failed callable admission must not poison a subsequent data publication");
        }
    }

    @Test
    void topicAndGroupNamesRejectIsoControlCharacters() {
        try (ActorRuntime runtime = new ActorRuntime();
             SourceActorPubSub pubsub = new SourceActorPubSub(runtime)) {
            SourceActorPubSub.Group group = pubsub.group("valid-group");
            for (int codePoint : new int[]{0x00, 0x1f, 0x7f, 0x80, 0x85, 0x9f}) {
                String invalidName = "before" + (char) codePoint + "after";
                assertThrows(IllegalArgumentException.class,
                        () -> pubsub.group(invalidName));
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(invalidName, "latest", 1));
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(invalidName));
            }
            for (int codePoint : new int[]{0x20, 0x09, 0x00a0, 0x2003, 0x202f, 0x3000}) {
                String whitespaceOnly = String.valueOf(Character.toChars(codePoint));
                assertThrows(IllegalArgumentException.class,
                        () -> pubsub.group(whitespaceOnly));
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(whitespaceOnly, "latest", 1));
                assertThrows(IllegalArgumentException.class,
                        () -> group.topic(whitespaceOnly));
            }
            assertEquals("valid", group.topic("valid", "latest", 1).name());
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
