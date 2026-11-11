package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
            try (var route = new ActorRouteCoordinatorV1<>(
                    83, supervisor, image('a'), state(0), v1,
                    32, 32, 1024)) {
                var stable = route.logicalRef();
                var transport = ActorRuntimeHotRouteBridgeV1
                        .<Integer>processedReceiptTransport(bytes -> (int) bytes[0]);
                for (int i = 0; i < 10; i++) stable.send(new byte[]{1});
                for (int i = 0; i < 10; i++) assertTrue(route.dispatchNext(supervisor, transport));
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
                assertTrue(stage.publish(supervisor, state(oldCount.get()), v2));
                assertSame(stable, route.logicalRef());
                assertEquals(83, stable.id());
                assertSame(v2, route.current().physicalRoute());
                assertEquals(2L, route.current().routeEpoch());
                for (int i = 0; i < 5; i++) assertTrue(route.dispatchNext(supervisor, transport));
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
}
