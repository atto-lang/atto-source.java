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
    @SuppressWarnings("unchecked")
    void actorLocalSharedDebitsRemainOwnedWhenAggregateRefundFails()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            actorsField.setAccessible(true);
            accountingField.setAccessible(true);

            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object actorCell = actors.get(actor.id());
            assertNotNull(actorCell);
            var accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);

            var outputField = actorCell.getClass().getDeclaredField("outputBytes");
            var mailboxField = actorCell.getClass().getDeclaredField(
                    "sharedMailboxBytes");
            var releaseOutput = actorCell.getClass().getDeclaredMethod(
                    "releaseOutput", long.class);
            var releaseMailbox = actorCell.getClass().getDeclaredMethod(
                    "releaseSharedMailbox", long.class);
            outputField.setAccessible(true);
            mailboxField.setAccessible(true);
            releaseOutput.setAccessible(true);
            releaseMailbox.setAccessible(true);

            var output = (java.util.concurrent.atomic.AtomicLong)
                    outputField.get(actorCell);
            var mailbox = (java.util.concurrent.atomic.AtomicLong)
                    mailboxField.get(actorCell);

            output.set(64L);
            accounting.set(0L);
            var outputFailure = assertThrows(
                    InvocationTargetException.class,
                    () -> releaseOutput.invoke(actorCell, 64L));
            assertInstanceOf(IllegalStateException.class, outputFailure.getCause());
            assertEquals(64L, output.get(),
                    "failed aggregate refund must retain exact output debit");

            accounting.set(64L);
            releaseOutput.invoke(actorCell, 64L);
            assertEquals(0L, output.get());
            assertEquals(0L, accounting.get());

            mailbox.set(96L);
            accounting.set(0L);
            var mailboxFailure = assertThrows(
                    InvocationTargetException.class,
                    () -> releaseMailbox.invoke(actorCell, 96L));
            assertInstanceOf(IllegalStateException.class, mailboxFailure.getCause());
            assertEquals(96L, mailbox.get(),
                    "failed aggregate refund must retain exact mailbox debit");

            accounting.set(96L);
            releaseMailbox.invoke(actorCell, 96L);
            assertEquals(0L, mailbox.get());
            assertEquals(0L, accounting.get());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncCellShrinkRefundFailureDoesNotCommitNewValue() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var original = new java.util.ArrayList<Integer>();
            for (int i = 0; i < 64; i++) original.add(i);
            var cell = runtime.syncCell(original);
            long reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            var currentActorField =
                    ActorRuntime.class.getDeclaredField("currentActor");
            var accountingField =
                    ActorRuntime.class.getDeclaredField("sharedMemoryBytes");
            actorsField.setAccessible(true);
            currentActorField.setAccessible(true);
            accountingField.setAccessible(true);

            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object actorCell = actors.get(actor.id());
            assertNotNull(actorCell);
            var currentActor = (ThreadLocal<Object>)
                    currentActorField.get(runtime);
            var accounting = (java.util.concurrent.atomic.AtomicLong)
                    accountingField.get(runtime);

            // Make the shrink refund impossible while preserving the original
            // local reservation. update() must fail before publishing value.
            accounting.set(0L);
            currentActor.set(actorCell);
            try {
                var failure = assertThrows(
                        IllegalStateException.class,
                        () -> cell.update(
                                ignored -> new java.util.ArrayList<>(
                                        java.util.List.of(1))));
                assertTrue(failure.getMessage().contains(
                        "shared actor memory accounting underflow"));
            } finally {
                currentActor.remove();
            }

            assertEquals(64, cell.snapshot().size(),
                    "failed shrink refund must not commit the replacement value");

            // Restore the exact aggregate owner, then close normally.
            accounting.set(reserved);
            cell.close();
            assertEquals(0L, runtime.sharedMemoryBytes());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedRuntimeCloseCannotZeroSharedAggregateBeforePhysicalProof()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            var shared = runtime.shareReadonly(
                    new java.util.ArrayList<>(java.util.List.of(11, 12, 13)));
            long reserved = runtime.sharedMemoryBytes();
            assertTrue(reserved > 0L);

            var faultField = ActorRuntime.class.getDeclaredField(
                    "runtimeCloseBeforeTerminationProofFaultForTesting");
            faultField.setAccessible(true);
            var attempts = new AtomicInteger();
            faultField.set(runtime, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected before runtime physical-close proof");
                }
            });

            var failure = assertThrows(OutOfMemoryError.class, runtime::close);
            assertTrue(failure.getMessage().contains(
                    "runtime physical-close proof"));
            assertEquals(1, attempts.get());
            assertEquals(reserved, runtime.sharedMemoryBytes(),
                    "failed close must retain conservative shared aggregate");
            assertThrows(IllegalStateException.class, shared::value,
                    "resource invalidation may precede the final aggregate reset");

            faultField.set(runtime, null);
            runtime.close();

            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "aggregate reset publishes only after physical close succeeds");
        } finally {
            try {
                var faultField = ActorRuntime.class.getDeclaredField(
                        "runtimeCloseBeforeTerminationProofFaultForTesting");
                faultField.setAccessible(true);
                faultField.set(runtime, null);
            } catch (ReflectiveOperationException ignored) {
                // Best-effort fixture cleanup.
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
