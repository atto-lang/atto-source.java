package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the REAL ActorRuntime SHARED physical actors behind one host logical
 * ActorRef. The two actor behaviors are Java HOST factories, not .atto guests.
 * No claim of guest state migration or physical FFI/Task pin validation.
 */
final class ActorRuntimeHotRouteBridgeV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }
    private static ActorUpgradeEpochGate.Image image(char c) {
        return new ActorUpgradeEpochGate.Image(sha(c), sha('1'), sha('2'), sha('3'));
    }
    private static ActorRouteCoordinatorV1.StateSnapshot state(int count) {
        return new ActorRouteCoordinatorV1.StateSnapshot(
                sha('1'), ByteBuffer.allocate(4).putInt(count).array());
    }
    private static void drained(ActorRouteCoordinatorV1<?> coordinator) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (coordinator.snapshot().mailbox().retainedMessages() != 0) {
            if (System.nanoTime() >= deadline)
                fail("real ActorRuntime did not physically process all accepted mail");
            Thread.sleep(5);
        }
    }

    @Test
    void realSharedActorRuntimeFifoRouteHandoverUsesStableLogicalReference()
            throws Exception {
        Object supervisor = new Object();
        AtomicInteger oldCount = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> v1 =
                    runtime.<Integer>spawnShared(() ->
                            (message, context) -> oldCount.addAndGet(message));
            v1.ready().get(5, TimeUnit.SECONDS);
            var sealedV1 = runtime.claimPhysicalForHotRoute(v1);
            assertThrows(SecurityException.class, () -> v1.send(1),
                    "ordinary physical ActorRef sends must not bypass the logical route");
            assertThrows(SecurityException.class, () -> v1.sendAsync(1),
                    "async physical sends must also be fenced");
            try (var route = new ActorRouteCoordinatorV1<>(
                    83, supervisor, image('a'), state(0), sealedV1,
                    32, 32, 1024)) {
                var stable = route.logicalRef();
                var transport = ActorRuntimeHotRouteBridgeV1
                        .<Integer>processedReceiptTransport(bytes -> (int) bytes[0]);
                for (int i = 0; i < 10; i++) stable.send(new byte[]{1});
                // The logical coordinator deliberately allows only one
                // unacknowledged physical submission to preserve FIFO even
                // with competing CONTROL carriers.
                int oldSubmitted = 0;
                long oldDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (oldSubmitted < 10) {
                    if (route.dispatchNext(supervisor, transport)) {
                        oldSubmitted++;
                    } else {
                        if (System.nanoTime() >= oldDeadline)
                            fail("v1 physical mailbox could not drain in time");
                        Thread.sleep(2);
                    }
                }
                drained(route);
                assertEquals(10, oldCount.get());

                var stage = route.stage(supervisor, image('b'));
                for (int i = 0; i < 5; i++) stable.send(new byte[]{1});
                assertTrue(stage.quiescent(supervisor));
                AtomicInteger newCount = new AtomicInteger(oldCount.get());
                ActorRuntime.ActorRef<Integer> v2 =
                        runtime.<Integer>spawnShared(() ->
                                (message, context) -> newCount.addAndGet(message));
                v2.ready().get(5, TimeUnit.SECONDS);
                var sealedV2 = runtime.claimPhysicalForHotRoute(v2);
                assertThrows(SecurityException.class, () -> v2.send(1));
                assertTrue(stage.publish(supervisor, state(oldCount.get()), sealedV2));
                assertSame(stable, route.logicalRef());
                assertEquals(83, stable.id());
                assertSame(sealedV2, route.current().physicalRoute());
                assertThrows(SecurityException.class, () -> v1.send(1));
                assertThrows(SecurityException.class, () -> v2.send(1));
                assertThrows(SecurityException.class,
                        () -> sealedV1.sendTracked(1, null),
                        "no new physical receipt may be invented for the retired route");
                assertEquals(2L, route.current().routeEpoch());
                int nextSubmitted = 0;
                long nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (nextSubmitted < 5) {
                    if (route.dispatchNext(supervisor, transport)) {
                        nextSubmitted++;
                    } else {
                        if (System.nanoTime() >= nextDeadline)
                            fail("v2 physical mailbox could not drain in time");
                        Thread.sleep(2);
                    }
                }
                drained(route);
                assertEquals(10, oldCount.get());
                assertEquals(15, newCount.get());
                assertEquals(0, route.snapshot().mailbox().retainedMessages());
                v2.stop();
                assertTrue(v2.awaitTermination(5, TimeUnit.SECONDS));
            }
            v1.stop();
            assertTrue(v1.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void physicalClaimIsOneWayAndDoesNotFenceUnrelatedActors()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            var claimed = runtime.<Integer>spawnShared(
                    () -> (mail, context) -> first.addAndGet(mail));
            var unrelated = runtime.<Integer>spawnShared(
                    () -> (mail, context) -> second.addAndGet(mail));
            claimed.ready().get(5, TimeUnit.SECONDS);
            unrelated.ready().get(5, TimeUnit.SECONDS);

            var permit = runtime.claimPhysicalForHotRoute(claimed);
            assertTrue(permit.readyForPublication());
            assertThrows(IllegalStateException.class,
                    () -> runtime.claimPhysicalForHotRoute(claimed),
                    "physical ActorRef cannot be enrolled twice");
            assertThrows(SecurityException.class, () -> claimed.send(1));
            assertThrows(SecurityException.class, () -> claimed.sendAsync(1));
            unrelated.send(3);
            unrelated.drain().get(5, TimeUnit.SECONDS);
            assertEquals(3, second.get());
            assertEquals(0, first.get());

            permit.retireFromLogicalRoute();
            assertFalse(permit.readyForPublication());
            assertThrows(SecurityException.class, () -> claimed.send(1),
                    "retirement must not reopen stale raw physical delivery");
            claimed.stop();
            unrelated.stop();
            assertTrue(claimed.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(unrelated.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preclaimDirectPhysicalTurnBlocksLogicalUpgradeUntilProcessed()
            throws Exception {
        Object supervisor = new Object();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger oldCount = new AtomicInteger();
        AtomicInteger nextCount = new AtomicInteger(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var v1 = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("test physical turn was never released");
                oldCount.addAndGet(mail);
            });
            var v2 = runtime.<Integer>spawnShared(() ->
                    (mail, context) -> nextCount.addAndGet(mail));
            v1.ready().get(5, TimeUnit.SECONDS);
            v2.ready().get(5, TimeUnit.SECONDS);
            try {
                v1.send(1); // Accepted directly BEFORE it joins logical route.
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var physical1 = runtime.claimPhysicalForHotRoute(v1);
                var physical2 = runtime.claimPhysicalForHotRoute(v2);
                try (var route = new ActorRouteCoordinatorV1<>(
                        91, supervisor, image('a'), state(0), physical1,
                        8, 32, 256)) {
                    var stage = route.stage(supervisor, image('b'));
                    assertFalse(stage.quiescent(supervisor),
                            "preclaim physical send must still count as outstanding v1 work");
                    assertFalse(stage.publish(supervisor, state(1), physical2));
                    release.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!stage.quiescent(supervisor)) {
                        if (System.nanoTime() >= deadline)
                            fail("v1 direct preclaim message did not physically finish");
                        Thread.sleep(2);
                    }
                    assertEquals(1, oldCount.get());
                    assertTrue(stage.publish(supervisor, state(oldCount.get()), physical2));
                    var stable = route.logicalRef();
                    stable.send(new byte[]{1});
                    assertTrue(route.dispatchNext(supervisor,
                            ActorRuntimeHotRouteBridgeV1
                                    .<Integer>processedReceiptTransport(bytes -> (int) bytes[0])));
                    drained(route);
                    assertEquals(2, nextCount.get());
                    assertEquals(1, oldCount.get());
                    assertEquals(2, route.current().routeEpoch());
                }
            } finally {
                release.countDown();
                v1.stop();
                v2.stop();
                assertTrue(v1.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(v2.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

}
