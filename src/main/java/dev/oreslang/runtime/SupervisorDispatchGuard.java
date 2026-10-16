package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CONTROL turns must not execute while their admission lock is held.
 *
 * A synchronous/direct executor would otherwise run supervisor guest policy
 * inline on a child carrier (or under a supervisor mailbox monitor). Reject
 * it without ever running the callback. A different worker can execute as
 * soon as the admission monitor becomes free.
 */
final class SupervisorDispatchGuard {
    private SupervisorDispatchGuard() { }

    static void enqueue(Executor dispatcher, Runnable turn) {
        Objects.requireNonNull(dispatcher);
        Objects.requireNonNull(turn);
        Thread submittingThread = Thread.currentThread();
        AtomicBoolean submitting = new AtomicBoolean(true);
        AtomicBoolean attemptedInline = new AtomicBoolean();
        try {
            dispatcher.execute(() -> {
                if (submitting.get()
                        && Thread.currentThread() == submittingThread) {
                    attemptedInline.set(true);
                    return;
                }
                turn.run();
            });
        } finally {
            submitting.set(false);
        }
        if (attemptedInline.get()) {
            throw new RejectedExecutionException(
                    "supervisor CONTROL executor ran inline during admission");
        }
    }
}
