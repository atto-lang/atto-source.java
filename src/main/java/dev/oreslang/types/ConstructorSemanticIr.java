package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-neutral semantic objects for ordinary-class construction, callables,
 * and recursive aliases.
 *
 * <p>These records deliberately sit above parser spelling and below backend ABI
 * lowering. In particular, an optional source {@code self} receiver is metadata,
 * never a user parameter, and constructor identity is only owner + effective
 * caller-visible arity.</p>
 */
public final class ConstructorSemanticIr {
    private ConstructorSemanticIr() { }

    public enum ReceiverBinding {
        IMPLICIT,
        EXPLICIT_SELF
    }

    public enum ConstructionPhase {
        ALLOCATED,
        INITIALIZING,
        INITIALIZED,
        CONSTRUCTED;

        public boolean canTransitionTo(ConstructionPhase next) {
            Objects.requireNonNull(next, "next construction phase");
            return next.ordinal() >= ordinal();
        }
    }

    public record ConstructorKey(String owner, int effectiveArity) {
        public ConstructorKey {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("constructor owner cannot be blank");
            }
            if (effectiveArity < 0) {
                throw new IllegalArgumentException("constructor arity cannot be negative");
            }
        }
    }

    /**
     * Semantic constructor declaration. {@code userParameters} never contains
     * the compiler-supplied receiver.
     */
    public record ConstructorDecl(
            ConstructorKey key,
            ReceiverBinding receiverBinding,
            List<Ast.Param> userParameters,
            Ast.ConstructorDecl source,
            ConstructorKey delegatesTo) {
        public ConstructorDecl {
            Objects.requireNonNull(key, "constructor key");
            Objects.requireNonNull(receiverBinding, "receiver binding");
            Objects.requireNonNull(source, "source constructor");
            userParameters = List.copyOf(userParameters);
            if (key.effectiveArity() != userParameters.size()) {
                throw new IllegalArgumentException(
                        "constructor key arity " + key.effectiveArity()
                                + " does not match " + userParameters.size()
                                + " caller-visible parameters");
            }
            if (delegatesTo != null && !key.owner().equals(delegatesTo.owner())) {
                throw new IllegalArgumentException(
                        "constructor delegation must stay within owner '" + key.owner() + "'");
            }
        }

        public static ConstructorDecl of(
                String owner,
                ReceiverBinding receiverBinding,
                Ast.ConstructorDecl source) {
            return new ConstructorDecl(
                    new ConstructorKey(owner, source.arity()),
                    receiverBinding,
                    source.parameters(),
                    source,
                    null);
        }

        public ConstructorDecl withDelegation(ConstructorKey target) {
            return new ConstructorDecl(key, receiverBinding, userParameters, source, target);
        }
    }

    public record ConstructionRegionId(String owner, int constructorArity, long ordinal) {
        public ConstructionRegionId {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("construction region owner cannot be blank");
            }
            if (constructorArity < 0) {
                throw new IllegalArgumentException("construction region arity cannot be negative");
            }
            if (ordinal < 0) {
                throw new IllegalArgumentException("construction region ordinal cannot be negative");
            }
        }
    }

    public enum CleanupKind {
        DEFER,
        FINALLY,
        FIELD_DROP,
        BASE_DROP,
        STORAGE_RECLAIM
    }

    public record CleanupEdge(CleanupKind kind, String subject) {
        public CleanupEdge {
            Objects.requireNonNull(kind, "cleanup kind");
            subject = subject == null ? "" : subject;
        }
    }

    /**
     * Construction state carried by semantic CFG edges.
     *
     * <p>{@link ConstructionPhase#INITIALIZED} is not publication. Only
     * CONSTRUCTED may be converted to an ordinary object capability.</p>
     */
    public record ConstructionState(
            ConstructionRegionId region,
            ConstructionPhase phase,
            ConstructorFlowState fields,
            List<CleanupEdge> pendingCleanups) {
        public ConstructionState {
            Objects.requireNonNull(region, "construction region");
            Objects.requireNonNull(phase, "construction phase");
            Objects.requireNonNull(fields, "constructor field state");
            pendingCleanups = List.copyOf(pendingCleanups);
        }

        public ConstructionState transitionTo(ConstructionPhase next) {
            if (!phase.canTransitionTo(next)) {
                throw new IllegalArgumentException(
                        "construction phase cannot move backward from " + phase + " to " + next);
            }
            return new ConstructionState(region, next, fields, pendingCleanups);
        }

        public ConstructionState withFields(ConstructorFlowState nextFields) {
            return new ConstructionState(region, phase, nextFields, pendingCleanups);
        }

        public ConstructionState withCleanup(CleanupEdge cleanup) {
            java.util.ArrayList<CleanupEdge> next = new java.util.ArrayList<>(pendingCleanups);
            next.add(Objects.requireNonNull(cleanup, "cleanup"));
            return new ConstructionState(region, phase, fields, next);
        }

        public ConstructionState join(ConstructionState other) {
            Objects.requireNonNull(other, "other construction state");
            if (!region.equals(other.region)) {
                throw new IllegalArgumentException("cannot join different construction regions");
            }
            if (phase != other.phase) {
                throw new IllegalArgumentException(
                        "cannot join construction states in different phases: "
                                + phase + " and " + other.phase);
            }
            if (!pendingCleanups.equals(other.pendingCleanups)) {
                throw new IllegalArgumentException(
                        "cleanup-stack join requires identical registered cleanup edges");
            }
            return new ConstructionState(
                    region,
                    phase,
                    fields.join(other.fields),
                    pendingCleanups);
        }

        public boolean publishable() {
            return phase == ConstructionPhase.CONSTRUCTED && fields.completeExactlyOnce();
        }
    }

    public enum CallableEffect {
        SUSPENDS,
        THROWS,
        RAISES,
        PANICS,
        READS_CONSTRUCTION,
        WRITES_CONSTRUCTION
    }

    public enum CallableCapability {
        BORROWED,
        CONSTRUCTION_SCOPED,
        ACTOR_LOCAL,
        SHAREABLE,
        SERIALIZABLE
    }

    /**
     * Concrete callable semantic type. Parameter names never participate in
     * identity; ownership/effect metadata does.
     */
    public record CallableType(
            List<Types.Type> parameters,
            Types.Type result,
            Set<CallableEffect> effects,
            Set<CallableCapability> captureCapabilities) {
        public CallableType {
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(result, "callable result");
            effects = Set.copyOf(effects);
            captureCapabilities = Set.copyOf(captureCapabilities);
        }

        public Types.Function erasedToLegacyFunction() {
            return new Types.Function(parameters, result);
        }

        public Types.Type erasedCallableTop() {
            return Types.AnyCallable.INSTANCE;
        }
    }

    /**
     * Nominal fixpoint wrapper for a recursive alias. The body is finite and
     * may refer back to {@code declarationId}; consumers must never expand it
     * textually without cycle detection.
     */
    public record RecursiveAliasType(
            String declarationId,
            String name,
            List<String> genericParameters,
            Ast.TypeRef body,
            boolean representationGuarded,
            boolean regularGenericRecursion) {
        public RecursiveAliasType {
            if (declarationId == null || declarationId.isBlank()) {
                throw new IllegalArgumentException("recursive alias declaration id cannot be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("recursive alias name cannot be blank");
            }
            genericParameters = List.copyOf(genericParameters);
            Objects.requireNonNull(body, "recursive alias body");
        }

        public void requireWellFormed() {
            if (!representationGuarded) {
                throw new IllegalArgumentException(
                        "recursive alias '" + name + "' has an infinite inline representation");
            }
            if (!regularGenericRecursion) {
                throw new IllegalArgumentException(
                        "recursive alias '" + name + "' changes its own generic arguments recursively");
            }
        }
    }
}
