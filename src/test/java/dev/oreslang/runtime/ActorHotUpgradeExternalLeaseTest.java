package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Integration with the production ActorRuntime admission and lifecycle monitor.
 * These host registrations are not yet automatic JNI/timer/broker instrumentation. */
@Timeout(20)
final class ActorHotUpgradeExternalLeaseTest {
    private static void drained(ActorRuntime.HotUpgradeIngressFreeze freeze)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!freeze.schedulerDrained() && System.nanoTime() < until)
            Thread.sleep(2);
        assertTrue(freeze.schedulerDrained());
    }

    @Test
    void externalWorkOutlivesPhysicalActorTurnAndPreventsFalseDrain() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.getReady().get(5, TimeUnit.SECONDS);
            var lease = runtime.pinExternalWorkForHotUpgrade(actor);
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(1, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                assertThrows(ActorRuntime.ActorSealedException.class,
                        () -> runtime.pinExternalWorkForHotUpgrade(actor));
                assertThrows(ActorRuntime.ActorSealedException.class,
                        () -> actor.send("too late"));
                lease.close();
                lease.close();
                assertEquals(0, freeze.outstandingExternalPins());
                drained(freeze);
            } finally {
                lease.close();
            }
            actor.send("stop");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closingOldFreezeNeverUnsealsNewFreezeOrStealsExternalLease() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.getReady().get(5, TimeUnit.SECONDS);
            var old = runtime.freezeUserIngressForHotUpgrade(actor);
            old.close();
            var next = runtime.freezeUserIngressForHotUpgrade(actor);
            old.close();
            assertThrows(IllegalStateException.class, old::outstandingExternalPins);
            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> runtime.pinExternalWorkForHotUpgrade(actor));
            next.close();
            try (var lease = runtime.pinExternalWorkForHotUpgrade(actor);
                 var fresh = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(1, fresh.outstandingExternalPins());
                assertFalse(fresh.schedulerDrained());
            }
            actor.send("stop");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void releaseOnOtherThreadIsIdempotentAndWakesSupervisor() throws Exception {
        CountDownLatch mayRelease = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<Throwable> asyncError = new AtomicReference<>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.getReady().get(5, TimeUnit.SECONDS);
            var lease = runtime.pinExternalWorkForHotUpgrade(actor);
            Thread foreign = new Thread(() -> {
                try {
                    assertTrue(mayRelease.await(5, TimeUnit.SECONDS));
                    lease.close();
                    lease.close();
                } catch (Throwable t) {
                    asyncError.set(t);
                } finally {
                    released.countDown();
                }
            }, "hot-upgrade-external-resource-close");
            foreign.start();
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(1, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                mayRelease.countDown();
                assertTrue(released.await(5, TimeUnit.SECONDS));
                foreign.join(5000);
                assertNull(asyncError.get());
                drained(freeze);
            } finally {
                mayRelease.countDown();
                foreign.join(5000);
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void externalPinOwnershipCannotCrossActorRuntimes() throws Exception {
        try (var left = new ActorRuntime(); var right = new ActorRuntime()) {
            var actor = left.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.getReady().get(5, TimeUnit.SECONDS);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> right.pinExternalWorkForHotUpgrade(actor));
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
