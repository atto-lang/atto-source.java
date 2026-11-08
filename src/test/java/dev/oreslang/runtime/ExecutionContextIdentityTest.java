package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Regression coverage for the shared, non-wrapping logical identity allocator. */
final class ExecutionContextIdentityTest {
    @Test
    void identityAllocationIsUniqueUnderContention() throws Exception {
        AtomicLong counter = new AtomicLong();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 4_000; i++) {
                calls.add(() -> ExecutionContext.nextPositive(counter, "test execution"));
            }
            var results = executor.invokeAll(calls);
            Set<Long> identities = new HashSet<>();
            for (var result : results) {
                long identity = result.get();
                assertTrue(identity > 0);
                identities.add(identity);
            }
            assertEquals(calls.size(), identities.size(), "concurrent allocations must never collide");
            assertEquals((long) calls.size(), counter.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exhaustionSaturatesPermanentlyWithoutWrapping() {
        AtomicLong counter = new AtomicLong(Long.MAX_VALUE - 1);

        assertEquals(Long.MAX_VALUE,
                ExecutionContext.nextPositive(counter, "test execution"));
        assertThrows(IllegalStateException.class,
                () -> ExecutionContext.nextPositive(counter, "test execution"));
        assertThrows(IllegalStateException.class,
                () -> ExecutionContext.nextPositive(counter, "test execution"));

        assertEquals(Long.MAX_VALUE, counter.get(),
                "failed allocations must not mutate or wrap the exhausted counter");
    }

    @Test
    void corruptNegativeCounterFailsClosedWithoutMutation() {
        AtomicLong counter = new AtomicLong(Long.MIN_VALUE);

        assertThrows(IllegalStateException.class,
                () -> ExecutionContext.nextPositive(counter, "test execution"));
        assertEquals(Long.MIN_VALUE, counter.get());
    }
}
