package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted HOST-only task-owned transferable lease cleanup.
 *
 * <p>This is not a generic Java monitor/ReentrantLock unlock mechanism:
 * thread-affine locks MUST be unwound on their owning carrier by compiler
 * emitted try/finally, with suspension forbidden while those guards live.
 * Only nonblocking, task-identity-owned lease tokens whose release is
 * explicitly valid on ANY host notification thread may be registered.
 * Never run untrusted guest callbacks from a release implementation.</p>
 *
 * <p>The runtime retires any remaining registered leases in reverse
 * acquisition order after the parent Task and owned descendants are
 * physically quiescent, before publishing TaskHandle.getDone().</p>
 */
public final class TaskOwnedLeases {
    public static final int MAX_LEASES_PER_TASK = 4_096;

    private TaskOwnedLeases() { }

    /** Verified nonblocking, thread-independent token-release capability. */
    @FunctionalInterface
    public interface TransferableRelease {
        void release();
    }

    /** Exact-once handle. An explicit early close requires its owning Task turn. */
    public interface Lease extends AutoCloseable {
        @Override void close();
    }

    /**
     * Register a logical lease with the CURRENT tracked Task. Compiler/native
     * lock lowering may call this only after checking actual token ownership
     * and cross-thread release safety. Do NOT pass java.util.concurrent.Lock::unlock.
     */
    // Package-private: only trusted runtime lowering/adapters may register.
    // Never surface a generic host callback registration to Oreslang guest code.
    static Lease register(TransferableRelease release) {
        return TaskHandle.registerTransferableLease(Objects.requireNonNull(release, "release"));
    }

    /**
     * Immutable snapshot of failed releases. A failed release invalidates
     * memory reclamation even if the Task's value Future settled successfully.
     */
    public static final class CleanupFailure extends IllegalStateException {
        private final List<Throwable> failures;

        CleanupFailure(List<Throwable> failures) {
            super("task-owned lease cleanup failed (" + failures.size() + " leases)");
            this.failures = List.copyOf(failures);
        }

        public List<Throwable> failures() {
            return failures;
        }
    }

    static final class Registry {
        private final Object lock = new Object();
        private final ArrayDeque<Entry> stack = new ArrayDeque<>();
        // An explicit early close may fail and be caught by host code.
        // That still invalidates the cleanup proof at physical retirement.
        private final ArrayList<Throwable> earlyFailures = new ArrayList<>();
        private boolean retired;

        Lease register(TransferableRelease token) {
            synchronized (lock) {
                if (retired) throw new IllegalStateException(
                        "task lease registration after retirement");
                if (stack.size() == MAX_LEASES_PER_TASK) throw new IllegalStateException(
                        "task lease quota exceeded");
                Entry entry = new Entry(this, token);
                stack.addLast(entry);
                return entry;
            }
        }

        List<Throwable> releaseOnRetirement() {
            final ArrayList<Entry> reversed;
            final ArrayList<Throwable> errors;
            synchronized (lock) {
                if (retired) return List.of();
                retired = true;
                reversed = new ArrayList<>(stack.size());
                while (!stack.isEmpty()) reversed.add(stack.removeLast());
                // One linearized snapshot of earlier failed releases; no
                // registration or same-task close may follow retirement.
                errors = new ArrayList<>(earlyFailures);
                earlyFailures.clear();
            }
            // Do not invoke any release while holding the registry lock.
            for (Entry entry : reversed) {
                try {
                    entry.releaseOnce();
                } catch (RuntimeException | Error failed) {
                    // Never abandon sibling lease releases. The host owner
                    // must treat failed cleanup as a failed quiescence proof.
                    errors.add(failed);
                }
            }
            return List.copyOf(errors);
        }

        private static final class Entry implements Lease {
            private final Registry owner;
            private final TransferableRelease token;
            private final AtomicBoolean released = new AtomicBoolean();

            Entry(Registry owner, TransferableRelease token) {
                this.owner = owner;
                this.token = token;
            }

            private void releaseOnce() {
                if (released.compareAndSet(false, true)) token.release();
            }

            @Override public void close() {
                if (!TaskHandle.isCurrentLeaseOwner(owner)) {
                    throw new IllegalStateException(
                            "only the owning Task turn may close a live lease");
                }
                // A logical lease can be released early inside its owning
                // Task; retirement's stack entry is safely idempotent.
                try {
                    releaseOnce();
                } catch (RuntimeException | Error failed) {
                    synchronized (owner.lock) {
                        owner.earlyFailures.add(failed);
                    }
                    throw failed;
                }
            }
        }
    }
}
