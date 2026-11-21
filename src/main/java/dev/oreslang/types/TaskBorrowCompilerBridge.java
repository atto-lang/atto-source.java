package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * First compiler adapter for the scoped Task footprint verifier.
 *
 * <p>This intentionally accepts only whole, process-owned scalar places.
 * It never accepts caller-asserted referent types: the AST type is obtained
 * from the compiler's resolved-place lookup. Unsupported or unresolved
 * source types, all projected paths, and non-process domains fail closed.
 *
 * <p>Not an executable source Task permission: provenance identity and
 * TaskScope lifetime still require MIR proof minting. The returned Admission's
 * one-shot PhysicalCompletion must be bound to physical Task exit
 * before Java or LLVM source admission can be enabled.
 */
final class TaskBorrowCompilerBridge {
    private TaskBorrowCompilerBridge() { }

    /**
     * Preflights independently resolved scalar accesses and reserves them
     * atomically on a compiler-local ledger. The lookup MUST come from the
     * checked symbol/place table, not guest source annotations. Allocation
     * identities and uniqueness must be independently resolved from the
     * compiler's trusted allocation table. A callback that simply echoes
     * user claims does NOT establish MIR authority.
     */
    static TaskAccessVerifier.Admission admitResolvedScalars(
            TaskAccessVerifier ledger,
            List<TaskAccessVerifier.Claim> accesses,
            Function<TaskAccessVerifier.Place, TaskAccessVerifier.Root> resolvedAllocationRoots,
            Function<TaskAccessVerifier.Place, Ast.TypeRef> resolvedPlaceTypes) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(resolvedAllocationRoots, "resolved allocation identities");
        Objects.requireNonNull(resolvedPlaceTypes, "resolved place types");
        List<TaskAccessVerifier.Claim> claims =
                List.copyOf(Objects.requireNonNull(accesses, "accesses"));
        Map<TaskAccessVerifier.Place, Types.Type> resolved = new HashMap<>();
        List<TaskAccessVerifier.TypedClaim> typed = new ArrayList<>(claims.size());
        for (TaskAccessVerifier.Claim claim : claims) {
            TaskAccessVerifier.Place place = claim.place();
            TaskAccessVerifier.Root root = place.root();
            // The caller's Root record is a requested place, not itself
            // authority. Verify it independently against the checked
            // compiler's canonical allocation table, including the
            // uniqueness witness and generation, before resolving its type.
            TaskAccessVerifier.Root actualRoot = resolvedAllocationRoots.apply(place);
            if (actualRoot == null || !actualRoot.equals(root)) {
                throw new IllegalArgumentException(
                        "Task claim does not match compiler-resolved allocation provenance");
            }
            // Until source MIR produces layout/projection proofs, claiming
            // field/column separation here would be an unverified assumption.
            if (!place.projections().isEmpty()) {
                throw new IllegalArgumentException(
                        "Task field/range borrowing needs compiler-verified layout lowering");
            }
            if (!root.domain().equals("PROCESS") || !root.ownerId().equals("PROCESS")) {
                throw new IllegalArgumentException(
                        "Task actor/tenant storage needs proven isolation-domain authority");
            }
            Types.Type type = provenScalar(resolvedPlaceTypes.apply(place));
            Types.Type earlier = resolved.putIfAbsent(place, type);
            if (earlier != null && !earlier.equals(type)) {
                throw new IllegalStateException("inconsistent resolved Task place types");
            }
            typed.add(new TaskAccessVerifier.TypedClaim(claim, type));
        }
        // The verifier remains the single route to READ/WRITE conflict
        // checks, generational admission and auto-trait verification.
        return ledger.admitScopedTyped(typed, (type, trait) -> false, resolved::get);
    }

    /**
     * Runtime-facing form of the physical retirement capability. Lowering may
     * pass this Runnable to OresTaskScope.startCompilerFrame; it carries no
     * ticket, place, type, or cancellation authority.
     */
    static Runnable physicalExitCallback(
            TaskAccessVerifier.Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return admission.completion()::releaseAfterPhysicalQuiescence;
    }

    private static Types.Type provenScalar(Ast.TypeRef source) {
        if (source == null || source.isBorrow() || source.inferArguments()
                || !source.arguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Task scalar capture requires a fully resolved, non-borrowed type");
        }
        return switch (source.name()) {
            case "int" -> Types.Primitive.INT;
            case "bool" -> Types.Primitive.BOOL;
            case "float" -> Types.Primitive.FLOAT;
            // Other numeric, nominal, container and composite forms require
            // the canonical TypeChecker/MIR type mapping, not name guessing.
            default -> throw new IllegalArgumentException(
                    "Task scalar capture type is not proven: " + source.name());
        };
    }
}
