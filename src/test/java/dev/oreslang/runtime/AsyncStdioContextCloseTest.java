package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real polyglot-disposal regression. These tests intentionally exercise
 * OresContext.close(), not a model of its shutdown ordering or a writer helper.
 * They require executable GraalVM/JUnit evidence before any promotion claim.
 */
final class AsyncStdioContextCloseTest {
    @Test
    void concurrentCloseCannotReleaseQuarantineBeforeFailedDrainRetry() throws Exception {
        Set<OresContext> prior = OresContext.quarantinedContextSnapshot();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IOException("blocked host sink timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        };

        Source guest = Source.newBuilder(OresLanguage.ID,
                        "pub routine main(): void { stdio.stdout.log_async(\"held\"); }",
                        "stdio-quarantine-retry.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        Context polyglot = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(blocked)
                .build();
        CompletableFuture<RuntimeException> firstClose = null;
        OresContext quarantined = null;
        try {
            polyglot.eval(guest);
            assertTrue(entered.await(3, TimeUnit.SECONDS),
                    "async writer did not reach the blocked host output");
            firstClose = CompletableFuture.supplyAsync(() -> {
                try {
                    polyglot.close();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });

            // Retention must begin before the physical retirement and writer
            // join. Observe the actual runtime object, not a synthetic state.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (quarantined == null && System.nanoTime() < deadline) {
                Set<OresContext> newlyPinned =
                        new HashSet<>(OresContext.quarantinedContextSnapshot());
                newlyPinned.removeAll(prior);
                assertTrue(newlyPinned.size() <= 1,
                        "unrelated contexts entered quarantine during this isolated test");
                if (!newlyPinned.isEmpty()) quarantined = newlyPinned.iterator().next();
                else Thread.sleep(10);
            }
            assertNotNull(quarantined,
                    "close never pinned the real OresContext for failed-retirement safety");
            assertFalse(firstClose.isDone(),
                    "first close unexpectedly finished while host writer was blocked");

            // A second closer must fail quickly without calling VM/GC cleanup,
            // joining a stuck writer, or removing the first close's pin.
            OresContext pinned = quarantined;
            RuntimeException overlap = CompletableFuture.supplyAsync(() -> {
                try {
                    pinned.close();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            }).get(2, TimeUnit.SECONDS);
            assertNotNull(overlap, "overlapping context close must be rejected");
            assertTrue(String.valueOf(overlap.getMessage()).contains("already in progress"),
                    () -> "unexpected concurrent-close outcome: " + overlap);
            assertTrue(OresContext.quarantinedContextSnapshot().contains(pinned),
                    "rejected overlapping close must not clear the quarantine");

            RuntimeException firstFailure = firstClose.get(12, TimeUnit.SECONDS);
            assertNotNull(firstFailure, "a blocked host sink must fail the drain deadline");
            assertTrue(hasCause(firstFailure, "logging drain incomplete")
                            || hasCause(firstFailure, "shutdown deadline"),
                    () -> "unexpected failed-close stage: " + firstFailure);
            assertTrue(OresContext.quarantinedContextSnapshot().contains(pinned),
                    "timed-out async drain must retain the context pin");
            assertThrows(IllegalStateException.class, pinned.asyncStdio()::flushAsync,
                    "failed-close drain must reject newly admitted barriers");
            assertThrows(IllegalStateException.class, () -> pinned.asyncStdio().logAsync("late"),
                    "failed-close drain must reject newly admitted records");
        } finally {
            release.countDown();
            if (firstClose != null) firstClose.get(8, TimeUnit.SECONDS);
            else polyglot.close();
        }

        // Retry the same runtime object after the sink is released. Polyglot
        // Context.close() itself may be terminal after throwing, so use the
        // retained OresContext directly; no fresh context can prove this.
        assertNotNull(quarantined);
        quarantined.close();
        assertFalse(OresContext.quarantinedContextSnapshot().contains(quarantined),
                "verified successful retry must remove the strong quarantine pin");
        quarantined.close(); // completed close must remain idempotent
    }

    private static boolean hasCause(Throwable failure, String expected) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (String.valueOf(cursor.getMessage()).contains(expected)) return true;
        }
        return false;
    }
}
