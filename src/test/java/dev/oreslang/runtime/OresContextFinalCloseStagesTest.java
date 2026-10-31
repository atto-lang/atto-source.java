package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed final teardown tests. Invokes the same final-cleanup controller
 * as production OresContext.close(), after the auxiliary/async-drain gate.
 * Does not claim full Polyglot context or native actor-join execution.
 */
final class OresContextFinalCloseStagesTest {
    @Test
    void failedFinalStdoutFlushCannotReleaseSharedCodeOrGcGraph() {
        var finalStages = new OresContext.FinalCloseStages();
        var calls = new ArrayList<String>();
        var flush = new AtomicInteger();
        Runnable flushOutput = () -> {
            calls.add("flush");
            if (flush.incrementAndGet() == 1) {
                throw new IllegalStateException("host-final-flush-failed");
            }
        };
        Runnable closeSharedCode = () -> calls.add("shared-code");
        Runnable closeGc = () -> calls.add("gc");

        var failed = assertThrows(IllegalStateException.class,
                () -> finalStages.closeRemaining(flushOutput, closeSharedCode, closeGc));
        assertEquals("host-final-flush-failed", failed.getMessage());
        assertEquals(List.of("flush"), calls,
                "no physical graph cleanup may run before host flush succeeds");

        assertDoesNotThrow(() -> finalStages.closeRemaining(flushOutput, closeSharedCode, closeGc));
        assertEquals(List.of("flush", "flush", "shared-code", "gc"), calls);
        assertDoesNotThrow(() -> finalStages.closeRemaining(flushOutput, closeSharedCode, closeGc));
        assertEquals(4, calls.size(), "all successful stages are exactly once");
    }

    @Test
    void sharedCodeCleanupFailureDoesNotPrematurelyReclaimGc() {
        var finalStages = new OresContext.FinalCloseStages();
        var calls = new ArrayList<String>();
        var shared = new AtomicInteger();
        Runnable flushOutput = () -> calls.add("flush");
        Runnable closeSharedCode = () -> {
            calls.add("shared-code");
            if (shared.incrementAndGet() == 1) {
                throw new IllegalStateException("shared-code-cleanup-failed");
            }
        };
        Runnable closeGc = () -> calls.add("gc");

        var failed = assertThrows(IllegalStateException.class,
                () -> finalStages.closeRemaining(flushOutput, closeSharedCode, closeGc));
        assertEquals("shared-code-cleanup-failed", failed.getMessage());
        assertEquals(List.of("flush", "shared-code"), calls,
                "GC must remain strongly owned until shared-code cleanup succeeds");
        finalStages.closeRemaining(flushOutput, closeSharedCode, closeGc);
        assertEquals(List.of("flush", "shared-code", "shared-code", "gc"), calls);
    }

    @Test
    void garbageCollectorFailureRetriesOnlyGcCleanup() {
        var finalStages = new OresContext.FinalCloseStages();
        var flush = new AtomicInteger();
        var shared = new AtomicInteger();
        var gc = new AtomicInteger();
        Runnable closeGc = () -> {
            if (gc.incrementAndGet() == 1) throw new IllegalStateException("gc-failed");
        };
        assertThrows(IllegalStateException.class,
                () -> finalStages.closeRemaining(flush::incrementAndGet,
                        shared::incrementAndGet, closeGc));
        assertEquals(1, flush.get());
        assertEquals(1, shared.get());
        assertEquals(1, gc.get());

        finalStages.closeRemaining(flush::incrementAndGet, shared::incrementAndGet, closeGc);
        finalStages.closeRemaining(flush::incrementAndGet, shared::incrementAndGet, closeGc);
        assertEquals(1, flush.get());
        assertEquals(1, shared.get());
        assertEquals(2, gc.get());
    }

    @Test
    void fatalFlushErrorStillBlocksAllResourceReclamation() {
        var finalStages = new OresContext.FinalCloseStages();
        var events = new ArrayList<String>();
        AssertionError fatal = new AssertionError("fatal-host-flush");
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> finalStages.closeRemaining(
                        () -> { events.add("flush"); throw fatal; },
                        () -> events.add("shared-code"),
                        () -> events.add("gc"))));
        assertEquals(List.of("flush"), events);
        finalStages.closeRemaining(
                () -> events.add("retry-flush"),
                () -> events.add("shared-code"),
                () -> events.add("gc"));
        assertEquals(List.of("flush", "retry-flush", "shared-code", "gc"), events);
    }
}
