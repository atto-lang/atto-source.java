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
            ActorRuntime.ActorRef<Integer> connection = connectionOwner.manageWorker(
                    runtime.spawnSharedTrusted(
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
                            assertThrows(SecurityException.class,
                                    () -> roomA.contains(actorContext.self()));
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
                    }));
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

    @Test
    void privateConnectionMayJoinTwoRoomsButCannotSubscribeToZeroCopyEvents() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup roomA = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomB = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability joinA = roomA.joinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = roomB.joinCapability();
            roomA.events().defineTopic("chat", ActorEventBus.DeliveryPolicy.LATEST, 1);
            roomB.events().defineTopic("chat", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> connection = runtime.spawnPrivateTrusted(
                    factoryContext -> (message, actorContext) -> {
                        try {
                            joinA.join();
                            joinB.join();
                            assertTrue(roomA.contains(actorContext.self()));
                            assertTrue(roomB.contains(actorContext.self()));
                            // Private socket-owning actors must use their
                            // mailboxes; group membership never bypasses isolation.
                            assertThrows(SecurityException.class,
                                    () -> roomA.events().subscribe("chat"));
                            assertThrows(SecurityException.class,
                                    () -> roomB.events().subscribe("chat"));
                            roomA.leaveCurrent();
                            assertTrue(roomB.contains(actorContext.self()));
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            completed.countDown();
                        }
                    });
            connection.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertFalse(roomA.contains(connection));
            assertTrue(roomB.contains(connection));

            roomA.close();
            roomB.close();
            assertTrue(connection.isAlive(), "room membership cannot terminate the socket actor");
            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void perActorRoomLimitIsAtomicAndSlotsAreReclaimedAfterLeaveAndClose() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy twoRooms = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 2, base.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup a = runtime.createActorGroup();
            ActorRuntime.ActorGroup b = runtime.createActorGroup();
            ActorRuntime.ActorGroup c = runtime.createActorGroup();
            ActorRuntime.ActorGroup d = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability ca = a.joinCapability();
            ActorRuntime.ActorGroupJoinCapability cb = b.joinCapability();
            ActorRuntime.ActorGroupJoinCapability cc = c.joinCapability();
            ActorRuntime.ActorGroupJoinCapability cd = d.joinCapability();

            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> connection = runtime.spawnShared(
                    twoRooms, () -> (step, ctx) -> {
                        try {
                            if (step == 1) {
                                ca.join();
                                cb.join();
                                cb.join(); // idempotent: consumes no extra slot
                                assertThrows(IllegalStateException.class, cc::join);
                                a.leaveCurrent();
                                cc.join();
                                assertTrue(b.contains(ctx.self()));
                                assertTrue(c.contains(ctx.self()));
                                assertThrows(SecurityException.class,
                                        () -> a.contains(ctx.self()));
                            } else if (step == 2) {
                                // Closing B from the host must free a room
                                // slot without terminating this actor or C.
                                cd.join();
                                assertTrue(c.contains(ctx.self()));
                                assertTrue(d.contains(ctx.self()));
                                assertThrows(SecurityException.class,
                                        () -> b.contains(ctx.self()));
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                        } finally {
                            if (step == 1) first.countDown();
                            if (step == 2) second.countDown();
                        }
                    });
            connection.send(1);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            b.close();
            assertTrue(connection.isAlive());
            connection.send(2);
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, c.memberCount());
            assertEquals(0, d.memberCount());
        }
    }

    @Test
    void creatorLeavingItsGroupStillClosesGroupOnActorTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch created = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorGroup> ownerGroup = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> creator = runtime.spawnSharedTrusted(
                    factoryContext -> (message, context) -> {
                        ActorRuntime.ActorGroup group = context.runtime().createActorGroup();
                        // Structural ownership persists even after the creator
                        // voluntarily drops cooperative membership.
                        group.leaveCurrent();
                        ownerGroup.set(group);
                        created.countDown();
                        context.self().stop();
                    });
            creator.send(1);
            assertTrue(created.await(5, TimeUnit.SECONDS));
            assertTrue(creator.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(ownerGroup.get());
            assertTrue(ownerGroup.get().closed(),
                    "creator retirement must close groups it created even after leave");
            assertEquals(0, runtime.actorGroupCount());
        }
    }

    @Test
    void concurrentRoomJoinsCannotExceedActorMembershipQuota() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy twoRooms = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 2, base.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup a = runtime.createActorGroup();
            ActorRuntime.ActorGroup b = runtime.createActorGroup();
            ActorRuntime.ActorGroup c = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> connection = runtime.spawnShared(
                    twoRooms, () -> (message, context) -> { });
            var groups = java.util.List.of(a, b, c);
            var permits = java.util.List.of(
                    a.joinCapability(), b.joinCapability(), c.joinCapability());
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger successes =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger rejected =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Thread> joins = new java.util.ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                final int index = i;
                Thread joiner = new Thread(() -> {
                    try {
                        release.await();
                        groups.get(index).join(connection, permits.get(index));
                        successes.incrementAndGet();
                    } catch (IllegalStateException capacity) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                joins.add(joiner);
                joiner.start();
            }
            release.countDown();
            for (Thread thread : joins) thread.join(5_000);
            for (Thread thread : joins) assertFalse(thread.isAlive(), "joiner hung");
            assertEquals(2, successes.get(), "concurrent joins exceeded quota");
            assertEquals(1, rejected.get());
            assertEquals(2, a.memberCount() + b.memberCount() + c.memberCount());
            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, a.memberCount() + b.memberCount() + c.memberCount());
        }
    }

    @Test
    void rawJoinCapabilityCannotCrossOrdinaryDataMailboxes() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();

            ActorRuntime.ActorRef<Object> shared = runtime.spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> privateActor = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> { });

            for (Object payload : java.util.List.of(
                    capability,
                    java.util.List.of(capability),
                    java.util.Map.of("invite", capability),
                    java.util.Set.of(capability),
                    new Object[]{java.util.List.of("nested", java.util.Map.of("invite", capability))},
                    java.util.Map.of(capability, "as-key"))) {
                IllegalArgumentException sharedFailure = assertThrows(
                        IllegalArgumentException.class, () -> shared.send(payload));
                assertTrue(sharedFailure.getMessage().contains("CONTROL invitation"),
                        sharedFailure::getMessage);

                IllegalArgumentException privateFailure = assertThrows(
                        IllegalArgumentException.class, () -> privateActor.send(payload));
                assertTrue(privateFailure.getMessage().contains("CONTROL invitation"),
                        privateFailure::getMessage);
            }

            // SharedMutex transport has its own payload validator and must
            // independently reject CONTROL-plane join authority before later
            // ownership walkers can mask a regression in that boundary.
            OresMutex.Shared<Object> sharedMutex =
                    OresMutex.shared((Object) java.util.List.of(capability));
            IllegalArgumentException mutexFailure = assertThrows(
                    IllegalArgumentException.class, () -> shared.send(sharedMutex));
            assertTrue(mutexFailure.getMessage().contains("CONTROL invitation"),
                    mutexFailure::getMessage);

            // Frozen/shared containers and reusable state are not an
            // alternative path for raw join authority to reach DATA actors.
            assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(java.util.Map.of("cap", capability)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.syncCell(java.util.List.of(capability)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.shareReadonly(java.util.Map.of("cap", capability)));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.freezeActorGroupEventPayload(
                            java.util.List.of("event", capability)));

            shared.stop();
            privateActor.stop();
        }
    }

}
