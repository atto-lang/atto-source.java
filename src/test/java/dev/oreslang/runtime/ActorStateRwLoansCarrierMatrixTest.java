package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JVM-carrier contention against the candidate task-bound state-region
 * admission gate. This does NOT establish Oreslang guest compiler/lowering
 * parity or Graal Native Image allocation guarantees.
 */
final class ActorStateRwLoansCarrierMatrixTest {
    private static final int[] THREAD_COUNTS = {3, 7, 15};
    private static final int ITERATIONS = 80;

    private static ExecutionContext actor(int threadIndex) {
        // All executions belong to the same actor allocation incarnation,
        // but have distinct generation-fenced execution handles.
        return ExecutionContext.actorBound(
                7, 10_000L + threadIndex,
                20_000L + threadIndex, 1,
                9, 3, 11,
                0, 0);
    }

    @Test
    void concurrentReadersCannotBeOvertakenByWaitingWriterAtThreeSevenFifteenCarriers()
            throws Exception {
        for (int count : THREAD_COUNTS) {
            ExecutorService carriers = Executors.newFixedThreadPool(count);
            CountDownLatch allReading = new CountDownLatch(count);
            CountDownLatch releaseReaders = new CountDownLatch(1);
            try (var loans = new ActorStateRwLoans(count + 2)) {
                List<Future<?>> readers = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    readers.add(carriers.submit(() -> {
                        try (var loan = loans.acquireReadAsync(actor(index))
                                .get(15, TimeUnit.SECONDS)) {
                            assertTrue(loan.heldBy(actor(index)));
                            allReading.countDown();
                            assertTrue(releaseReaders.await(15, TimeUnit.SECONDS),
                                    "reader cohort must be explicitly released");
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    }));
                }
                assertTrue(allReading.await(15, TimeUnit.SECONDS),
                        "all " + count + " platform threads must hold reads at once");
                assertEquals(count, loans.activeReaderCount());
                assertEquals(0, loans.activeWriterCount());

                var waitingWriter = loans.acquireWriteAsync(actor(count));
                var lateReader = loans.acquireReadAsync(actor(count + 1));
                assertFalse(waitingWriter.isDone());
                assertFalse(lateReader.isDone(),
                        "new reader must not bypass waiting writer");

                releaseReaders.countDown();
                for (Future<?> reader : readers) reader.get(20, TimeUnit.SECONDS);
                try (var writer = waitingWriter.get(15, TimeUnit.SECONDS)) {
                    assertEquals(0, loans.activeReaderCount());
                    assertEquals(1, loans.activeWriterCount());
                    assertFalse(lateReader.isDone(),
                            "late reader cannot overlap exclusive writer");
                }
                try (var lastReader = lateReader.get(15, TimeUnit.SECONDS)) {
                    assertEquals(1, loans.activeReaderCount());
                    assertEquals(0, loans.activeWriterCount());
                }
                assertEquals(0, loans.pendingCount());
            } finally {
                releaseReaders.countDown();
                carriers.shutdownNow();
                assertTrue(carriers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void mixedReadWriteContentionRetainsExclusiveMutationAtThreeSevenFifteenCarriers()
            throws Exception {
        for (int count : THREAD_COUNTS) {
            ExecutorService carriers = Executors.newFixedThreadPool(count);
            CountDownLatch start = new CountDownLatch(1);
            try (var loans = new ActorStateRwLoans(count + 2)) {
                var activeReaders = new AtomicInteger();
                var activeWriters = new AtomicInteger();
                var completedWrites = new AtomicInteger();
                // Intentionally plain mutable heap data: the admission gate,
                // NOT the Java counter implementation, must serialize writers.
                final int[] protectedValue = {0};
                List<Future<?>> tasks = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    final int index = i;
                    tasks.add(carriers.submit(() -> {
                        try {
                            assertTrue(start.await(15, TimeUnit.SECONDS));
                            var execution = actor(index);
                            for (int step = 0; step < ITERATIONS; step++) {
                                boolean write = (step + index) % 4 == 0;
                                var requested = write
                                        ? loans.acquireWriteAsync(execution)
                                        : loans.acquireReadAsync(execution);
                                try (var loan = requested.get(15, TimeUnit.SECONDS)) {
                                    assertTrue(loan.heldBy(execution));
                                    assertFalse(loan.readyToSuspend(execution));
                                    if (write) {
                                        assertEquals(1, activeWriters.incrementAndGet());
                                        try {
                                            assertEquals(0, activeReaders.get(),
                                                    "writer must exclude every reader");
                                            int prior = protectedValue[0];
                                            Thread.yield();
                                            protectedValue[0] = prior + 1;
                                            completedWrites.incrementAndGet();
                                            assertEquals(0, activeReaders.get());
                                        } finally {
                                            activeWriters.decrementAndGet();
                                        }
                                    } else {
                                        activeReaders.incrementAndGet();
                                        try {
                                            assertEquals(0, activeWriters.get(),
                                                    "reader must not overlap a writer");
                                            int observed = protectedValue[0];
                                            Thread.yield();
                                            assertTrue(observed <= protectedValue[0],
                                                    "protected value cannot roll back");
                                            assertEquals(0, activeWriters.get());
                                        } finally {
                                            activeReaders.decrementAndGet();
                                        }
                                    }
                                }
                            }
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    }));
                }

                start.countDown();
                for (Future<?> task : tasks) task.get(60, TimeUnit.SECONDS);
                assertEquals(completedWrites.get(), protectedValue[0],
                        "each writer must commit exactly once without a lost update");
                assertEquals(0, activeReaders.get());
                assertEquals(0, activeWriters.get());
                assertEquals(0, loans.activeReaderCount());
                assertEquals(0, loans.activeWriterCount());
                assertEquals(0, loans.pendingCount());
            } finally {
                start.countDown();
                carriers.shutdownNow();
                assertTrue(carriers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void physicalRetirementWaitsForEveryReaderAtThreeSevenFifteenCarriers()
            throws Exception {
        for (int count : THREAD_COUNTS) {
            ExecutorService carriers = Executors.newFixedThreadPool(count);
            CountDownLatch allReading = new CountDownLatch(count);
            CountDownLatch releaseReaders = new CountDownLatch(1);
            AtomicInteger retirementWakeups = new AtomicInteger();
            try (var loans = new ActorStateRwLoans(
                    count + 1, retirementWakeups::incrementAndGet)) {
                List<Future<?>> readers = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    readers.add(carriers.submit(() -> {
                        try (var loan = loans.acquireReadAsync(actor(index))
                                .get(15, TimeUnit.SECONDS)) {
                            allReading.countDown();
                            if (!releaseReaders.await(15, TimeUnit.SECONDS)) {
                                throw new AssertionError("release latch timed out");
                            }
                            assertFalse(loan.heldBy(actor(index)),
                                    "logical stop must revoke state access before physical release");
                            assertFalse(loan.readyToSuspend(actor(index)),
                                    "logical stop cannot release a physical suspension fence");
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    }));
                }
                assertTrue(allReading.await(15, TimeUnit.SECONDS));
                assertEquals(count, loans.activeReaderCount());
                loans.close();
                assertFalse(loans.physicallyRetired());
                assertEquals(0, retirementWakeups.get());
                assertTrue(loans.acquireReadAsync(actor(count + 1))
                        .isCompletedExceptionally(),
                        "retired region must reject new admission");

                releaseReaders.countDown();
                for (Future<?> reader : readers) reader.get(20, TimeUnit.SECONDS);
                assertTrue(loans.physicallyRetired(),
                        "all physical readers must release before arena retirement");
                assertEquals(1, retirementWakeups.get(),
                        "exactly one retirement wakeup after final receipt");
            } finally {
                releaseReaders.countDown();
                carriers.shutdownNow();
                assertTrue(carriers.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }
}
