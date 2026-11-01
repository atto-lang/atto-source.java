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
                    assertSame(a, pubsub.group("a"));
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
            assertThrows(SecurityException.class, () -> runtime.invoke(
                    ActorRuntime.ActorKind.SHARED,
                    "probe",
                    (message, context) -> {
                        pubsub.group("a");
                        return null;
                    }));

            actor.send("check");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, b.memberCount());
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
}
