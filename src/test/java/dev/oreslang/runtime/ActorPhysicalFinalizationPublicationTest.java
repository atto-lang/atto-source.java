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
    @SuppressWarnings("unchecked")
    void terminationWaitSurvivesRegistryRemovalWithoutSpuriousTimeout()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var field = ActorRuntime.class.getDeclaredField("actors");
            field.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) field.get(runtime);
            Object cell = actors.remove(actor.id());
            assertNotNull(cell);

            var started = new CountDownLatch(1);
            var completed = new CountDownLatch(1);
            var observedFinalization = new AtomicBoolean();
            var error = new AtomicReference<Throwable>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    observedFinalization.set(
                            actor.awaitTermination(5, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    error.set(failure);
                } finally {
                    completed.countDown();
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(completed.await(40, TimeUnit.MILLISECONDS),
                        "temporary absence of ActorCell must not shorten a "
                        + "five-second physical finalization wait");
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            } finally {
                // A missing actor is a simulated registry linearization
                // window, not permission to discard its live executor.
                assertNull(actors.putIfAbsent(actor.id(), cell));
                actor.stop();
                waiter.join(5000);
            }
            assertFalse(waiter.isAlive());
            assertNull(error.get());
            assertTrue(observedFinalization.get(),
                    "surviving ActorRef wait must observe actual finalization");
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> booleanCarrier(
            Class<?> runtimeType, String field) throws Exception {
        var declared = runtimeType.getDeclaredField(field);
        declared.setAccessible(true);
        return (ThreadLocal<Boolean>) declared.get(null);
    }

    @Test
    void orphanedCarrierMarkersCannotBlockOnActorFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "an ordinary host may observe an unfinished actor");

            // No active CURRENT actor or scheduler Task is present. Each
            // carrier marker alone must be sufficient to deny a blocking
            // physical join that could stall its executor indefinitely.
            record Marker(Class<?> owner, String field) { }
            var carriers = java.util.List.of(
                    new Marker(ActorRuntime.class, "ACTOR_CARRIER"),
                    new Marker(OresScheduler.class, "SCHEDULER_CARRIER"),
                    new Marker(AsyncRuntime.class, "ASYNC_CARRIER"));
            for (var carrier : carriers) {
                var local = booleanCarrier(carrier.owner(), carrier.field());
                Boolean before = local.get();
                try {
                    local.set(Boolean.TRUE);
                    assertFalse(ActorRuntime.inActorExecution());
                    assertThrows(SecurityException.class,
                            () -> actor.awaitTermination(5, TimeUnit.SECONDS),
                            "carrier-only thread may not join actor: " + carrier.field());
                } finally {
                    if (before == null) local.remove();
                    else local.set(before);
                }
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void recordedPhysicalFinalizerCannotSynchronouslyWaitForItself()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            // Emulate only the terminal callback's owning thread marker.
            // The separate blocked-observer test exercises real completion
            // callbacks and retirement publication across threads.
            var marker = actor.getClass().getDeclaredField(
                    "physicalFinalizationOwnerThread");
            marker.setAccessible(true);
            marker.set(actor, Thread.currentThread());
            try {
                IllegalStateException denied = assertThrows(
                        IllegalStateException.class,
                        () -> actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(denied.getMessage().contains(
                        "cannot join its own physical finalization"));
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            } finally {
                marker.set(actor, null);
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
