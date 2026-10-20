package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only reflection probes for the supervisor's shared aggregate budget. */
@Timeout(20)
final class ActorSharedRuntimeAccountingTest {

    private static Method operation(String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = ActorRuntime.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    @Test
    void oversizedDuplicateAndNegativeReleasesNeverAlterTheBudget() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = operation("reserveSharedRuntimeBytes", long.class, String.class);
            Method release = operation("releaseSharedRuntimeBytes", long.class);
            assertEquals(0L, runtime.sharedMemoryBytes());
            reserve.invoke(runtime, 64L, "shared budget regression");
            assertEquals(64L, runtime.sharedMemoryBytes());

            InvocationTargetException oversized = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, 65L));
            assertInstanceOf(IllegalStateException.class, oversized.getCause());
            assertTrue(oversized.getCause().getMessage().contains(
                    "shared actor memory accounting underflow"));
            assertEquals(64L, runtime.sharedMemoryBytes(),
                    "an over-release must not discard a valid outstanding reservation");

            InvocationTargetException negative = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, -1L));
            assertInstanceOf(IllegalArgumentException.class, negative.getCause());
            assertEquals(64L, runtime.sharedMemoryBytes());

            release.invoke(runtime, 64L);
            assertEquals(0L, runtime.sharedMemoryBytes());
            InvocationTargetException duplicate = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, 64L));
            assertInstanceOf(IllegalStateException.class, duplicate.getCause());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void concurrentDuplicateReleasesHaveExactlyOneWinner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = operation("reserveSharedRuntimeBytes", long.class, String.class);
            Method release = operation("releaseSharedRuntimeBytes", long.class);
            reserve.invoke(runtime, 64L, "concurrent release regression");

            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Runnable contender = () -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    release.invoke(runtime, 64L);
                    accepted.incrementAndGet();
                } catch (InvocationTargetException ex) {
                    if (ex.getCause() instanceof IllegalStateException) {
                        rejected.incrementAndGet();
                    } else {
                        throw new AssertionError("unexpected aggregate release failure", ex.getCause());
                    }
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            };

            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(contender);
                var b = executor.submit(contender);
                start.countDown();
                a.get(5, TimeUnit.SECONDS);
                b.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void sharedReadonlyRuntimeCloseRetainsExactDebitForRetry() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        long reserved = 0L;
        java.util.concurrent.atomic.AtomicLong accounting = null;
        Object shared = null;
        java.lang.reflect.Field reservedField = null;
        try {
            var value = runtime.shareReadonly(
                    new java.util.ArrayList<>(java.util.List.of(1, 2, 3, 4)));
            shared = value;
            reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            accountingField.setAccessible(true);
            accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);

            reservedField = value.getClass().getDeclaredField("reservedBytes");
            var cleanupField =
                    value.getClass().getDeclaredField("sharedCleanupComplete");
            var closedField =
                    value.getClass().getDeclaredField("sharedClosed");
            var valuesField =
                    ActorRuntime.class.getDeclaredField("sharedValues");
            reservedField.setAccessible(true);
            cleanupField.setAccessible(true);
            closedField.setAccessible(true);
            valuesField.setAccessible(true);

            var values = (java.util.Set<Object>) valuesField.get(runtime);
            var closed = (java.util.concurrent.atomic.AtomicBoolean)
                    closedField.get(value);

            // Corrupt only the aggregate witness. The Shared value still owns
            // its exact reservation and runtime close must fail closed.
            accounting.set(0L);
            var failure = assertThrows(IllegalStateException.class, runtime::close);
            assertTrue(failure.getMessage().contains(
                    "shared actor memory accounting underflow"));

            assertTrue(closed.get(),
                    "guest observation must be sealed after close begins");
            assertFalse(cleanupField.getBoolean(value),
                    "admission seal cannot forge shared cleanup completion");
            assertEquals(reserved, reservedField.getLong(value),
                    "failed aggregate refund must retain the exact debit");
            assertTrue(values.contains(value),
                    "runtime must retain the Shared owner for retry");
            assertThrows(IllegalStateException.class, value::value,
                    "sealed Shared value cannot be observed while cleanup is pending");

            // Repair only the trusted aggregate witness. A second runtime close
            // must refund the same retained debit exactly once.
            accounting.set(reserved);
            runtime.close();

            assertTrue(cleanupField.getBoolean(value));
            assertEquals(0L, reservedField.getLong(value));
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertFalse(values.contains(value));
        } finally {
            // If an assertion aborts after the synthetic corruption, repair the
            // aggregate witness from the still-retained exact owner before the
            // cleanup close so the fixture cannot leak runtime resources.
            if (accounting != null && shared != null && reservedField != null) {
                try {
                    long outstanding = reservedField.getLong(shared);
                    if (outstanding > accounting.get()) accounting.set(outstanding);
                } catch (ReflectiveOperationException ignored) {
                    // Best effort only for test-fixture cleanup.
                }
            }
            runtime.close();
        }
    }

    @Test
    void concurrentBalancedReservationsDoNotLoseRuntimeAccounting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = operation(
                    "reserveSharedRuntimeBytes", long.class, String.class);
            Method release = operation("releaseSharedRuntimeBytes", long.class);
            var pool = Executors.newFixedThreadPool(6);
            try {
                java.util.ArrayList<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
                for (int carrier = 0; carrier < 6; carrier++) {
                    tasks.add(pool.submit(() -> {
                        try {
                            for (int i = 0; i < 500; i++) {
                                reserve.invoke(runtime, 1L, "concurrent accounting");
                                release.invoke(runtime, 1L);
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError("balanced reservation failed", e);
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(0L, runtime.sharedMemoryBytes(),
                        "concurrent reserve/release pairs must fully balance");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
