package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime termination linearization regression. ActorRef absence
 * from the runtime registry must never be mistaken for completed physical
 * finalization while shutdown observers are still executing.
 */
@Timeout(30)
final class ActorPhysicalFinalizationPublicationTest {
    @Test
    @SuppressWarnings("unchecked")
    void temporaryRegistryAbsenceIsNotProofOfPhysicalFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);
            // Inject precisely the registry-absence window that used to
            // certify physical termination without checking a durable ref
            // witness. Restore it before asking the runtime to stop.
            assertSame(cell, registry.remove(actor.id()));
            try {
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                        "missing ActorCell cannot release a live generation");
                assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                        "unknown registry entry is not a terminal barrier");
            } finally {
                assertNull(registry.putIfAbsent(actor.id(), cell));
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @Test
    void blockedTerminalObserverCannotPublishEarlyCodeImageRetirement()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var entered = new CountDownLatch(1);
            var releaseObserver = new CountDownLatch(1);
            actor.getDone().whenCompleteRuntime((ignored, failure) -> {
                entered.countDown();
                try {
                    if (!releaseObserver.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("terminal observer not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("terminal observer interrupted", interrupted);
                }
            });

            var stopFailure = new AtomicReference<Throwable>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            Thread waiter = null;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "actor termination never entered terminal observer");

                // A missing ActorCell after unregisterActor is not itself a
                // physical-completion proof. The on-done callback deliberately
                // blocks this actor's finalization, not a guest Task carrier.
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                        "an incomplete terminal observer must retain old-code pin");

                var waiterEntered = new CountDownLatch(1);
                var waiterFinished = new CountDownLatch(1);
                var terminationObserved = new AtomicBoolean();
                var waiterFailure = new AtomicReference<Throwable>();
                waiter = Thread.ofPlatform().start(() -> {
                    waiterEntered.countDown();
                    try {
                        terminationObserved.set(
                                actor.awaitTermination(5, TimeUnit.SECONDS));
                    } catch (Throwable failure) {
                        waiterFailure.set(failure);
                    } finally {
                        waiterFinished.countDown();
                    }
                });
                assertTrue(waiterEntered.await(5, TimeUnit.SECONDS));
                assertFalse(waiterFinished.await(35, TimeUnit.MILLISECONDS),
                        "awaitTermination cannot pass while completion observers run");

                releaseObserver.countDown();
                assertTrue(waiterFinished.await(5, TimeUnit.SECONDS));
                assertNull(waiterFailure.get());
                assertTrue(terminationObserved.get(),
                        "physical finalization must eventually become observable");
            } finally {
                releaseObserver.countDown();
                stopper.join(5000);
                if (waiter != null) waiter.join(5000);
            }

            assertFalse(stopper.isAlive(), "stopper did not exit");
            assertNull(stopFailure.get(), "physical stop raised an exception");
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertTrue(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
        }
    }
}
