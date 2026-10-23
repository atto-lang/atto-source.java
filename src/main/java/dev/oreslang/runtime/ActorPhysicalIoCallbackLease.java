package dev.oreslang.runtime;

import java.nio.channels.CompletionHandler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One physical receipt per asynchronously submitted host NIO operation.
 *
 * The native driver must register this wrapper BEFORE submitting read/write,
 * then pass it as the actual JDK CompletionHandler. The receipt retires only
 * after the underlying native callback AND the trusted delegate return.
 * Closing the descriptor or cancelling a logical Future cannot settle it.
 *
 * This is an internal host API; never expose callbacks or tickets as Oreslang
 * guest values. Drivers with a different completion contract must supply
 * their own physical-quiescence proof instead of using this wrapper.
 */
final class ActorPhysicalIoCallbackLease<V, A> implements CompletionHandler<V, A> {
    private final ActorPhysicalRetirementRegistry.Receipt receipt;
    private final CompletionHandler<? super V, ? super A> delegate;
    private final AtomicBoolean claimed = new AtomicBoolean();

    ActorPhysicalIoCallbackLease(
            ActorPhysicalRetirementRegistry.Receipt receipt,
            CompletionHandler<? super V, ? super A> delegate) {
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void completed(V result, A attachment) {
        if (!claimed.compareAndSet(false, true)) {
            // Duplicate completion while the original delegate is running
            // breaks native at-most-once ownership; quarantine the receipt.
            // A later duplicate cannot undo a previously retired receipt.
            var duplicate = new IllegalStateException("duplicate native I/O callback");
            recordFailurePreservingCause(duplicate);
            throw duplicate;
        }
        try {
            delegate.completed(result, attachment);
        } catch (RuntimeException | Error failure) {
            recordFailurePreservingCause(failure);
            throw failure;
        }
        // Native completion and the user's trusted host callback have both
        // exited. No guest-owned arena pointer remains in the callback.
        receipt.completeAfterPhysicalRelease();
    }

    @Override
    public void failed(Throwable error, A attachment) {
        if (!claimed.compareAndSet(false, true)) {
            // Duplicate completion while the original delegate is running
            // breaks native at-most-once ownership; quarantine the receipt.
            // A later duplicate cannot undo a previously retired receipt.
            var duplicate = new IllegalStateException("duplicate native I/O callback");
            recordFailurePreservingCause(duplicate);
            throw duplicate;
        }
        // A malformed driver callback cannot leave an unclassified ticket.
        if (error == null) {
            var malformed = new IllegalArgumentException(
                    "native I/O failure callback requires a cause");
            recordFailurePreservingCause(malformed);
            throw malformed;
        }
        try {
            delegate.failed(error, attachment);
        } catch (RuntimeException | Error failure) {
            recordFailurePreservingCause(failure);
            throw failure;
        }
        // I/O failure is a normal physical completion outcome. It does not
        // prove an additional descriptor or FFI loan was released.
        receipt.completeAfterPhysicalRelease();
    }

    /**
     * A submission rejection with unclear kernel ownership is not a
     * successful physical receipt. Quarantine unless a separate
     * driver-specific proof establishes that no native operation was armed.
     */
    void admissionFailedWithoutPhysicalProof(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!claimed.compareAndSet(false, true)) return;
        recordFailurePreservingCause(failure);
    }

    /**
     * The registry latches failure before invoking the owner observer. If
     * that observer throws, preserve the real delegate/submission exception;
     * the extra diagnostic is already suppressed on the registry failure.
     */
    private void recordFailurePreservingCause(Throwable original) {
        try {
            receipt.failPhysicalRelease(original);
        } catch (RuntimeException | Error observerFailure) {
            rethrowFatal(observerFailure);
        }
    }

    @SuppressWarnings("removal") // ThreadDeath is deprecated but still fatal.
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (failure instanceof LinkageError fatal) throw fatal;
    }
}
