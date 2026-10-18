package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Ordered, fail-closed shutdown of a trusted Oreslang context.
 *
 * Guest-owned frames and async carriers MUST be physically retired before
 * closing providers/actors, and providers/actors MUST be retired before VM
 * or immutable code-image reclamation. This class deliberately depends on
 * no GraalVM classes so the failure policy can be independently tested.
 */
final class OresContextTeardown {
    private OresContextTeardown() { }

    static void close(
            Runnable tasks,
            Runnable async,
            Runnable nativeIo,
            Runnable http,
            Runnable actors,
            Runnable vm,
            Runnable codeImages,
            Runnable garbageCollector,
            Runnable flushOutput) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(async, "async");
        Objects.requireNonNull(nativeIo, "nativeIo");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(vm, "vm");
        Objects.requireNonNull(codeImages, "codeImages");
        Objects.requireNonNull(garbageCollector, "garbageCollector");
        Objects.requireNonNull(flushOutput, "flushOutput");

        // Strong lifecycle barrier. Failed generation retirement is NOT
        // equivalent to successful Task quiescence: quarantine the context.
        runBarrier(tasks,
                "Task scope retirement incomplete; VM and code-image teardown aborted");
        runBarrier(async,
                "async carriers not quiescent; VM and code-image teardown aborted");

        // Independent provider shutdowns all get one attempt even if one
        // fails. Actor teardown is still attempted, but VM/code-image
        // destruction is forbidden unless every provider/actor succeeded.
        Throwable providers = null;
        providers = attempt(nativeIo, providers);
        providers = attempt(http, providers);
        providers = attempt(actors, providers);
        if (providers != null) {
            throw new IllegalStateException(
                    "provider or actor shutdown incomplete; VM and code-image teardown aborted",
                    providers);
        }

        // Never run image or heap reclamation from a finally block if the VM
        // did not actually stop its carriers.
        runBarrier(vm, "VM shutdown incomplete; code-image teardown aborted");
        runBarrier(codeImages,
                "shared code-image retirement incomplete; heap teardown aborted");
        runBarrier(garbageCollector,
                "garbage collector retirement incomplete; output teardown aborted");
        flushOutput.run();
    }

    private static void runBarrier(Runnable operation, String message) {
        try {
            operation.run();
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException(message, failure);
        }
    }

    private static Throwable attempt(Runnable operation, Throwable previous) {
        try {
            operation.run();
        } catch (RuntimeException | Error failure) {
            if (previous == null) return failure;
            if (previous != failure) previous.addSuppressed(failure);
        }
        return previous;
    }
}
