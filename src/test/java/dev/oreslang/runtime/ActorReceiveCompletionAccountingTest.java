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
