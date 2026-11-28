package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CleanupFailureOrderTest {
    @Test void firstOrdinaryFailureRemainsPrimary() {
        var first = new IllegalArgumentException("first");
        var later = new IllegalStateException("cleanup");
        assertSame(first, CleanupFailureOrder.merge(first, later, false, false, false, false, false));
        assertArrayEquals(new Throwable[]{later}, first.getSuppressed());
    }

    @Test void terminalCleanupFailureReplacesOrdinaryFailure() {
        var first = new IllegalArgumentException("first");
        var fatal = new AssertionError("fatal");
        assertSame(fatal, CleanupFailureOrder.merge(first, fatal, false, true, false, false, false));
        assertArrayEquals(new Throwable[]{first}, fatal.getSuppressed());
    }

    @Test void processExitCannotReplacePendingFailure() {
        var first = new IllegalStateException("first");
        var exit = new RuntimeException("exit");
        assertSame(first, CleanupFailureOrder.merge(first, exit, true, true, false, false, false));
        assertArrayEquals(new Throwable[]{exit}, first.getSuppressed());
    }

    @Test void cancellationAndSchedulerFatalFollowExistingResumablePrecedence() {
        var cancelled = new java.util.concurrent.CancellationException("cancelled");
        var panic = new RuntimeException("panic");
        assertSame(cancelled, CleanupFailureOrder.merge(cancelled, panic, false, true, true, true, false));
        var fatal = new RuntimeException("scheduler fatal");
        assertSame(fatal, CleanupFailureOrder.merge(cancelled, fatal, false, true, true, true, true));
        assertArrayEquals(new Throwable[]{cancelled}, fatal.getSuppressed());
    }

    @Test void diagnosticsAreBoundedAndNeverSelfSuppress() {
        var first = new RuntimeException("first");
        for (int i = 0; i < 100; i++) {
            assertSame(first, CleanupFailureOrder.merge(first, new RuntimeException("later"),
                    false, false, false, false, false));
        }
        assertSame(first, CleanupFailureOrder.merge(first, first, false, false, false, false, false));
        assertEquals(64, first.getSuppressed().length);
        assertSame(first, CleanupFailureOrder.merge(null, first, false, false, false, false, false));
    }
}
