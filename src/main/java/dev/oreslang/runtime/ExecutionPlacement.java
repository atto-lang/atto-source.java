package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Host scheduling hint for one logical Oreslang runnable.
 *
 * <p>This is deliberately not an ownership capability. A placement hint can
 * improve cache locality but can never authorize actor state, a Task loan,
 * supervisor CONTROL, code-generation access, or a native TLS resource.</p>
 */
public final class ExecutionPlacement {
    private ExecutionPlacement() {}

    public enum Kind {
        ACTOR_TURN,
        TASK,
        CALLBACK,
        CONTINUATION,
        CONTROL
    }

    public enum Affinity {
        NONE,
        SOFT,
        THREAD_BOUND
    }

    public record Hint(Kind kind, Affinity affinity, int affinityKey,
                       int preferredNuma) {
        /* Existing callers still have no NUMA promise. */
        public Hint(Kind kind, Affinity affinity, int affinityKey) {
            this(kind, affinity, affinityKey, CpuTopology.UNKNOWN);
        }

        public Hint {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(affinity, "affinity");
            if (preferredNuma < CpuTopology.UNKNOWN || preferredNuma > 65535) {
                throw new IllegalArgumentException("invalid NUMA node hint");
            }
            if (preferredNuma != CpuTopology.UNKNOWN
                    && (kind != Kind.TASK || affinity != Affinity.SOFT)) {
                throw new IllegalArgumentException(
                        "NUMA region hints require a soft Task placement");
            }
        }

        public static Hint unbound(Kind kind) {
            return new Hint(kind, Affinity.NONE, 0);
        }

        public static Hint soft(Kind kind, int affinityKey) {
            return new Hint(kind, Affinity.SOFT, affinityKey);
        }

        /** Region-locality advice from the trusted host allocation ledger.
         * This cannot authorize a borrow, infer actual NUMA page residence,
         * or override the scheduler's bounded spill/steal and cancellation. */
        public static Hint regionTask(int affinityKey, int preferredNuma) {
            return new Hint(Kind.TASK, Affinity.SOFT, affinityKey, preferredNuma);
        }

        /**
         * A non-migratable request. NativeCarrierExecutor currently rejects
         * this fail-closed because a soft queue/steal lane is not proof that a
         * native TLS-bound resource will resume on the same pthread.
         */
        public static Hint threadBound(Kind kind, int affinityKey) {
            return new Hint(kind, Affinity.THREAD_BOUND, affinityKey);
        }
    }
}
