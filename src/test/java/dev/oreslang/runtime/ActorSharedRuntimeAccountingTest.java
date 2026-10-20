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
    void sharedReadonlyCleanupRetainsExactDebitForRetry() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        java.util.concurrent.atomic.AtomicLong accounting = null;
        java.lang.reflect.Field reservedField = null;
        Object shared = null;
        try {
            var value = runtime.shareReadonly(
                    new java.util.ArrayList<>(java.util.List.of(1, 2, 3, 4)));
            shared = value;
            long reserved = runtime.sharedMemoryBytes();
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
            var closeMethod = value.getClass().getDeclaredMethod(
                    "closeFromRuntime");
            reservedField.setAccessible(true);
            cleanupField.setAccessible(true);
            closedField.setAccessible(true);
            valuesField.setAccessible(true);
            closeMethod.setAccessible(true);

            var values = (java.util.Set<Object>) valuesField.get(runtime);
            var closed = (java.util.concurrent.atomic.AtomicBoolean)
                    closedField.get(value);

            accounting.set(0L);
            var failure = assertThrows(
                    InvocationTargetException.class,
                    () -> closeMethod.invoke(value));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains(
                    "shared actor memory accounting underflow"));

            assertTrue(closed.get());
            assertFalse(cleanupField.getBoolean(value));
            assertEquals(reserved, reservedField.getLong(value),
                    "failed cleanup must retain the exact aggregate debit");
            assertTrue(values.contains(value),
                    "internal cleanup owner must remain registered for retry");
            assertThrows(IllegalStateException.class, value::value);

            accounting.set(reserved);
            closeMethod.invoke(value);

            assertTrue(cleanupField.getBoolean(value));
            assertEquals(0L, reservedField.getLong(value));
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertFalse(values.contains(value));
        } finally {
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
    @SuppressWarnings("unchecked")
    void syncCellCloseRetainsExactDebitForRetry() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        java.util.concurrent.atomic.AtomicLong accounting = null;
        java.lang.reflect.Field reservedField = null;
        Object rawCell = null;
        try {
            var cell = runtime.syncCell(
                    new java.util.ArrayList<>(java.util.List.of(7, 8, 9)));
            rawCell = cell;
            long reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            accountingField.setAccessible(true);
            accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);

            reservedField = cell.getClass().getDeclaredField("reservedBytes");
            var cleanupField =
                    cell.getClass().getDeclaredField("cellCleanupComplete");
            var cellsField =
                    ActorRuntime.class.getDeclaredField("syncCells");
            reservedField.setAccessible(true);
            cleanupField.setAccessible(true);
            cellsField.setAccessible(true);
            var cells = (java.util.Set<Object>) cellsField.get(runtime);

            accounting.set(0L);
            var failure = assertThrows(IllegalStateException.class, cell::close);
            assertTrue(failure.getMessage().contains(
                    "shared actor memory accounting underflow"));

            assertTrue(cell.closed(),
                    "failed close must still revoke SyncCell observation");
            assertFalse(cleanupField.getBoolean(cell),
                    "closed flag alone cannot certify resource cleanup");
            assertEquals(reserved, reservedField.getLong(cell),
                    "failed refund must retain the exact debit");
            assertTrue(cells.contains(cell),
                    "runtime must retain the exact SyncCell retry owner");
            assertThrows(IllegalStateException.class, cell::snapshot);

            accounting.set(reserved);
            cell.close();
            cell.close();

            assertTrue(cleanupField.getBoolean(cell));
            assertEquals(0L, reservedField.getLong(cell));
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertFalse(cells.contains(cell));
        } finally {
            if (accounting != null && rawCell != null && reservedField != null) {
                try {
                    long outstanding = reservedField.getLong(rawCell);
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
