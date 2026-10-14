package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CleanupFailureDiagnosticsTest {
    @Test
    void acceptedCancellationRetainsOnlyBoundedCleanupDiagnostics() {
        CancellationException cancellation = new CancellationException("accepted");
        for (int i = 0; i < 500; i++) {
            CleanupFailureDiagnostics.suppress(
                    cancellation, new IllegalStateException("cleanup " + i));
        }
        assertTrue(cancellation.getSuppressed().length > 0);
        assertTrue(cancellation.getSuppressed().length
                < CleanupFailureDiagnostics.MAX_NODES);
    }

    @Test
    void replacedCleanupErrorsNeverRetainUnboundedSuppressedHistory() {
        Throwable latest = new IllegalStateException("original");
        for (int i = 0; i < 500; i++) {
            Throwable replacement = new IllegalStateException("replacement " + i);
            CleanupFailureDiagnostics.suppress(replacement, latest);
            latest = replacement;
        }
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        pending.add(latest);
        while (!pending.isEmpty()) {
            Throwable next = pending.removeFirst();
            assertNull(seen.put(next, Boolean.TRUE), "cleanup graph must be acyclic");
            assertTrue(seen.size() <= CleanupFailureDiagnostics.MAX_NODES);
            Throwable cause = next.getCause();
            if (cause != null) pending.add(cause);
            for (Throwable suppressed : next.getSuppressed()) pending.add(suppressed);
        }
    }

    @Test
    void diagnosticThatAlreadyReferencesDominantCannotFormCycle() {
        Throwable dominant = new IllegalStateException("dominant");
        Throwable diagnostic = new IllegalStateException("diagnostic");
        diagnostic.addSuppressed(dominant);
        CleanupFailureDiagnostics.suppress(dominant, diagnostic);
        assertEquals(0, dominant.getSuppressed().length);
        CleanupFailureDiagnostics.suppress(dominant, dominant);
        assertEquals(0, dominant.getSuppressed().length);
    }
}
