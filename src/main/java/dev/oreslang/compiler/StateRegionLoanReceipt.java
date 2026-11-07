package dev.oreslang.compiler;

import java.util.Objects;

/**
 * State-region receipt v1. Compiler requirements are SYMBOLIC and contain no
 * scheduler IDs. Runtime receipts contain authenticated region/execution IDs,
 * but matching receipts are NOT themselves an authority to access guest state.
 *
 * All numeric IDs are limited to the IEEE-754 exact-integer range used by
 * WASM/JSON v1. This is host metadata only: no @Inject lowering or guest
 * access is conferred. JVM records are not assumed allocation-free.
 */
public final class StateRegionLoanReceipt {
    public static final long MAX_JSON_SAFE_ID = 9_007_199_254_740_991L;

    private StateRegionLoanReceipt() {}

    public enum Kind { READ, WRITE }
    public enum Action { BORROW, RELEASE }

    private static boolean identity_valid(long value) {
        return value > 0 && value <= MAX_JSON_SAFE_ID;
    }

    private static void require_identity(long value, String field) {
        if (!identity_valid(value)) {
            throw new IllegalArgumentException(
                    field + " must be a positive exact JSON/WASM integer");
        }
    }

    private static void require_nonblank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank");
        }
    }

    /** Symbolic compile-time loan requirement: never mint concrete identities. */
    public record Requirement(
            String region_binding,
            boolean current_logical_execution,
            boolean generation_fence,
            long loan_id,
            String place,
            Kind kind,
            Action action) {

        public Requirement {
            require_nonblank(region_binding, "region_binding");
            if (!current_logical_execution) {
                throw new IllegalArgumentException("current logical execution required");
            }
            if (!generation_fence) {
                throw new IllegalArgumentException("generation fence required");
            }
            require_identity(loan_id, "loan_id");
            require_nonblank(place, "place");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(action, "action");
        }

        public boolean authorizes_release(Requirement release) {
            return release != null &&
                    action == Action.BORROW && release.action == Action.RELEASE &&
                    region_binding.equals(release.region_binding) &&
                    current_logical_execution == release.current_logical_execution &&
                    generation_fence == release.generation_fence &&
                    loan_id == release.loan_id &&
                    place.equals(release.place) && kind == release.kind;
        }

        public Requirement release_requirement() {
            if (action != Action.BORROW) {
                throw new IllegalStateException("only borrow requirements derive releases");
            }
            return new Requirement(region_binding, current_logical_execution,
                    generation_fence, loan_id, place, kind, Action.RELEASE);
        }
    }

    /** Host-materialized provenance for one logical execution and region. */
    public record Provenance(
            long region_id,
            long region_generation,
            long runtime_domain,
            long execution_id,
            long execution_slot,
            long execution_generation,
            long loan_id,
            String place,
            Kind kind,
            Action action) {

        public Provenance {
            require_identity(region_id, "region_id");
            require_identity(region_generation, "region_generation");
            require_identity(runtime_domain, "runtime_domain");
            require_identity(execution_id, "execution_id");
            require_identity(execution_slot, "execution_slot");
            require_identity(execution_generation, "execution_generation");
            require_identity(loan_id, "loan_id");
            require_nonblank(place, "place");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(action, "action");
        }

        public boolean authorizes_release(Provenance release) {
            return release != null &&
                    action == Action.BORROW && release.action == Action.RELEASE &&
                    region_id == release.region_id &&
                    region_generation == release.region_generation &&
                    runtime_domain == release.runtime_domain &&
                    execution_id == release.execution_id &&
                    execution_slot == release.execution_slot &&
                    execution_generation == release.execution_generation &&
                    loan_id == release.loan_id &&
                    place.equals(release.place) && kind == release.kind;
        }

        public Provenance release_record() {
            if (action != Action.BORROW) {
                throw new IllegalStateException("only borrow receipts derive releases");
            }
            return new Provenance(region_id, region_generation, runtime_domain,
                    execution_id, execution_slot, execution_generation, loan_id,
                    place, kind, Action.RELEASE);
        }
    }
}
