package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LogicalTraceStoreTest {
    @Test
    void capacityReuseStaleGenerationAndCrossStoreIsolation() {
        try (LogicalTraceStore<String> execution = new LogicalTraceStore<>(1);
             LogicalTraceStore<String> other = new LogicalTraceStore<>(1)) {
            LogicalTraceStore.Handle first = execution.insert("one");
            assertTrue(first.isPresent());
            assertFalse(execution.insert("two").isPresent(), "capacity must be bounded");
            assertNull(other.load(first), "handles must be bound to a store instance");
            assertEquals("one", execution.load(first));
            assertTrue(execution.release(first));
            assertFalse(execution.release(first), "double release must be rejected");
            LogicalTraceStore.Handle reused = execution.insert("three");
            assertTrue(reused.isPresent());
            assertNotEquals(first, reused, "slot reuse must advance generation");
            assertNull(execution.load(first), "stale handle must remain invalid");
            assertEquals("three", execution.load(reused));
            assertFalse(other.release(reused));
        }
    }

    @Test
    void causalRecordsCarryHandlesNotNativeStackPointers() {
        try (LogicalTraceStore<ExecutionContext> executions = new LogicalTraceStore<>(2);
             LogicalTraceStore<LogicalTraceStore.Continuation> continuations =
                     new LogicalTraceStore<>(2)) {
            LogicalTraceStore.Handle parent = executions.insert(
                    ExecutionContext.actor(1, 2, 1, 0, 3, 0, 0));
            LogicalTraceStore.Continuation record = new LogicalTraceStore.Continuation(
                    parent, LogicalTraceStore.Handle.NONE, 101, 102, 3);
            LogicalTraceStore.Handle child = continuations.insert(record);
            assertEquals(record, continuations.load(child));
            assertEquals(0, executions.load(parent).allocationDomain().owner());
            assertTrue(executions.release(parent));
            assertNull(executions.load(parent), "expired causal parent must fail closed");
            assertTrue(continuations.release(child));
            assertNull(continuations.load(child));
        }
    }

    @Test
    void threadedChurnDoesNotReviveStaleHandles() throws Exception {
        try (LogicalTraceStore<Integer> store = new LogicalTraceStore<>(16)) {
            AtomicInteger failures = new AtomicInteger();
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> runs = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    runs.add(workers.submit(() -> {
                        for (int i = 0; i < 2000; i++) {
                            LogicalTraceStore.Handle h = store.insert(42);
                            if (!h.isPresent() || !Integer.valueOf(42).equals(store.load(h))
                                    || !store.release(h) || store.load(h) != null) {
                                failures.incrementAndGet();
                            }
                        }
                    }));
                }
                for (Future<?> run : runs) run.get(15, TimeUnit.SECONDS);
                assertEquals(0, failures.get());
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    void closeInvalidatesHandles() {
        LogicalTraceStore<Integer> store = new LogicalTraceStore<>(1);
        LogicalTraceStore.Handle handle = store.insert(7);
        store.close();
        assertNull(store.load(handle));
        assertFalse(store.release(handle));
        assertFalse(store.insert(8).isPresent());
        assertThrows(IllegalArgumentException.class, () -> new LogicalTraceStore<>(0));
    }
}
