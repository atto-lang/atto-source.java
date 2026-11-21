package dev.oreslang.types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Experimental, opt-in ownership-MIR observer of the production Java checker.
 *
 * This is a deliberately restricted straight-line, Copy-parameter profile.
 * It is not independent source-to-MIR completeness attestation and never
 * authorizes guest code generation. Unsupported functions fail closed in the
 * evidence report while the production OwnershipChecker still checks them.
 */
public final class BorrowMirAuditV1 {
    public static final String CONTRACT_VERSION = "atto-borrow-mir-v1-java-shadow";
    public static final String CANONICAL_TARGET = "atto-borrow-mir-v1";
    public static final String MODEL_ONLY = "SHADOW_MODEL_ONLY";
    public static final String UNSUPPORTED = "NOT_PROVEN";
    private static final int MAX_FUNCTIONS = 128;
    private static final int MAX_OWNERS = 64;
    private static final int MAX_OPERATIONS = 1024;

    private BorrowMirAuditV1() { }

    public record Instruction(long instructionId, long compilerLocalEventOrdinal, String operation,
                              int ownerId, boolean isCopy, boolean isSend) {
        public Instruction {
            Objects.requireNonNull(operation, "operation");
            if (instructionId <= 0 || compilerLocalEventOrdinal <= 0 || ownerId < 0) {
                throw new IllegalArgumentException("invalid borrow MIR event identity");
            }
        }
    }

    public record FunctionTrace(String name, long functionId, String status,
                                String reason, List<Instruction> instructions) {
        public FunctionTrace {
            instructions = List.copyOf(instructions);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean eligibleForModelComparison() {
            return MODEL_ONLY.equals(status);
        }
    }

    /**
     * A Report is never production certification: its IDs are compiler-local
     * event ordinals and DO NOT authenticate source NodeId/LoanId, type proofs,
     * return-value ABI, compiled machine code, or the full function IR.
     */
    public record Report(String version, boolean completeSourceInventory,
                         boolean verifiedEmittedCode, List<FunctionTrace> functions) {
        public Report {
            functions = List.copyOf(functions);
        }
    }

    /** Created privately by the existing ownership checker, not by source code. */
    static final class Collector {
        private final List<FunctionTrace> functions = new ArrayList<>();
        private final IdentityHashMap<Object, Integer> roots = new IdentityHashMap<>();
        private List<Instruction> currentEvents;
        private String currentName;
        private String unsupportedReason;
        private long currentFunction;
        private int nextOwnerId;
        private int returns;

        void beginFunction(String name, boolean straightLine) {
            if (currentEvents != null) {
                throw new IllegalStateException("nested borrow MIR collector function");
            }
            roots.clear();
            nextOwnerId = 1;
            returns = 0;
            currentEvents = new ArrayList<>();
            currentFunction = functions.size() + 1L;
            currentName = name;
            unsupportedReason = straightLine ? null : "nonlinear, async or effectful function profile";
            if (functions.size() >= MAX_FUNCTIONS) {
                throw new IllegalArgumentException("borrow MIR function budget exceeded");
            }
        }

        void parameter(Object checkedOwner, boolean isCopy) {
            if (currentEvents == null) {
                throw new IllegalStateException("no active borrow MIR function");
            }
            if (checkedOwner == null || roots.containsKey(checkedOwner)) {
                unsupported("unknown or repeated owner identity");
                return;
            }
            if (nextOwnerId > MAX_OWNERS) {
                unsupported("owner identity budget exceeded");
                return;
            }
            int id = nextOwnerId++;
            roots.put(checkedOwner, id);
            // Transitive Send is NOT attested by this observer. Never infer
            // Send from Copy or claim this bit is the compiler's Send proof.
            record("INIT", id, isCopy, false);
            if (!isCopy) {
                unsupported("non-Copy parameter requires affine move/loan CFG proof");
            }
        }

        void readCopyParameter(Object checkedOwner) {
            if (currentEvents == null) {
                return;
            }
            Integer id = roots.get(checkedOwner);
            if (id == null) {
                unsupported("read has no compiler-tracked parameter root");
                return;
            }
            record("READ", id, true, false);
        }

        void returned() {
            if (currentEvents == null) {
                return;
            }
            returns++;
            if (returns != 1) {
                unsupported("multiple return effects require CFG edges");
            }
            record("RETURN", 0, false, false);
        }

        void unsupported(String reason) {
            if (currentEvents != null && unsupportedReason == null) {
                unsupportedReason = Objects.requireNonNull(reason);
            }
        }

        private void record(String operation, int root, boolean copy, boolean send) {
            if (currentEvents.size() >= MAX_OPERATIONS) {
                unsupported("ownership event inventory budget exceeded");
                return;
            }
            long id = currentEvents.size() + 1L;
            // This event ordinal is NOT an authenticated source AST NodeId.
            currentEvents.add(new Instruction(id, id, operation, root, copy, send));
        }

        void finishFunction() {
            if (currentEvents == null) {
                throw new IllegalStateException("missing borrow MIR function");
            }
            if (returns != 1) {
                unsupported("no unique terminal return instruction");
            }
            String status = unsupportedReason == null ? MODEL_ONLY : UNSUPPORTED;
            functions.add(new FunctionTrace(
                    currentName, currentFunction, status,
                    unsupportedReason == null ? "restricted Copy-only linear shadow model"
                            : unsupportedReason,
                    status.equals(MODEL_ONLY) ? currentEvents : List.of()));
            currentEvents = null;
            roots.clear();
        }

        Report finishReport() {
            if (currentEvents != null) {
                throw new IllegalStateException("incomplete borrow MIR collection");
            }
            // The observer never authorizes production code or claims complete
            // compiler/emission inventory, even when every function is model-only.
            return new Report(CONTRACT_VERSION, false, false, functions);
        }
    }
}
