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
    void hostFatalFailuresDoNotAllocateSourceTraceMarkers() {
        for (Error fatal : new Error[] {
                new OutOfMemoryError("OOM"),
                new StackOverflowError("stack exhausted"),
                new InternalError("VM broken"),
                new LinkageError("class loader invalid"),
                new ThreadDeath()
        }) {
            assertSame(fatal, SourceBoundaryTrace.record(
                    fatal, "private.ores", "critical", "resumed source task"));
            assertEquals(0, fatal.getSuppressed().length,
                    "Do not allocate extra source metadata during a host-fatal failure");
        }
        // Ordinary guest panic markers remain traceable; only fatal *host*
        // failures take the nonallocating emergency path.
        var guestPanic = new GuestPanic();
        assertSame(guestPanic, SourceBoundaryTrace.record(
                guestPanic, "guest.ores", "critical", "source task"));
        assertEquals(1, guestPanic.getSuppressed().length);
        assertFalse(TrapFailurePolicy.isTrappable(guestPanic, GuestPanic.class));
    }

    @Test
    void diagnosticAnnotationsDoNotChangeOriginalEffectClassification() {
        for (int markers : new int[] {0, 1, 62, 63, 64, 1000}) {
            for (int outcome = 0; outcome < 5; outcome++) {
                RuntimeException failure = switch (outcome) {
                    case 0 -> new IllegalStateException("ordinary");
                    case 1 -> new SecurityException("denied");
                    case 2 -> new java.util.concurrent.CompletionException(
                            new IllegalArgumentException("wrapped ordinary"));
                    case 3 -> new java.util.concurrent.CompletionException(
                            new CancellationException("wrapped cancellation"));
                    default -> new GuestPanic();
                };
                boolean beforeTrap = TrapFailurePolicy.isTrappable(failure, GuestPanic.class);
                boolean beforeCatch = TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class);
                for (int i = 0; i < markers; i++) {
                    SourceBoundaryTrace.record(failure, "source.ores", "entry", "await");
                }
                assertEquals(beforeTrap, TrapFailurePolicy.isTrappable(
                        failure, GuestPanic.class), "trap classification changed with markers=" + markers);
                assertEquals(beforeCatch, TrapFailurePolicy.isGuestCatchable(
                        failure, GuestPanic.class), "catch classification changed with markers=" + markers);
                assertTrue(failure.getSuppressed().length <= 64);
            }
        }
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

    @Test
    void maxLabelLengthsIncludeEllipsisAndNeverSplitSurrogatePairs() {
        var failure = new IllegalStateException("bounds");
        SourceBoundaryTrace.record(failure,
                "c".repeat(161), "😀".repeat(49), "b".repeat(65));
        String message = failure.getSuppressed()[0].getMessage();
        assertTrue(message.startsWith("ores "));
        int codeUnitStart = message.indexOf(" [");
        assertTrue(codeUnitStart > 5);
        String callParts = message.substring(5, codeUnitStart);
        int separator = callParts.indexOf(' ');
        assertTrue(separator > 0);
        String boundary = callParts.substring(0, separator);
        String callable = callParts.substring(separator + 1);
        String codeUnit = message.substring(codeUnitStart + 2, message.length() - 1);

        assertTrue(boundary.length() <= 64, message);
        assertTrue(callable.length() <= 96, message);
        assertTrue(codeUnit.length() <= 160, message);
        assertTrue(boundary.endsWith("…"), message);
        assertTrue(callable.endsWith("…"), message);
        assertTrue(codeUnit.endsWith("…"), message);
        for (int i = 0; i < message.length(); i++) {
            char ch = message.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertTrue(i + 1 < message.length()
                        && Character.isLowSurrogate(message.charAt(i + 1)));
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(ch));
            }
        }
    }

}
