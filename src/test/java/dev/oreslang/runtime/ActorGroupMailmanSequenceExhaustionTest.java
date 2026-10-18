package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regression coverage for bounded Mailman sequence identity. */
final class ActorGroupMailmanSequenceExhaustionTest {
    @Test
    void sequenceExhaustionFailsClosedWithoutLeakingFrozenPayloadAccounting()
            throws Exception {
        AtomicReference<Runnable> admitted = new AtomicReference<>();
        AtomicReference<OresFuture<Void>> dispatch = new AtomicReference<>();

        ActorRuntime.ControlDispatcher pausedControl = task -> {
            if (!admitted.compareAndSet(null, task)) {
                throw new AssertionError("only one CONTROL task should be admitted");
            }
            OresFuture<Void> future = new OresFuture<>();
            dispatch.set(future);
            return future;
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                pausedControl)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.installMailman(2, (mail, context) -> { });

            Object state = mailmanState(group);
            Field nextSequence = state.getClass().getDeclaredField("nextSequence");
            nextSequence.setAccessible(true);
            Object value = nextSequence.get(state);
            if (value instanceof java.util.concurrent.atomic.AtomicLong atomic) {
                atomic.set(Long.MAX_VALUE);
            } else {
                nextSequence.setLong(state, Long.MAX_VALUE);
            }

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> exhaustion = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                group.joinCurrent(join);
                group.emit(java.util.List.of(1, 2, 3));
                try {
                    group.emit(java.util.List.of(4, 5, 6));
                } catch (Throwable failure) {
                    exhaustion.set(failure);
                } finally {
                    context.self().stop();
                    done.countDown();
                }
            });

            emitter.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(exhaustion.get());
            assertTrue(exhaustion.get() instanceof IllegalStateException);
            assertEquals(1, group.mailmanOutboxSize(),
                    "the final valid sequence must remain queued exactly once");

            group.close();
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "exhausted admission must not leak the rejected frozen payload reservation");
        }
    }

    private static Object mailmanState(ActorRuntime.ActorGroup group) throws Exception {
        Field stateField = group.getClass().getDeclaredField("mailmanState");
        stateField.setAccessible(true);
        AtomicReference<?> state = (AtomicReference<?>) stateField.get(group);
        Object value = state.get();
        assertNotNull(value);
        return value;
    }
}
