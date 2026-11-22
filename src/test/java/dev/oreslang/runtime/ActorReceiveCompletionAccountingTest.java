package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime/JVM regression, not independent concurrent guest dispatch.
 * Completion observers must finish before a successful DATA receive returns
 * its serial receive permit; an exception must retain it for fail-stop cleanup.
 */
final class ActorReceiveCompletionAccountingTest {
    @Test
    void synchronousReceiveKeepsPermitUntilDrainObserverHasRun() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch enteredReceive = new CountDownLatch(1);
            CountDownLatch finishReceive = new CountDownLatch(1);
            CountDownLatch observedDrain = new CountDownLatch(1);
            AtomicReference<Throwable> observationFailure = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                enteredReceive.countDown();
                if (!finishReceive.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("receive release timed out");
                }
            });

            actor.send("first");
            assertTrue(enteredReceive.await(5, TimeUnit.SECONDS),
                    "the receive must be running before registering its drain observer");
            OresFuture<Void> drain = runtime.drain(actor);
            drain.whenCompleteRuntime((value, failure) -> {
                try {
                    assertNull(failure, "the first receive must complete successfully");
                    ActorReceiveAdmission.Permit permit = livePermit(runtime, actor.id());
                    assertNotNull(permit,
                            "the receive permit was returned before drain observer accounting");
                    assertFalse(permit.isRetired(),
                            "the receive permit was retired before drain observer accounting");
                } catch (Throwable problem) {
                    observationFailure.set(problem);
                } finally {
                    observedDrain.countDown();
                }
            });
            finishReceive.countDown();

            assertTrue(observedDrain.await(5, TimeUnit.SECONDS),
                    "the synchronous drain observer did not execute");
            if (observationFailure.get() != null) {
                throw new AssertionError("receive completion ordering", observationFailure.get());
            }
            drain.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockedDrainObserverRetainsPermitAndPreventsNextReceiveTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch finishFirst = new CountDownLatch(1);
            CountDownLatch observerEntered = new CountDownLatch(1);
            CountDownLatch finishObserver = new CountDownLatch(1);
            CountDownLatch secondReceived = new CountDownLatch(1);
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("first".equals(message)) {
                    firstEntered.countDown();
                    if (!finishFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first receive timeout");
                    }
                } else if ("second".equals(message)) {
                    secondReceived.countDown();
                }
            });

            try {
                actor.send("first");
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                var drain = runtime.drain(actor);
                drain.whenCompleteRuntime((value, failure) -> {
                    observerEntered.countDown();
                    try {
                        if (failure != null) {
                            throw new AssertionError("unexpected drain failure", failure);
                        }
                        if (!finishObserver.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("drain observer wait timed out");
                        }
                    } catch (Throwable problem) {
                        observerFailure.set(problem);
                    }
                });
                actor.send("second");
                finishFirst.countDown();
                assertTrue(observerEntered.await(5, TimeUnit.SECONDS));
                var active = livePermit(runtime, actor.id());
                assertNotNull(active, "a blocked drain observer still owns the DATA permit");
                assertFalse(active.isRetired(), "the observer cannot return the DATA permit early");
                assertFalse(secondReceived.await(100, TimeUnit.MILLISECONDS),
                        "no later DATA turn may run before the previous observer exits");
                finishObserver.countDown();
                assertTrue(secondReceived.await(5, TimeUnit.SECONDS));
                drain.get(5, TimeUnit.SECONDS);
                assertNull(observerFailure.get(), "drain observer failed");
            } finally {
                finishFirst.countDown();
                finishObserver.countDown();
            }
        }
    }

    @Test
    void lostLivePermitFailsActorInsteadOfSilentlyCompletingReceive() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                // Deliberate host-only invariant fault injection. Guest source
                // cannot access these runtime-owned permit objects.
                var permit = livePermit(runtime, context.self().id());
                assertNotNull(permit);
                permit.close();
            });
            actor.send("invalidate");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            Throwable failure = actor.failure().orElseThrow();
            assertInstanceOf(IllegalStateException.class, failure);
            assertTrue(failure.getMessage().contains(
                    "actor receive completed without a live admission permit"),
                    failure::getMessage);
        }
    }

    @SuppressWarnings("unchecked")
    private static ActorReceiveAdmission.Permit livePermit(
            ActorRuntime runtime, ActorRuntime.ActorId actorId) throws Exception {
        Field actorsField = ActorRuntime.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        Map<Object, Object> actors = (Map<Object, Object>) actorsField.get(runtime);
        Object cell = actors.get(actorId);
        assertNotNull(cell, "the actor cell must remain live while its observer is called");
        Field permitField = cell.getClass().getDeclaredField("activeReceivePermit");
        permitField.setAccessible(true);
        return (ActorReceiveAdmission.Permit) permitField.get(cell);
    }
}
