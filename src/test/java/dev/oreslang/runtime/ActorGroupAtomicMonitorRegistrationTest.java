package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The monitor+snapshot receipt must linearize with the concurrent join.
 * A separate snapshot after registering a monitor can skip the first delta.
 * Test bodies are NOT_EXECUTED until exact-head Maven/JUnit step receipts.
 */
@Timeout(90)
final class ActorGroupAtomicMonitorRegistrationTest {
    @Test
    void registrationAndConcurrentJoinProvideEitherBaselineOrNextRevision()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var grant = group.joinCapability();
            AtomicReference<ActorRuntime.ActorGroup.MembershipRegistration> registered =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorGroup.MembershipSnapshot> afterJoin =
                    new AtomicReference<>();
            AtomicReference<CountDownLatch> registrationGate = new AtomicReference<>();
            AtomicReference<CountDownLatch> verificationGate = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorId> peerId = new AtomicReference<>();

            var watcher = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("register".equals(message)) {
                        registered.set(group.registerMembershipMonitor(4));
                    } else if ("verify".equals(message)) {
                        var registration = registered.get();
                        assertNotNull(registration);
                        var snapshot = registration.snapshot();
                        var finalSnapshot = afterJoin.get();
                        assertNotNull(finalSnapshot);
                        assertTrue(finalSnapshot.members().contains(peerId.get()));
                        assertEquals(2, finalSnapshot.members().size(),
                                "two live cooperating members");
                        if (snapshot.members().contains(peerId.get())) {
                            // The racing join took the membership lock first.
                            assertEquals(finalSnapshot.revision(), snapshot.revision());
                            assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.EMPTY,
                                    registration.monitor().poll(snapshot.revision()).status());
                        } else {
                            // Registration won the race. Exactly the NEXT delta
                            // must be observed, without a window or replay.
                            assertEquals(
                                    new BigInteger(snapshot.revision()).add(BigInteger.ONE),
                                    new BigInteger(finalSnapshot.revision()));
                            var observed = registration.monitor().poll(snapshot.revision());
                            assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.EVENT,
                                    observed.status());
                            assertEquals(finalSnapshot.revision(), observed.event().revision());
                            assertEquals(peerId.get(), observed.event().actorId());
                            assertEquals(ActorRuntime.ActorGroup.MembershipChangeKind.JOIN,
                                    observed.event().kind());
                        }
                        assertEquals(ActorRuntime.ActorGroup.MembershipPollStatus.EMPTY,
                                registration.monitor().poll().status());
                        registration.monitor().close();
                    } else {
                        throw new AssertionError("unknown watcher message: " + message);
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    if ("register".equals(message)) registrationGate.get().countDown();
                    if ("verify".equals(message)) verificationGate.get().countDown();
                }
            });
            var peer = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            peerId.set(peer.id());
            group.join(watcher, grant); // Revision 1 before this race suite.

            ExecutorService joiners = Executors.newSingleThreadExecutor();
            try {
                for (int n = 0; n < 24; n++) {
                    registered.set(null);
                    CountDownLatch start = new CountDownLatch(1);
                    CountDownLatch registeredNow = new CountDownLatch(1);
                    CountDownLatch checkedNow = new CountDownLatch(1);
                    registrationGate.set(registeredNow);
                    verificationGate.set(checkedNow);
                    watcher.send("register");
                    Future<?> joined = joiners.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        group.join(peer, grant);
                        return null;
                    });
                    start.countDown();
                    joined.get(10, TimeUnit.SECONDS);
                    assertTrue(registeredNow.await(10, TimeUnit.SECONDS));
                    assertNull(failure.get(), () -> String.valueOf(failure.get()));
                    afterJoin.set(group.membershipSnapshot());
                    watcher.send("verify");
                    assertTrue(checkedNow.await(10, TimeUnit.SECONDS));
                    assertNull(failure.get(), () -> String.valueOf(failure.get()));
                    assertEquals(0, group.activeMembershipMonitorCount(),
                            "closing each monitor reclaims quota under membership churn");
                    group.leave(peer);
                }
            } finally {
                joiners.shutdownNow();
                assertTrue(joiners.awaitTermination(10, TimeUnit.SECONDS));
                group.close();
                watcher.stop();
                peer.stop();
                assertTrue(watcher.awaitTermination(10, TimeUnit.SECONDS));
                assertTrue(peer.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void invalidCapacityNeverAllocatesSnapshotOrMembershipMonitor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var grant = group.joinCapability();
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    assertThrows(IllegalArgumentException.class,
                            () -> group.registerMembershipMonitor(0));
                    assertThrows(IllegalArgumentException.class,
                            () -> group.registerMembershipMonitor(257));
                    assertEquals(0, group.activeMembershipMonitorCount());
                    var receipt = group.registerMembershipMonitor(1);
                    assertEquals(group.membershipSnapshot(), receipt.snapshot());
                    assertTrue(receipt.monitor().poll().status()
                            == ActorRuntime.ActorGroup.MembershipPollStatus.EMPTY);
                    receipt.monitor().close();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    attempted.countDown();
                }
            });
            group.join(actor, grant);
            actor.send("register");
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0, group.activeMembershipMonitorCount());
            group.close();
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
