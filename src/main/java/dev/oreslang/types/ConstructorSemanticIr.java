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
            List<CleanupEdge> pendingCleanups,
            Set<String> fixedFields) {
        public ConstructionState {
            Objects.requireNonNull(region, "construction region");
            Objects.requireNonNull(phase, "construction phase");
            Objects.requireNonNull(fields, "constructor field state");
            pendingCleanups = List.copyOf(pendingCleanups);
            fixedFields = Set.copyOf(fixedFields);
            if (!fields.snapshot().keySet().containsAll(fixedFields)) {
                throw new IllegalArgumentException("fixed constructor field not present in state");
            }
        }

        /** Compatibility constructor: all tracked fields are immutable. */
        public ConstructionState(
                ConstructionRegionId region,
                ConstructionPhase phase,
                ConstructorFlowState fields,
                List<CleanupEdge> pendingCleanups) {
            this(region, phase, fields, pendingCleanups, fields.snapshot().keySet());
        }

        // Field mutability is fixed by the declaring class when this region
        // is created. It must not be relaxed after validation begins.

        public ConstructionState transitionTo(ConstructionPhase next) {
            if (!phase.canTransitionTo(next)) {
                throw new IllegalArgumentException(
                        "construction phase cannot move backward from " + phase + " to " + next);
            }
            if (next == ConstructionPhase.CONSTRUCTED
                    && (!fields.completeWithFixedFields(fixedFields)
                        || !pendingCleanups.isEmpty())) {
                throw new IllegalArgumentException(
                        "construction cannot complete with uninitialized fields or pending cleanup");
            }
            return new ConstructionState(region, next, fields, pendingCleanups, fixedFields);
        }

        public ConstructionState withFields(ConstructorFlowState nextFields) {
            Objects.requireNonNull(nextFields, "new constructor field state");
            if (!fields.snapshot().keySet().equals(nextFields.snapshot().keySet())) {
                throw new IllegalArgumentException(
                        "construction field-set cannot change across state transitions");
            }
            if (phase == ConstructionPhase.CONSTRUCTED) {
                throw new IllegalArgumentException("constructed state cannot mutate fields");
            }
            return new ConstructionState(region, phase, nextFields, pendingCleanups, fixedFields);
        }

        public ConstructionState withCleanup(CleanupEdge cleanup) {
            if (phase == ConstructionPhase.CONSTRUCTED) {
                throw new IllegalArgumentException("constructed state cannot register cleanup");
            }
            java.util.ArrayList<CleanupEdge> next = new java.util.ArrayList<>(pendingCleanups);
            next.add(Objects.requireNonNull(cleanup, "cleanup"));
            return new ConstructionState(region, phase, fields, next, fixedFields);
        }

        /**
         * A cleanup completes in strict LIFO order. The region must discharge
         * every pending cleanup before transitioning to CONSTRUCTED.
         */
        public ConstructionState completeCleanup(CleanupEdge expectedTop) {
            Objects.requireNonNull(expectedTop, "completed cleanup");
            if (phase == ConstructionPhase.CONSTRUCTED || pendingCleanups.isEmpty()) {
                throw new IllegalArgumentException("no pending constructor cleanup to complete");
            }
            CleanupEdge top = pendingCleanups.get(pendingCleanups.size() - 1);
            if (!top.equals(expectedTop)) {
                throw new IllegalArgumentException("constructor cleanup must complete in LIFO order");
            }
            return new ConstructionState(
                    region, phase,
                    fields,
                    pendingCleanups.subList(0, pendingCleanups.size() - 1),
                    fixedFields);
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
            if (!fixedFields.equals(other.fixedFields)) {
                throw new IllegalArgumentException(
                        "cannot join construction states with different immutable field contracts");
            }
            return new ConstructionState(
                    region,
                    phase,
                    fields.join(other.fields),
                    pendingCleanups,
                    fixedFields);
        }

        public boolean publishable() {
            return phase == ConstructionPhase.CONSTRUCTED
                    && pendingCleanups.isEmpty()
                    && fields.completeWithFixedFields(fixedFields);
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
        CAPTURES_SHAREABLE_DATA,
        /**
         * All captured DATA values are independently serializable. This never
         * means the callable, its code pointer, environment or AnyCallable
         * erasure may be serialized or admitted to actor DATA.
         */
        CAPTURES_SERIALIZABLE_DATA
    }

    public enum CaptureOrigin {
        /** A lexically resolved binding owned outside the closure activation. */
        EXTERNAL_BINDING,
        /**
         * The pre-reserved slot used to tie a recursive lambda's runtime
         * fixpoint. This is required for invocation but is not an external
         * ownership capture and can never become portable DATA.
         */
        RECURSIVE_SELF
    }

    public record CaptureBinding(
            String bindingId,
            CaptureOrigin origin,
            boolean write) {
        public CaptureBinding {
            if (bindingId == null || bindingId.isBlank()) {
                throw new IllegalArgumentException("capture binding id cannot be blank");
            }
            Objects.requireNonNull(origin, "capture origin");
            if (origin == CaptureOrigin.RECURSIVE_SELF && write) {
                throw new IllegalArgumentException(
                        "recursive callable self slot cannot be classified as an outer mutable capture");
            }
        }

        public boolean participatesInExternalOwnership() {
            return origin == CaptureOrigin.EXTERNAL_BINDING;
        }

        /** Executable capture cells never authorize actor/network DATA. */
        public boolean actorDataAdmissible() {
            return false;
        }
    }

    /** Stable lexical identity after name resolution; spelling alone is never identity. */
    public record CaptureBindingId(String declarationId) {
        public CaptureBindingId {
            if (declarationId == null || declarationId.isBlank()) {
                throw new IllegalArgumentException("capture binding id cannot be blank");
            }
        }
    }

    public enum CaptureAccess {
        READ,
        WRITE;

        public CaptureAccess merge(CaptureAccess other) {
            Objects.requireNonNull(other, "capture access");
            return this == WRITE || other == WRITE ? WRITE : READ;
        }
    }

    /**
     * One resolved free-variable dependency of a callable/deferred continuation.
     *
     * <p>{@code debugName} exists only for diagnostics. Shadowed declarations may
     * share the same source spelling but must carry distinct binding ids.</p>
     */
    public record CaptureBinding(
            CaptureBindingId bindingId,
            String debugName,
            Types.Type type,
            CaptureAccess access,
            Set<CallableCapability> capabilities) {
        public CaptureBinding {
            Objects.requireNonNull(bindingId, "capture binding id");
            if (debugName == null || debugName.isBlank()) {
                throw new IllegalArgumentException("capture debug name cannot be blank");
            }
            Objects.requireNonNull(type, "capture type");
            Objects.requireNonNull(access, "capture access");
            capabilities = Set.copyOf(capabilities);
        }

        public CaptureBinding merge(CaptureBinding other) {
            Objects.requireNonNull(other, "other capture binding");
            if (!bindingId.equals(other.bindingId)
                    || !debugName.equals(other.debugName)
                    || !type.equals(other.type)) {
                throw new IllegalArgumentException(
                        "cannot merge inconsistent semantic capture bindings");
            }
            java.util.LinkedHashSet<CallableCapability> mergedCapabilities =
                    new java.util.LinkedHashSet<>(capabilities);
            mergedCapabilities.addAll(other.capabilities);
            return new CaptureBinding(
                    bindingId,
                    debugName,
                    type,
                    access.merge(other.access),
                    mergedCapabilities);
        }
    }

    /**
     * Backend-neutral resolved capture plan.
     *
     * <p>Ownership validation and runtime lowering must consume the same plan.
     * Re-running a spelling-based free-variable walk in a backend is not
     * authoritative. The plan never grants actor/network transport authority.</p>
     */
    public record CapturePlan(List<CaptureBinding> bindings) {
        public CapturePlan {
            bindings = List.copyOf(bindings);
            java.util.HashSet<CaptureBindingId> ids = new java.util.HashSet<>();
            for (CaptureBinding binding : bindings) {
                Objects.requireNonNull(binding, "capture binding");
                if (!ids.add(binding.bindingId())) {
                    throw new IllegalArgumentException(
                            "duplicate semantic capture binding id: "
                                    + binding.bindingId().declarationId());
                }
            }
        }

        public static CapturePlan empty() {
            return new CapturePlan(List.of());
        }

        public boolean capturesBorrow() {
            return bindings.stream().anyMatch(binding ->
                    binding.capabilities().contains(CallableCapability.BORROWED));
        }

        public boolean capturesConstructionState() {
            return bindings.stream().anyMatch(binding ->
                    binding.capabilities().contains(CallableCapability.CONSTRUCTION_SCOPED));
        }

        public CapturePlan merge(CapturePlan other) {
            Objects.requireNonNull(other, "other capture plan");
            java.util.LinkedHashMap<CaptureBindingId, CaptureBinding> merged =
                    new java.util.LinkedHashMap<>();
            for (CaptureBinding binding : bindings) {
                merged.put(binding.bindingId(), binding);
            }
            for (CaptureBinding binding : other.bindings) {
                merged.merge(binding.bindingId(), binding, CaptureBinding::merge);
            }
            return new CapturePlan(List.copyOf(merged.values()));
        }

        /** Executable environments are never actor DATA, even when captures are serializable. */
        public boolean actorDataAdmissible() {
            return false;
        }
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

        /**
         * Executable callable values are never portable DATA. Capture metadata
         * may prove properties of referenced data, but cannot mint a network
         * codec, ActorMessageCodec, Sendable proof or supervisor authority.
         */
        public boolean serializableExecutableValue() {
            return false;
        }

        public boolean actorDataAdmissible() {
            return false;
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
