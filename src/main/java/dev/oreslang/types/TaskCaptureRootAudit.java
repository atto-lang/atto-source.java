package dev.oreslang.types;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Canonical-root preflight for source Task capture analysis.
 *
 * <p>The ownership checker supplies identity-stable ownership roots, never
 * source-supplied names, string allocation identifiers or value equality.
 * This only rejects duplicate affine/exclusive capture of the same root
 * within one activation. It is NOT a TaskScope lifetime/Send proof and does
 * not enable guest Task scheduling.
 */
final class TaskCaptureRootAudit {
    private TaskCaptureRootAudit() { }

    record Capture(Object canonicalRoot, String name, boolean affine, boolean write) {
        Capture {
            Objects.requireNonNull(canonicalRoot, "canonical ownership root");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Task capture requires a binding name");
            }
        }
    }

    static void verify(List<Capture> incoming) {
        List<Capture> captures = List.copyOf(Objects.requireNonNull(incoming, "captures"));
        if (captures.size() > 4096) {
            throw new IllegalArgumentException("Task capture analysis budget exceeded");
        }
        IdentityHashMap<Object, Capture> seen = new IdentityHashMap<>();
        for (Capture capture : captures) {
            Capture earlier = seen.putIfAbsent(capture.canonicalRoot(), capture);
            if (earlier != null && (earlier.affine() || capture.affine()
                    || earlier.write() || capture.write())) {
                throw new IllegalArgumentException(
                        "Oreslang ownership error: Task captures '" + earlier.name()
                        + "' and '" + capture.name()
                        + "' through the same affine or writable allocation root");
            }
        }
    }
}
