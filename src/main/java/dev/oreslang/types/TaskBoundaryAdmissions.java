package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Sealed compiler proof ledger for exact executable Task AST boundaries.
 *
 * <p>Unlike an AST shape check, lookup is by both exact {@link Ast.Program}
 * identity and exact {@link Ast.TaskExpr} identity. A structurally equal or
 * reconstructed Task expression has no authority, and an absent proof is never
 * represented as an empty capture list.</p>
 *
 * <p>The current executable surface records only the narrow copied-scalar Task.
 * The package-private scoped-loan path exists for the next compiler lowering
 * stage: it stores the exact ordered coherent claims and type authorities so a
 * later dynamic launch can mint {@link TaskLoanAdmission} without accepting
 * caller-supplied replacement claims.</p>
 */
public final class TaskBoundaryAdmissions {
    private static final int MAX_BOUNDARIES = 4_096;

    public static final class ScalarAdmission {
        private final Ast.Expr returnedExpression;
        private final Types.Type resolvedType;

        private ScalarAdmission(
                Ast.Expr returnedExpression,
                Types.Type resolvedType) {
            this.returnedExpression =
                    Objects.requireNonNull(returnedExpression, "returnedExpression");
            this.resolvedType = Objects.requireNonNull(resolvedType, "resolvedType");
        }

        /** Exact expression object validated inside this exact Task boundary. */
        public Ast.Expr returnedExpression() {
            return returnedExpression;
        }

        /** Canonical resolved scalar type admitted by the frontend. */
        public Types.Type resolvedType() {
            return resolvedType;
        }
    }

    private sealed interface BoundaryAdmission
            permits ScalarBoundary, ScopedLoanBoundary { }

    private record ScalarBoundary(ScalarAdmission admission)
            implements BoundaryAdmission {
        private ScalarBoundary {
            Objects.requireNonNull(admission, "admission");
        }
    }

    private record ScopedLoanBoundary(
            List<TaskAccessVerifier.CoherentTypedClaim> claims,
            SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof resolvedNominals)
            implements BoundaryAdmission {
        private ScopedLoanBoundary {
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            Objects.requireNonNull(sourceProof, "sourceProof");
            Objects.requireNonNull(resolvedNominals, "resolvedNominals");
            if (claims.isEmpty()) {
                throw new IllegalArgumentException(
                        "scoped Task loan proof requires an exact non-empty footprint");
            }
        }
    }

    private final Ast.Program programIdentity;
    private final IdentityHashMap<Ast.TaskExpr, BoundaryAdmission> byBoundary =
            new IdentityHashMap<>();
    private boolean sealed;

    TaskBoundaryAdmissions(Ast.Program programIdentity) {
        this.programIdentity =
                Objects.requireNonNull(programIdentity, "programIdentity");
    }

    static TaskBoundaryAdmissions rejectAll(Ast.Program programIdentity) {
        return new TaskBoundaryAdmissions(programIdentity).seal();
    }

    void recordScalar(
            Ast.TaskExpr boundary,
            Ast.Expr returnedExpression,
            Types.Type resolvedType) {
        ensureMutable();
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(returnedExpression, "returnedExpression");
        Objects.requireNonNull(resolvedType, "resolvedType");
        if (ScalarTaskPlan.returnedScalar(boundary) != returnedExpression) {
            throw new IllegalArgumentException(
                    "scalar Task proof must bind the exact returned AST expression");
        }
        put(boundary, new ScalarBoundary(
                new ScalarAdmission(returnedExpression, resolvedType)));
    }

    /**
     * Future borrowed/scoped lowering hook. Package-private by design: only
     * compiler-owned analysis in this package may bind allocation/generation
     * claims and type authorities to a source boundary.
     */
    void recordScopedLoan(
            Ast.TaskExpr boundary,
            List<TaskAccessVerifier.CoherentTypedClaim> claims,
            SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof resolvedNominals) {
        ensureMutable();
        put(Objects.requireNonNull(boundary, "boundary"),
                new ScopedLoanBoundary(claims, sourceProof, resolvedNominals));
    }

    TaskBoundaryAdmissions seal() {
        sealed = true;
        return this;
    }

    /**
     * Fail-closed executable scalar lookup. A valid zero-capture literal Task
     * still has a concrete ScalarAdmission; missing proof never returns null or
     * an empty list that lowering could mistake for authorization.
     */
    public ScalarAdmission requireScalar(
            Ast.Program program,
            Ast.TaskExpr boundary) {
        requireProgram(program);
        BoundaryAdmission admission = requireBoundary(boundary);
        if (!(admission instanceof ScalarBoundary scalar)) {
            throw new IllegalArgumentException(
                    "exact Task boundary is not admitted for scalar lowering");
        }
        return scalar.admission();
    }

    /**
     * Convert one exact compiler-scoped boundary proof into the one-shot
     * runtime loan capability used by Gate B. The caller cannot substitute a
     * different claim list, type proof, allocation generation or capture order:
     * those are retained inside this sealed compiler proof.
     */
    TaskLoanAdmission mintScopedLoan(
            Ast.Program program,
            Ast.TaskExpr boundary,
            TaskAccessVerifier verifier) {
        requireProgram(program);
        Objects.requireNonNull(verifier, "verifier");
        BoundaryAdmission admission = requireBoundary(boundary);
        if (!(admission instanceof ScopedLoanBoundary scoped)) {
            throw new IllegalArgumentException(
                    "exact Task boundary has no scoped-loan compiler proof");
        }
        return verifier.admitCoherentTypedRuntimeBound(
                scoped.claims(),
                scoped.sourceProof(),
                scoped.resolvedNominals());
    }

    public int boundaryCount() {
        return byBoundary.size();
    }

    public boolean sealed() {
        return sealed;
    }

    private void put(Ast.TaskExpr boundary, BoundaryAdmission admission) {
        if (byBoundary.size() >= MAX_BOUNDARIES) {
            throw new IllegalStateException(
                    "Task boundary admission capacity exceeded");
        }
        if (byBoundary.putIfAbsent(boundary, admission) != null) {
            throw new IllegalStateException(
                    "duplicate Task admission for exact AST boundary");
        }
    }

    private BoundaryAdmission requireBoundary(Ast.TaskExpr boundary) {
        Objects.requireNonNull(boundary, "Task boundary");
        if (!sealed) {
            throw new IllegalStateException(
                    "Task boundary admissions must be sealed before lookup");
        }
        if (!byBoundary.containsKey(boundary)) {
            throw new IllegalArgumentException(
                    "no Task admission for exact AST boundary");
        }
        return byBoundary.get(boundary);
    }

    private void requireProgram(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!sealed) {
            throw new IllegalStateException(
                    "Task boundary admissions must be sealed before lookup");
        }
        if (program != programIdentity) {
            throw new IllegalArgumentException(
                    "Task admission belongs to a different exact program instance");
        }
    }

    private void ensureMutable() {
        if (sealed) {
            throw new IllegalStateException(
                    "Task boundary admissions are already sealed");
        }
    }
}
