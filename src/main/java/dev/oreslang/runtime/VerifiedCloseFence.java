package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Exactly-once, fail-closed resource retirement.
 *
 * The first caller performs physical teardown. Concurrent callers wait for
 * the same confirmed result rather than mistaking "closing" for "closed".
 * The retire action must only release admission capacity after teardown
 * completes. Failures are sanitized and retained for all future callers.
 *
 * Never invoke a retiring Context's guest execution monitor here: the guest
 * may be stuck inside that monitor and must remain cancelable.
 */
final class VerifiedCloseFence {
    private final FutureTask<Void> completion;

    VerifiedCloseFence(Runnable retire) {
        Objects.requireNonNull(retire, "retire");
        this.completion = new FutureTask<>(() -> {
            retire.run();
            return null;
        });
    }

    void awaitConfirmedClose(String resource) {
        completion.run();
        try {
            completion.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    resource + " retirement interrupted before close was confirmed");
        } catch (ExecutionException failed) {
            // Deliberately do not attach failure.getCause(): native/polyglot
            // exceptions may embed guest object references and source.
            throw new IllegalStateException(
                    resource + " retirement failed; capacity remains reserved");
        }
    }
}
