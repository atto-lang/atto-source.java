package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cross-runtime isolation of scheduler lifecycle, not a security-isolate test.
 *
 * <p>These assertions intentionally do not require global versus per-runtime
 * physical carrier sharing. Both architectures must keep a live runtime's
 * admission and CONTROL service usable when a sibling runtime closes.
 * A carrier pool is never a tenant security boundary.</p>
 */
@Timeout(30)
final class SchedulerCrossRuntimeLifecycleTest {
    private static ActorRuntime newRuntime() {
        return new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
    }

    @Test
    void closingSiblingRuntimeCannotRetireAlreadyActiveActorPool() throws Exception {
        ActorRuntime first = newRuntime();
        try (ActorRuntime second = newRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            var left = first.<String>spawnShared(() -> (message, context) -> {});
            var right = second.<String>spawnShared(
                    () -> (message, context) -> delivered.countDown());

            left.stop();
            first.close();

            right.send("after-sibling-close");
            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "closing one runtime retired another runtime's actor carriers");
            right.stop();
        } finally {
            first.close();
        }
    }

    @Test
    void closingSiblingRuntimeCannotBlockFirstActorAdmission() throws Exception {
        ActorRuntime first = newRuntime();
        try (ActorRuntime second = newRuntime()) {
            var left = first.<String>spawnShared(() -> (message, context) -> {});
            left.stop();
            first.close();

            CountDownLatch delivered = new CountDownLatch(1);
            var right = second.<String>spawnShared(
                    () -> (message, context) -> delivered.countDown());
            right.send("first-admission-after-close");
            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "closing a sibling runtime disabled cold actor admission");
            right.stop();
        } finally {
            first.close();
        }
    }

    @Test
    void closingSiblingVmDoesNotRetireLiveVmControlWorkers() {
        OresVM first = OresVM.create(Runnable::run);
        try (OresVM second = OresVM.create(Runnable::run)) {
            int expected = Integer.getInteger("ores.runtime.control.parallelism", 2);
            assertEquals(expected, first.controlCarrierCount());
            assertEquals(expected, second.controlCarrierCount());
            first.close();

            assertTrue(second.started(), "live VM lost its bootstrap barrier");
            assertEquals(expected, second.controlCarrierCount(),
                    "sibling close stopped live VM CONTROL workers");
            assertNotNull(second.rootScheduler());
        } finally {
            first.close();
        }
    }
}
