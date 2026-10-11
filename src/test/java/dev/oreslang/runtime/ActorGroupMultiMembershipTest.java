package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A long-lived connection actor may cooperate with several rooms while exactly
 * one lifecycle owner retains stop/cancel authority over the actor.
 */
@Timeout(15)
final class ActorGroupMultiMembershipTest {
    @Test
    void connectionCanJoinTwoRoomsAndLeaveOneWithoutLosingItsSocketOwner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup connectionOwner = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomA = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomB = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability joinA = roomA.joinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = roomB.joinCapability();

            roomA.events().defineTopic("chat", ActorEventBus.DeliveryPolicy.LATEST, 1);
            roomB.events().defineTopic("chat", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> connection = connectionOwner.spawnWorker(
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    factoryContext -> (message, actorContext) -> {
                        try {
                            joinA.join();
                            joinB.join();
                            assertTrue(roomA.contains(actorContext.self()));
                            assertTrue(roomB.contains(actorContext.self()));

                            ActorEventBus.Subscription<String> subA =
                                    roomA.events().subscribe("chat");
                            ActorEventBus.Subscription<String> subB =
                                    roomB.events().subscribe("chat");

                            roomA.events().publish("chat", "from-A");
                            roomB.events().publish("chat", "from-B");
                            assertEquals("from-A", subA.tryRead().orElseThrow().value());
                            assertEquals("from-B", subB.tryRead().orElseThrow().value());

                            roomA.leaveCurrent();
                            assertTrue(subA.closed(), "leaving A must close only A's subscription");
                            assertFalse(subB.closed(), "leaving A must not close B's subscription");
                            assertFalse(roomA.contains(actorContext.self()));
                            assertTrue(roomB.contains(actorContext.self()));
                            assertThrows(SecurityException.class, () ->
                                    roomA.events().publish("chat", "unauthorized"));

                            roomB.events().publish("chat", "B-still-works");
                            assertEquals("B-still-works",
                                    subB.tryRead().orElseThrow().value());
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            completed.countDown();
                        }
                    });
            connection.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "both room subscriptions must stay isolated");
            assertFalse(roomA.contains(connection));
            assertTrue(roomB.contains(connection));
            assertEquals(0, roomA.memberCount());
            assertEquals(1, roomB.memberCount());

            // Joining as a member never grants either room the right to own or
            // cancel the socket/connection actor.
            assertThrows(IllegalStateException.class, () -> roomA.manageWorker(connection));
            assertThrows(IllegalStateException.class, () -> roomB.manageWorker(connection));

            roomA.close();
            assertTrue(connection.isAlive(), "closing A must not stop the connection");
            roomB.close();
            assertTrue(connection.isAlive(), "closing B must not stop the connection");
            connectionOwner.close();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
