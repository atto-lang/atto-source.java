package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/**
 * Bounded diagnostic-only suppression for source cleanup paths.
 *
 * <p>Guest-controlled chains of failing defer/finally callbacks must not
 * retain a linearly growing graph of suppressed exceptions. Failure priority
 * is determined by the caller; this helper never changes the dominant failure.
 * Host fatal failures must still be rethrown by the caller's precedence rule.</p>
 */
public final class CleanupFailureDiagnostics {
    public static final int MAX_NODES = 64;

    private CleanupFailureDiagnostics() { }

    public static void suppress(Throwable dominant, Throwable diagnostic) {
        if (dominant == null || diagnostic == null || dominant == diagnostic) return;
        synchronized (dominant) {
            if (dominant.getSuppressed().length >= MAX_NODES - 1) return;
            // Disallow both cycles and unbounded chains of distinct cleanup
            // failures. Traversal is identity based and bounded independently
            // of the number of guest cleanup callbacks.
            ArrayDeque<Throwable> pending = new ArrayDeque<>();
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            pending.add(dominant);
            pending.add(diagnostic);
            while (!pending.isEmpty()) {
                if (seen.size() >= MAX_NODES) return;
                Throwable next = pending.removeFirst();
                if (seen.put(next, Boolean.TRUE) != null) continue;
                Throwable cause = next.getCause();
                if (cause != null) pending.addLast(cause);
                for (Throwable child : next.getSuppressed()) pending.addLast(child);
                if (pending.size() + seen.size() >= MAX_NODES) return;
            }
            // A diagnostic that points back to the dominant failure would
            // introduce a cycle, even if the combined graph is small.
            if (references(diagnostic, dominant)) return;
            dominant.addSuppressed(diagnostic);
        }
    }

    private static boolean references(Throwable source, Throwable target) {
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            Throwable next = pending.removeFirst();
            if (next == target) return true;
            if (seen.put(next, Boolean.TRUE) != null) continue;
            Throwable cause = next.getCause();
            if (cause != null) pending.addLast(cause);
            for (Throwable child : next.getSuppressed()) pending.addLast(child);
        }
        return false;
    }
}
