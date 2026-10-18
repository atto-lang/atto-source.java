package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.ConstructorSemanticIr.CleanupKind;
import dev.oreslang.types.ConstructorSemanticIr.CleanupEdge;
import dev.oreslang.types.ConstructorSemanticIr.ConstructionPhase;
import dev.oreslang.types.ConstructorSemanticIr.ConstructionState;

/**
 * A backend-neutral failure cleanup *plan*, not a runtime destructor.
 *
 * <p>Plans are computed for a failed, still-unpublished constructor on a
 * particular CFG edge. MAY-initialized fields demand an initialization flag
 * at the point of cleanup; MUST-initialized fields drop unconditionally.
 * A field is dropped at most once even if a mutable slot was written more
 * than once. Ownership transfers/replacements must separately emit their
 * own drop at the actual assignment site.</p>
 *
 * <p>All observable cleanup effects and suppression/replacement of failures
 * require backend-specific lowering; this class does not claim to execute
 * those effects.</p>
 */
public final class ConstructorFailureCleanup {
    private ConstructorFailureCleanup() { }

    public record Action(CleanupKind kind, String subject, boolean conditional) {
        public Action {
            Objects.requireNonNull(kind, "cleanup action kind");
            subject = Objects.requireNonNull(subject, "cleanup subject");
            if (subject.isBlank()) throw new IllegalArgumentException("cleanup subject is blank");
            if (conditional && kind != CleanupKind.FIELD_DROP) {
                throw new IllegalArgumentException("only field drops may be conditional");
            }
        }
    }

    /**
     * @param state still-private failed construction state on this CFG edge
     * @param fieldDeclarationOrder ALL tracked fields in declaration order
     * @param baseInitialized proof that a base subobject finished construction
     * @return immutable ordered cleanup actions
     */
    public static List<Action> plan(
            ConstructionState state,
            List<String> fieldDeclarationOrder,
            boolean baseInitialized) {
        Objects.requireNonNull(state, "construction state");
        Objects.requireNonNull(fieldDeclarationOrder, "field declaration order");
        if (state.phase() == ConstructionPhase.CONSTRUCTED) {
            throw new IllegalArgumentException("cannot generate failure cleanup for published object");
        }
        Map<String, ConstructorFlowState.AssignmentCount> fields = state.fields().snapshot();
        List<String> names = List.copyOf(fieldDeclarationOrder);
        Set<String> seen = new HashSet<>(names);
        if (seen.size() != names.size() || !fields.keySet().equals(seen)) {
            throw new IllegalArgumentException(
                    "cleanup field declaration order must cover each tracked field exactly once");
        }

        ArrayList<Action> plan = new ArrayList<>();
        List<CleanupEdge> pending = state.pendingCleanups();
        for (int i = pending.size() - 1; i >= 0; i--) {
            CleanupEdge edge = pending.get(i);
            if (edge.kind() != CleanupKind.DEFER && edge.kind() != CleanupKind.FINALLY) {
                throw new IllegalArgumentException(
                        "pending cleanup stack must contain only defer/finally obligations");
            }
            plan.add(new Action(edge.kind(), edge.subject(), false));
        }
        for (int i = names.size() - 1; i >= 0; i--) {
            String field = names.get(i);
            ConstructorFlowState.AssignmentCount count = fields.get(field);
            if (count.maybeInitialized()) {
                plan.add(new Action(
                        CleanupKind.FIELD_DROP,
                        field,
                        !count.definitelyInitialized()));
            }
        }
        if (baseInitialized) {
            plan.add(new Action(CleanupKind.BASE_DROP, state.region().owner(), false));
        }
        plan.add(new Action(CleanupKind.STORAGE_RECLAIM, state.region().owner(), false));
        return List.copyOf(plan);
    }
}
