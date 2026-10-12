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
    void creatorCannotEvadeGroupOwnershipQuotaByLeavingMembership() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy twoGroups = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 2, base.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> creator = runtime.spawnShared(
                    twoGroups, () -> (message, context) -> {
                        try {
                            ActorRuntime.ActorGroup first = context.runtime().createActorGroup();
                            ActorRuntime.ActorGroup second = context.runtime().createActorGroup();
                            first.leaveCurrent();
                            second.leaveCurrent();
                            // Creation quota is structural: leaving cooperation
                            // membership must never confer extra creator rights.
                            assertThrows(IllegalStateException.class,
                                    context.runtime()::createActorGroup);
                            first.close();
                            ActorRuntime.ActorGroup replacement =
                                    context.runtime().createActorGroup();
                            assertEquals(2, context.runtime().actorGroupCount());
                            replacement.close();
                            second.close();
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            completed.countDown();
                        }
                    });
            creator.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            creator.stop();
            assertTrue(creator.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorGroupCount());
        }
    }

    @Test
    void actorWithoutSpawnAuthorityCannotCreateGroupsThroughAnyRuntimeCall() throws Exception {
        IsolatePolicy deniedPolicy = IsolatePolicy.developer()
                .withoutCapabilities(IsolatePolicy.Capability.ACTOR_SPAWN);
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    deniedPolicy, () -> (message, context) -> {
                        try {
                            SecurityException denied = assertThrows(
                                    SecurityException.class,
                                    context.runtime()::createActorGroup);
                            assertTrue(denied.getMessage().contains("ACTOR_SPAWN"));
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            completed.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorGroupCount());
            // The host/supervisor control path retains explicit authority.
            ActorRuntime.ActorGroup hostGroup = runtime.createActorGroup();
            hostGroup.close();
        }
    }

    @Test
    void permitRotationVsJoinHasOneAdmissionOrderingAndNoPostRotationReuse() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup room = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> connection =
                    runtime.spawnSharedTrusted(factory -> (message, actor) -> { });
            ActorRuntime.ActorGroupJoinCapability initial = room.joinCapability();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorGroupJoinCapability> rotated =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread rotator = new Thread(() -> {
                try {
                    start.await();
                    rotated.set(room.rotateJoinCapability());
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            Thread joiner = new Thread(() -> {
                try {
                    start.await();
                    // Either admission wins before rotation, or stale
                    // capability rejection wins after rotation. Never allow
                    // a partially updated membership index in either case.
                    room.join(connection, initial);
                } catch (SecurityException expectedIfRotationWon) {
                    // Expected when rotation linearizes first.
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            rotator.start();
            joiner.start();
            start.countDown();
            rotator.join(5_000);
            joiner.join(5_000);
            assertFalse(rotator.isAlive(), "rotator deadlocked");
            assertFalse(joiner.isAlive(), "joiner deadlocked");
            assertNull(failure.get());
            assertNotNull(rotated.get());
            assertThrows(SecurityException.class,
                    () -> room.join(connection, initial),
                    "stale permits cannot be accepted even for idempotent joins");
            room.join(connection, rotated.get());
            assertEquals(1, room.memberCount());
            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, room.memberCount());
        }
    }

    @Test
    void foreignRuntimeCannotAdmitItsActorsWithAnotherRuntimesPermit() throws Exception {
        try (ActorRuntime one = new ActorRuntime();
             ActorRuntime two = new ActorRuntime()) {
            ActorRuntime.ActorGroup room = one.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = room.joinCapability();
            ActorRuntime.ActorRef<Integer> foreign =
                    two.spawnSharedTrusted(factory -> (message, actor) -> { });
            assertThrows(IllegalArgumentException.class, () -> room.join(foreign, join));
            assertEquals(0, room.memberCount());
            foreign.stop();
            assertTrue(foreign.awaitTermination(5, TimeUnit.SECONDS));
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
    void rotatedPermitCannotJoinEvenWhileAnotherRoomRemainsAvailable() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup roomA = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomB = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability stale = roomA.joinCapability();
            ActorRuntime.ActorGroupJoinCapability replacement = roomA.rotateJoinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = roomB.joinCapability();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> problem = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, ctx) -> {
                        try {
                            assertThrows(SecurityException.class, stale::join,
                                    "rotated permit must not admit a new member");
                            assertThrows(SecurityException.class, roomA::memberCount,
                                    "nonmember may not inspect group membership");
                            replacement.join();
                            joinB.join();
                            assertTrue(roomA.contains(ctx.self()));
                            assertTrue(roomB.contains(ctx.self()));
                            roomA.leaveCurrent();
                            assertThrows(SecurityException.class, stale::join,
                                    "revoked authority cannot bypass membership revocation");
                            assertThrows(SecurityException.class, roomA::memberCount,
                                    "leaving A must revoke A's observer authority");
                            assertTrue(roomB.contains(ctx.self()));
                        } catch (Throwable failure) {
                            problem.set(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(problem.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, roomA.memberCount());
            assertEquals(0, roomB.memberCount());
        }
    }


    @Test
    void closingOneRoomRacingAdmissionCannotResurrectItOrRevokeAnotherRoom()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup stableRoom = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> connection =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            stableRoom.join(connection, stableRoom.joinCapability());
            for (int round = 0; round < 64; round++) {
                ActorRuntime.ActorGroup closingRoom = runtime.createActorGroup();
                ActorRuntime.ActorGroupJoinCapability permit = closingRoom.joinCapability();
                CountDownLatch release = new CountDownLatch(1);
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                Thread joiner = new Thread(() -> {
                    try {
                        release.await();
                        closingRoom.join(connection, permit);
                    } catch (SecurityException | IllegalStateException expected) {
                        // Either close revoked admission first, or join won.
                    } catch (Throwable error) {
                        unexpected.compareAndSet(null, error);
                    }
                });
                Thread closer = new Thread(() -> {
                    try {
                        release.await();
                        closingRoom.close();
                    } catch (Throwable error) {
                        unexpected.compareAndSet(null, error);
                    }
                });
                joiner.start();
                closer.start();
                release.countDown();
                joiner.join(5_000);
                closer.join(5_000);
                assertFalse(joiner.isAlive(), "joiner deadlocked with close");
                assertFalse(closer.isAlive(), "closer deadlocked with join");
                assertNull(unexpected.get(), "unexpected concurrent group admission failure");
                assertTrue(closingRoom.closed(), "concurrently closed room reopened");
                assertEquals(0, closingRoom.memberCount(), "closed room retained membership");
                assertThrows(IllegalStateException.class,
                        () -> closingRoom.join(connection, permit),
                        "closed room admitted a stale permit");
                assertTrue(stableRoom.contains(connection),
                        "closing unrelated room revoked healthy membership");
                assertTrue(connection.isAlive(), "cooperation room closed the socket owner");
            }
            stableRoom.close();
            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void multiRoomMembershipNeverRunsOneActorOnTwoCarriers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup roomA = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomB = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability joinA = roomA.joinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = roomB.joinCapability();

            CountDownLatch firstTurnEntered = new CountDownLatch(1);
            CountDownLatch secondTurnEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstTurn = new CountDownLatch(1);
            CountDownLatch bothTurnsFinished = new CountDownLatch(2);
            java.util.concurrent.atomic.AtomicInteger activeTurns =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger peakConcurrentTurns =
                    new java.util.concurrent.atomic.AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> connection = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        int nowActive = activeTurns.incrementAndGet();
                        peakConcurrentTurns.accumulateAndGet(nowActive, Math::max);
                        try {
                            if (message == 1) {
                                joinA.join();
                                joinB.join();
                                assertTrue(roomA.contains(context.self()));
                                assertTrue(roomB.contains(context.self()));
                                firstTurnEntered.countDown();
                                if (!releaseFirstTurn.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("first actor turn was not released");
                                }
                            } else {
                                secondTurnEntered.countDown();
                                assertEquals(2, message.intValue());
                                assertTrue(roomA.contains(context.self()));
                                assertTrue(roomB.contains(context.self()));
                            }
                        } catch (Throwable failure) {
                            unexpected.compareAndSet(null, failure);
                        } finally {
                            activeTurns.decrementAndGet();
                            bothTurnsFinished.countDown();
                        }
                    });

            try {
                connection.send(1);
                assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS),
                        "first turn never acquired the actor execution lane");
                // The actor participates in two cooperation groups, but neither
                // group owns another execution lane. The second message must
                // not run concurrently while the first turn remains active.
                connection.send(2);
                assertFalse(secondTurnEntered.await(100, TimeUnit.MILLISECONDS),
                        "second turn started before the first turn released its execution lane");
            } finally {
                releaseFirstTurn.countDown();
            }
            assertTrue(bothTurnsFinished.await(5, TimeUnit.SECONDS),
                    "both actor messages did not complete");
            assertNull(unexpected.get());
            assertEquals(1, peakConcurrentTurns.get(),
                    "a multi-room actor ran concurrently on two carriers");
            assertEquals(0, activeTurns.get());
            assertEquals(1, roomA.memberCount());
            assertEquals(1, roomB.memberCount());

            connection.stop();
            assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, roomA.memberCount());
            assertEquals(0, roomB.memberCount());
        }
    }

    @Test
    void concurrentHostProducersPreserveMultiRoomActorTurnSerialityAndExactlyOnceDelivery()
            throws Exception {
        final int producers = 4;
        final int messagesPerProducer = 16;
        final int total = producers * messagesPerProducer;
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup roomA = runtime.createActorGroup();
            ActorRuntime.ActorGroup roomB = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability joinA = roomA.joinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = roomB.joinCapability();

            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch startProducers = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(total);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger activeTurns =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger peakTurns =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.Set<Integer> received =
                    java.util.concurrent.ConcurrentHashMap.newKeySet();

            ActorRuntime.ActorRef<Integer> connection = runtime.spawnSharedTrusted(
                    factory -> (message, actor) -> {
                        int active = activeTurns.incrementAndGet();
                        peakTurns.accumulateAndGet(active, Math::max);
                        try {
                            if (message == 0) {
                                joinA.join();
                                joinB.join();
                                joined.countDown();
                            } else {
                                assertTrue(roomA.contains(actor.self()));
                                assertTrue(roomB.contains(actor.self()));
                                assertTrue(received.add(message),
                                        "actor must consume each producer ticket once");
                                Thread.yield();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            activeTurns.decrementAndGet();
                            if (message != 0) delivered.countDown();
                        }
                    });

            connection.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            java.util.List<Thread> senders = new java.util.ArrayList<>();
            for (int producer = 0; producer < producers; producer++) {
                final int number = producer;
                Thread sender = new Thread(() -> {
                    try {
                        if (!startProducers.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("producer start timed out");
                        }
                        for (int ticket = 1; ticket <= messagesPerProducer; ticket++) {
                            connection.send(number * messagesPerProducer + ticket);
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                }, "actor-group-producer-" + producer);
                senders.add(sender);
                sender.start();
            }
            try {
                startProducers.countDown();
                for (Thread sender : senders) {
                    sender.join(5_000);
                    assertFalse(sender.isAlive(), "a concurrent message producer deadlocked");
                }
                assertTrue(delivered.await(5, TimeUnit.SECONDS),
                        "actor did not consume every concurrently enqueued message");
                assertNull(failure.get());
                assertEquals(total, received.size(),
                        "multi-room actor lost or duplicated a producer ticket");
                assertEquals(1, peakTurns.get(),
                        "two carriers simultaneously ran the same multi-room actor");
                assertEquals(0, activeTurns.get());
                assertEquals(1, roomA.memberCount());
                assertEquals(1, roomB.memberCount());
            } finally {
                connection.stop();
                assertTrue(connection.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(0, roomA.memberCount());
            assertEquals(0, roomB.memberCount());
        }
    }

    @Test
    void creatorShutdownDuringGroupCreationAlwaysReclaimsOwnedGroup()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            for (int round = 0; round < 24; round++) {
                CountDownLatch actorEntered = new CountDownLatch(1);
                CountDownLatch releaseCreation = new CountDownLatch(1);
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                ActorRuntime.ActorRef<Integer> creator = runtime.spawnSharedTrusted(
                        factory -> (message, actor) -> {
                            actorEntered.countDown();
                            try {
                                if (!releaseCreation.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("creator creation barrier timed out");
                                }
                                // stop() can race any point between creator
                                // registration and group publication.
                                try {
                                    runtime.createActorGroup();
                                } catch (IllegalStateException alreadyStopping) {
                                    // Only creator retirement is expected. Never
                                    // silently swallow quota or runtime defects.
                                    String message = alreadyStopping.getMessage();
                                    if (!"current actor is no longer registered".equals(message)
                                            && !"group creator is no longer alive".equals(message)
                                            && !"group creator stopped during creation".equals(message)) {
                                        throw alreadyStopping;
                                    }
                                }
                            } catch (Throwable failure) {
                                unexpected.compareAndSet(null, failure);
                            }
                        });
                creator.send(1);
                assertTrue(actorEntered.await(5, TimeUnit.SECONDS),
                        "creator did not enter its actor turn");
                AtomicReference<Throwable> stopFailure = new AtomicReference<>();
                Thread terminator = new Thread(() -> {
                    try {
                        creator.stop();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                });
                terminator.start();
                releaseCreation.countDown();
                terminator.join(5_000);
                assertFalse(terminator.isAlive(), "creator stop deadlocked group publication");
                assertNull(stopFailure.get());
                assertTrue(creator.awaitTermination(5, TimeUnit.SECONDS),
                        "creator retirement blocked by ActorGroup close");
                assertNull(unexpected.get());
                assertEquals(0, runtime.actorGroupCount(),
                        "creator's structural ActorGroups escaped termination cleanup");
            }
        }
    }
}
