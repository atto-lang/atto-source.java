package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Concrete actor-dispatch refinement: the ledger never becomes another guest executor. */
final class ActorContinuationLedgerIntegrationTest {
    @Test
    void readyContinuationPrecedesLaterMailAndStillOwnsActorCarrier() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        OresFuture<Integer> future = new OresFuture<>();

        var config = new ActorRuntime.DispatcherConfig(2, 2, 1, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("register".equals(message)) {
                    var target = runtime.captureCurrentContinuationTarget();
                    runtime.enqueueOnCompletion(future, target, (value, failure) -> {
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                        assertNull(failure);
                        assertEquals(42, value);
                        order.add("resume");
                    });
                    registered.countDown();
                    if (!releaseReceive.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("initial receive was not released");
                    }
                } else if ("later".equals(message)) {
                    order.add("later");
                    delivered.countDown();
                    context.self().stop();
                }
            });
            try {
                actor.send("register");
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                actor.send("later");
                assertTrue(future.completeFromRuntime(42));
            } finally {
                releaseReceive.countDown();
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of("resume", "later"), order,
                    "the continuation lane must not be overtaken by later user mail");
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void completionAfterActorStopCannotInvokeCapturedContinuation() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var target = runtime.captureCurrentContinuationTarget();
                runtime.enqueueOnCompletion(future, target, (value, failure) ->
                        callbacks.incrementAndGet());
                registered.countDown();
                context.self().stop();
            });
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            future.completeFromRuntime(1);
            assertEquals(0, callbacks.get(),
                    "retired actor may not be resurrected by a stale Future completion");
        }
    }

    @Test
    void retainedContinuationTargetCannotImplicitlyPinTheEntireActorRuntime()
            throws Exception {
        // Do not assert GC timing. Reflect on the actual handle layout:
        // non-static inner classes would synthesize a strong this$0 runtime
        // reference even when the visible actor-cell field is weak.
        Class<?> targetType = ActorRuntime.ContinuationTarget.class;
        assertTrue(java.lang.reflect.Modifier.isStatic(targetType.getModifiers()),
                "continuation target must not have a synthetic outer runtime owner");
        var runtimeField = targetType.getDeclaredField("originatingRuntime");
        var cellField = targetType.getDeclaredField("capturedCell");
        assertEquals(java.lang.ref.WeakReference.class, runtimeField.getType());
        assertEquals(java.lang.ref.WeakReference.class, cellField.getType());
        for (var field : targetType.getDeclaredFields()) {
            assertNotEquals(ActorRuntime.class, field.getType(),
                    "foreign Future must not hold a strong runtime field");
            assertFalse(field.getName().startsWith("this$"),
                    "non-static inner target would implicitly retain ActorRuntime");
        }
    }

    @Test
    void borrowedSuspendedReceiveCompletionDoesNotRetainRetiredActorOrRuntime()
            throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        OresFuture<Integer> borrowed = new OresFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                runtime.suspendCurrentUserMailboxUntil(borrowed);
                registered.countDown();
            });
            actor.send("suspend");
            assertTrue(registered.await(5, TimeUnit.SECONDS),
                    "the source receive callback must be registered before inspection");

            // The borrowed producer may remain pending after STOP. It must
            // not pin the actor or the runtime through callback capture.
            var waitersField = OresFuture.class.getDeclaredField("waiters");
            waitersField.setAccessible(true);
            Object queue = waitersField.get(borrowed);
            assertInstanceOf(java.util.Collection.class, queue);
            var registrations = (java.util.Collection<?>) queue;
            assertEquals(1, registrations.size());
            Object registration = registrations.iterator().next();
            var callbackField = registration.getClass().getDeclaredField("callback");
            callbackField.setAccessible(true);
            Object callback = callbackField.get(registration);

            int weakCaptures = 0;
            for (var field : callback.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object captured = field.get(callback);
                assertNotSame(runtime, captured,
                        "pending foreign producer must not strongly capture the runtime");
                assertFalse(captured != null
                                && captured.getClass().getName().contains("ActorRuntime$ActorCell"),
                        "pending foreign producer must not strongly capture the actor cell");
                if (captured instanceof java.lang.ref.WeakReference<?>) weakCaptures++;
            }
            assertEquals(2, weakCaptures,
                    "borrowed callback should capture only weak runtime/cell identities");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(borrowed.completeFromRuntime(42),
                    "borrowed producer survives actor termination");
            assertEquals(42, borrowed.join());
            assertTrue(actor.failure().isEmpty(),
                    "late borrowed completion must not resurrect the stopped actor");
        }
    }

    @Test
    void aTargetCannotBeUsedThroughAnotherRuntime() throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ContinuationTarget> target = new AtomicReference<>();
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var actor = owner.<String>spawnShared(() -> (message, context) -> {
                target.set(owner.captureCurrentContinuationTarget());
                captured.countDown();
                context.self().stop();
            });
            actor.send("capture");
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            assertThrows(SecurityException.class, () ->
                    foreign.enqueueOnCompletion(new OresFuture<Integer>(), target.get(),
                            (value, failure) -> {}));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
