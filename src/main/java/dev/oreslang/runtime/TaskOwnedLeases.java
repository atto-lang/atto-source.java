package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
        /** Enforce owning logical Task identity for any guest-visible access. */
        void requireCurrentOwner();
        @Override void close();
    }

    /**
     * Attach an actual mutex acquisition to the current tracked Task, if any.
     * The ordinary release and abandoned-release paths are intentionally
     * distinct: only abandonment may poison protected mutable state.
     * A mismatched or exhausted tracked Task MUST fail closed.
     */
    static Lease registerIfTracked(
            TransferableRelease ordinaryRelease,
            TransferableRelease abandonedRelease) {
        Objects.requireNonNull(ordinaryRelease, "ordinaryRelease");
        Objects.requireNonNull(abandonedRelease, "abandonedRelease");
        if (!TaskHandle.isCurrentTrackedTaskTurn()) return null;
        return TaskHandle.registerTransferableLease(ordinaryRelease, abandonedRelease);
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
        // Insertion-ordered live leases permit O(1) explicit retirement, so
        // sequential acquisitions cannot exhaust the live-leases quota.
        private final LinkedHashSet<Entry> active = new LinkedHashSet<>();
        private static final int MAX_EARLY_FAILURES = 64;
        // Bounded: arbitrary repeated early failures must not grow Task memory.
        private final ArrayList<Throwable> earlyFailures = new ArrayList<>();
        private long suppressedEarlyFailures;
        private boolean retired;

        Lease register(TransferableRelease token) {
            return register(token, token);
        }

        Lease register(TransferableRelease ordinaryRelease,
                       TransferableRelease abandonedRelease) {
            synchronized (lock) {
                if (retired) throw new IllegalStateException(
                        "task lease registration after retirement");
                if (active.size() == MAX_LEASES_PER_TASK) throw new IllegalStateException(
                        "task lease quota exceeded");
                Entry entry = new Entry(this, ordinaryRelease, abandonedRelease);
                active.add(entry);
                return entry;
            }
        }

        private void forget(Entry entry) {
            synchronized (lock) {
                active.remove(entry);
            }
        }

        private void recordEarlyFailure(Throwable failure) {
            synchronized (lock) {
                if (earlyFailures.size() < MAX_EARLY_FAILURES) {
                    earlyFailures.add(failure);
                } else if (suppressedEarlyFailures != Long.MAX_VALUE) {
                    suppressedEarlyFailures++;
                }
            }
        }

        List<Throwable> releaseOnRetirement() {
            final ArrayList<Entry> reversed;
            final ArrayList<Throwable> errors;
            synchronized (lock) {
                if (retired) return List.of();
                retired = true;
                reversed = new ArrayList<>(active);
                Collections.reverse(reversed);
                active.clear();
                // One linearized snapshot of earlier failed releases; no
                // registration or same-task close may follow retirement.
                errors = new ArrayList<>(earlyFailures);
                if (suppressedEarlyFailures != 0L) {
                    errors.add(new IllegalStateException(
                            suppressedEarlyFailures + " additional early lease release failures"));
                }
                earlyFailures.clear();
                suppressedEarlyFailures = 0L;
            }
            // Do not invoke any release while holding the registry lock.
            for (Entry entry : reversed) {
                try {
                    entry.releaseOnce(true);
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
            private final TransferableRelease ordinaryRelease;
            private final TransferableRelease abandonedRelease;
            private final AtomicBoolean released = new AtomicBoolean();

            Entry(Registry owner, TransferableRelease ordinaryRelease,
                  TransferableRelease abandonedRelease) {
                this.owner = owner;
                this.ordinaryRelease = ordinaryRelease;
                this.abandonedRelease = abandonedRelease;
            }

            private void releaseOnce(boolean abandoned) {
                if (released.compareAndSet(false, true)) {
                    (abandoned ? abandonedRelease : ordinaryRelease).release();
                }
            }

            @Override public void requireCurrentOwner() {
                if (!TaskHandle.isCurrentLeaseOwner(owner)) {
                    throw new IllegalStateException(
                            "mutex guard belongs to a different logical Task");
                }
            }

            @Override public void close() {
                requireCurrentOwner();
                // A logical lease can be released early inside its owning
                // Task; retirement's stack entry is safely idempotent.
                try {
                    releaseOnce(false);
                } catch (RuntimeException | Error failed) {
                    owner.recordEarlyFailure(failed);
                    throw failed;
                } finally {
                    // Unlink after an explicit close regardless of success.
                    // The failure remains in cleanupFailures and prevents
                    // falsely claiming resource reclamation.
                    owner.forget(this);
                }
            }
        }
    }
}
