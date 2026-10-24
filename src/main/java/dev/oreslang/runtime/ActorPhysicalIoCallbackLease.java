package dev.oreslang.runtime;

import java.nio.channels.CompletionHandler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One physical receipt per asynchronously submitted host NIO operation.
 *
 * The native driver must register this wrapper BEFORE submitting read/write.
 * The CompletionHandler methods prove only that the trusted delegate returned;
 * they do NOT prove that the enclosing provider/native callback stack exited.
 * The driver must separately call providerExited() from a boundary outside
 * that provider invocation. Only both receipts together retire the operation.
 * Closing the descriptor or cancelling a logical Future cannot settle it.
 *
 * This is an internal host API; never expose callbacks or tickets as Oreslang
 * guest values. A raw JDK CompletionHandler callback by itself has no portable
 * post-provider-return hook, so drivers that cannot issue this explicit exit
 * witness must keep the actor quarantined or use a stronger driver-specific
 * physical-quiescence proof.
 */
final class ActorPhysicalIoCallbackLease<V, A> implements CompletionHandler<V, A> {
    private final ActorPhysicalRetirementRegistry.Receipt receipt;
    private final CompletionHandler<? super V, ? super A> delegate;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean delegateReturned = new AtomicBoolean();
    private final AtomicBoolean providerExitWitnessed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();

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
            failed.set(true);
            recordFailurePreservingCause(duplicate);
            throw duplicate;
        }
        try {
            delegate.completed(result, attachment);
        } catch (RuntimeException | Error failure) {
            failed.set(true);
            recordFailurePreservingCause(failure);
            throw failure;
        }
        // Delegate return is only the inner Java callback boundary. The
        // provider/native caller may still own stack/FFI state.
        delegateReturned.set(true);
    }

    @Override
    public void failed(Throwable error, A attachment) {
        if (!claimed.compareAndSet(false, true)) {
            // Duplicate completion while the original delegate is running
            // breaks native at-most-once ownership; quarantine the receipt.
            // A later duplicate cannot undo a previously retired receipt.
            var duplicate = new IllegalStateException("duplicate native I/O callback");
            failed.set(true);
            recordFailurePreservingCause(duplicate);
            throw duplicate;
        }
        // A malformed driver callback cannot leave an unclassified ticket.
        if (error == null) {
            var malformed = new IllegalArgumentException(
                    "native I/O failure callback requires a cause");
            failed.set(true);
            recordFailurePreservingCause(malformed);
            throw malformed;
        }
        try {
            delegate.failed(error, attachment);
        } catch (RuntimeException | Error failure) {
            failed.set(true);
            recordFailurePreservingCause(failure);
            throw failure;
        }
        // I/O failure is a normal logical completion outcome, but the
        // provider/native stack still needs its independent exit witness.
        delegateReturned.set(true);
    }

    /**
     * Trusted driver-owned physical-exit witness. Call only from code outside
     * the provider/native invocation that called completed()/failed(). Calling
     * this before the delegate has returned is a protocol violation and
     * quarantines the actor rather than guessing that native state is gone.
     */
    boolean providerExited() {
        if (failed.get()) return false;
        if (!claimed.get() || !delegateReturned.get()) {
            var early = new IllegalStateException(
                    "provider exit witness arrived before callback delegate returned");
            failed.set(true);
            recordFailurePreservingCause(early);
            throw early;
        }
        if (!providerExitWitnessed.compareAndSet(false, true)) return false;
        return receipt.completeAfterPhysicalRelease();
    }

    /**
     * A submission rejection with unclear kernel ownership is not a
     * successful physical receipt. Quarantine unless a separate
     * driver-specific proof establishes that no native operation was armed.
     */
    void admissionFailedWithoutPhysicalProof(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!claimed.compareAndSet(false, true)) return;
        failed.set(true);
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
