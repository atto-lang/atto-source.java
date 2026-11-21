package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-affine group monitor regression. These source-level JUnit bodies are
 * NOT_EXECUTED until an exact-head, stepful CI reports their method receipts.
 * No guest-language monitor lowering or Native Image parity is claimed.
 */
@Timeout(60)
final class ActorGroupMembershipMonitorTest {
    @Test
    void snapshotOverflowsResyncsAndFencesOldGrantAfterLeaveRejoin() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability admit = group.joinCapability();
            AtomicReference<ActorRuntime.ActorGroup.MembershipMonitor> monitor =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipSnapshot> initial =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipSnapshot> recovered =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipPoll> overflow =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipPoll> latest =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch overflowChecked = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            CountDownLatch staleChecked = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> watcher = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            switch (message) {
                                case "register" -> {
                                    // Registration and baseline membership revision must
                                    // be a single linearizable operation.
                                    var registration = group.registerMembershipMonitor(1);
                                    monitor.set(registration.monitor());
                                    initial.set(registration.snapshot());
                                }
                                case "overflow" -> {
                                    overflow.set(monitor.get().poll("1"));
                                    recovered.set(monitor.get().resync());
                                }
                                case "latest" -> {
                                    assertEquals(
                                            ActorRuntime.ActorGroup.MembershipPollStatus.INVALID_REVISION,
                                            monitor.get().poll("03").status());
                                    assertEquals(
                                            ActorRuntime.ActorGroup.MembershipPollStatus.REVISION_MISMATCH,
                                            monitor.get().poll("1").status());
                                    // Both refusals must leave the queued revision-4
                                    // event available for the correct cursor.
                                    latest.set(monitor.get().poll("3"));
                                }
                                case "stale" -> {
                                    IllegalStateException denied = assertThrows(
                                            IllegalStateException.class,
                                            () -> monitor.get().poll("6"));
                                    assertTrue(denied.getMessage().contains("StaleMonitor"));
                                }
                                default -> throw new AssertionError("unknown message " + message);
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            switch (message) {
                                case "register" -> registered.countDown();
                                case "overflow" -> overflowChecked.countDown();
                                case "latest" -> delivered.countDown();
                                case "stale" -> staleChecked.countDown();
                                default -> { }
                            }
                        }
                    });
            ActorRuntime.ActorRef<String> other =
                    runtime.spawnShared(() -> (message, context) -> { });
            group.join(watcher, admit); // revision 1
            watcher.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals("1", initial.get().revision());
            assertEquals(List.of(watcher.id()), initial.get().members());
            assertThrows(SecurityException.class, () -> monitor.get().poll(),
                    "a host thread cannot impersonate the monitor owner");
            assertEquals(1, group.activeMembershipMonitorCount());

            group.join(other, admit); // revision 2
            group.leave(other); // revision 3: capacity-one monitor overflows
            watcher.send("overflow");
            assertTrue(overflowChecked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.OVERFLOW,
                    overflow.get().status());
            assertEquals("3", recovered.get().revision());
            assertEquals(List.of(watcher.id()), recovered.get().members());

            group.join(other, admit); // revision 4, one queued delta
            watcher.send("latest");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.EVENT,
                    latest.get().status());
            assertEquals("4", latest.get().event().revision());
            assertEquals(ActorRuntime.ActorGroup.MembershipChangeKind.JOIN,
                    latest.get().event().kind());
            assertEquals(other.id(), latest.get().event().actorId());

            group.leave(watcher); // revokes old monitor before same-actor rejoin
            assertEquals(0, group.activeMembershipMonitorCount());
            group.join(watcher, admit); // new membership, old handle stays stale
            watcher.send("stale");
            assertTrue(staleChecked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(watcher.isAlive());
            group.close();
            watcher.stop();
            other.stop();
            assertTrue(watcher.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(other.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void otherMemberCannotUseMonitorAndGroupCloseReclaimsCredit() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var capability = group.joinCapability();
            AtomicReference<ActorRuntime.ActorGroup.MembershipMonitor> monitor =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch rejected = new CountDownLatch(1);
            CountDownLatch stale = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> owner = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            if ("register".equals(message)) {
                                monitor.set(group.monitorMembership(2));
                            } else if ("stale".equals(message)) {
                                assertThrows(IllegalStateException.class, monitor.get()::resync);
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if ("register".equals(message)) registered.countDown();
                            if ("stale".equals(message)) stale.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> intruder = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class,
                                    () -> monitor.get().poll());
                            assertThrows(SecurityException.class,
                                    () -> monitor.get().close());
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            rejected.countDown();
                        }
                    });
            group.join(owner, capability);
            group.join(intruder, capability);
            owner.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            intruder.send("attack");
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, group.activeMembershipMonitorCount());

            group.close();
            assertEquals(0, group.activeMembershipMonitorCount());
            assertTrue(owner.isAlive(), "group close does not own ordinary actor lifecycle");
            assertTrue(intruder.isAlive());
            owner.send("stale");
            assertTrue(stale.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            owner.stop();
            intruder.stop();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(intruder.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void parallelMembershipChurnOverflowsButResyncReturnsExactRevision()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var join = group.joinCapability();
            AtomicReference<ActorRuntime.ActorGroup.MembershipMonitor> monitor =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipPoll> outcome =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipSnapshot> snapshot =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> watcher = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            if ("register".equals(message)) {
                                monitor.set(group.monitorMembership(2));
                            } else if ("resync".equals(message)) {
                                outcome.set(monitor.get().poll("1"));
                                snapshot.set(monitor.get().resync());
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if ("register".equals(message)) ready.countDown();
                            if ("resync".equals(message)) done.countDown();
                        }
                    });
            group.join(watcher, join);
            watcher.send("register");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            List<ActorRuntime.ActorRef<String>> peers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                peers.add(runtime.spawnShared(() -> (message, context) -> { }));
            }
            ExecutorService churn = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (var peer : peers) {
                    tasks.add(churn.submit(() -> {
                        for (int i = 0; i < 32; i++) {
                            group.join(peer, join);
                            group.leave(peer);
                        }
                    }));
                }
                for (Future<?> future : tasks) future.get(20, TimeUnit.SECONDS);
            } finally {
                churn.shutdownNow();
                assertTrue(churn.awaitTermination(5, TimeUnit.SECONDS));
            }
            watcher.send("resync");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.OVERFLOW,
                    outcome.get().status());
            assertEquals("513", snapshot.get().revision(),
                    "watcher join + 8 peers x 32 balanced join/leave cycles");
            assertEquals(Set.of(watcher.id()), Set.copyOf(snapshot.get().members()));
            assertEquals(snapshot.get(), group.membershipSnapshot());
            group.close();
            watcher.stop();
            assertTrue(watcher.awaitTermination(5, TimeUnit.SECONDS));
            for (var peer : peers) {
                peer.stop();
                assertTrue(peer.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void monitorCreditsArePerActorAcrossIndependentGroups() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup first = runtime.createActorGroup();
            ActorRuntime.ActorGroup second = runtime.createActorGroup();
            var firstJoin = first.joinCapability();
            var secondJoin = second.joinCapability();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            List<ActorRuntime.ActorGroup.MembershipMonitor> monitors =
                                    new ArrayList<>();
                            for (int i = 0; i < 10; i++) {
                                monitors.add(first.monitorMembership(1));
                            }
                            for (int i = 0; i < 6; i++) {
                                monitors.add(second.monitorMembership(1));
                            }
                            assertThrows(IllegalStateException.class,
                                    () -> second.monitorMembership(1),
                                    "per-actor credit is shared across groups");
                            assertThrows(IllegalArgumentException.class,
                                    () -> first.monitorMembership(257));
                            monitors.getFirst().close();
                            monitors.add(second.monitorMembership(1));
                            assertEquals(9, first.activeMembershipMonitorCount());
                            assertEquals(7, second.activeMembershipMonitorCount());
                            for (var monitor : monitors) monitor.close();
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            finished.countDown();
                        }
                    });
            first.join(actor, firstJoin);
            second.join(actor, secondJoin);
            actor.send("quotas");
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0, first.activeMembershipMonitorCount());
            assertEquals(0, second.activeMembershipMonitorCount());
            first.close();
            second.close();
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void monitorTokenAndNestedTokenCannotBeSentOrSharedAsData() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var join = group.joinCapability();
            AtomicReference<ActorRuntime.ActorGroup.MembershipMonitor> token =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            ActorRuntime.ActorRef<Object> owner = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            token.set(group.monitorMembership(2));
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            registered.countDown();
                        }
                    });
            ActorRuntime.ActorRef<Object> recipient = runtime.spawnShared(
                    () -> (message, context) -> { });
            group.join(owner, join);
            owner.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            var monitor = token.get();
            assertNotNull(monitor);
            assertThrows(SecurityException.class, () -> recipient.send(monitor));
            assertThrows(SecurityException.class, () -> recipient.send(List.of(monitor)));
            assertThrows(SecurityException.class, () ->
                    runtime.freezeActorGroupEventPayload(monitor));
            assertThrows(SecurityException.class, () ->
                    runtime.freezeActorGroupEventPayload(List.of(monitor)));
            assertThrows(SecurityException.class, () -> runtime.shareReadonly(monitor));
            assertEquals(1, group.activeMembershipMonitorCount(),
                    "rejected transport must never mutate monitor authority");
            group.close();
            assertEquals(0, group.activeMembershipMonitorCount());
            owner.stop();
            recipient.stop();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(recipient.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
