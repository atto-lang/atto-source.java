package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Source marker overflow must stay bounded without changing exceptional effects. */
final class SourceBoundaryTruncationTest {
    private static final class GuestPanic extends RuntimeException { }

    @Test
    void overflowIsVisibleAndBoundedWithoutChangingTheOriginalFailure() {
        var failure = new IllegalStateException("original failure");
        for (int i = 0; i < 1000; i++) {
            assertSame(failure, SourceBoundaryTrace.record(
                    failure, "app/orders.ores", "load", "await"));
        }
        assertEquals(64, failure.getSuppressed().length);
        for (int i = 0; i < 63; i++) {
            assertTrue(failure.getSuppressed()[i].getMessage().contains("load"));
            assertEquals(0, failure.getSuppressed()[i].getStackTrace().length);
        }
        var finalMarker = failure.getSuppressed()[63];
        assertTrue(finalMarker.getMessage().contains("source trace truncated"));
        assertEquals(0, finalMarker.getStackTrace().length);
        assertEquals("original failure", failure.getMessage());
        assertTrue(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
    }


    @Test
    void shortTraceKeepsAllSourceBoundariesWithoutTruncationNotice() {
        var failure = new IllegalStateException("short");
        for (int i = 0; i < 63; i++) {
            SourceBoundaryTrace.record(failure, "short.ores", "call_" + i, "source task");
        }
        assertEquals(63, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[62].getMessage().contains("call_62"));
        for (var marker : failure.getSuppressed()) {
            assertFalse(marker.getMessage().contains("truncated"));
        }
        assertTrue(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
    }

    @Test
    void tracingCannotConcealRealSuppressedTerminalFailures() {
        var failure = new IllegalStateException("ordinary");
        for (int i = 0; i < 1000; i++) {
            SourceBoundaryTrace.record(failure, "code.ores", "fn", "await");
        }
        failure.addSuppressed(new CancellationException("cancelled in cleanup"));
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
        var panic = new GuestPanic();
        SourceBoundaryTrace.record(panic, "code.ores", "fn", "await");
        assertFalse(TrapFailurePolicy.isTrappable(panic, GuestPanic.class));
    }

    @Test
    void concurrentSourceCaptureIsBounded() throws Exception {
        var failure = new IllegalStateException("parallel source trace");
        int workers = 12;
        int iterations = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < iterations; n++) {
                            SourceBoundaryTrace.record(failure, "trace.ores", "task", "await");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(64, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[63].getMessage().contains("truncated"));
        assertTrue(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
    }

    @Test
    void existingCleanupFailureRemainsVisible() {
        var failure = new IllegalStateException("primary");
        failure.addSuppressed(new IllegalArgumentException("cleanup"));
        for (int i = 0; i < 100; i++) {
            SourceBoundaryTrace.record(failure, "trace.ores", "method", "source task");
        }
        assertSame(IllegalArgumentException.class, failure.getSuppressed()[0].getClass());
        assertEquals(64, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[63].getMessage().contains("truncated"));
        assertTrue(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
    }
}
