package dev.oreslang.compiler;

import java.util.Objects;

/**
 * Conservative compile-time dispatch classification for an eventual guest AOT
 * lowering pass. This class does NOT implement native guest code generation.
 *
 * <p>Only a trusted, checked compiler pass may supply receiver proof. Source
 * annotations, macro text, a class name, or a JIT's observed monomorphism are
 * not such proof. The generated call still has to enforce ownership, effects,
 * visibility and capabilities independently of the chosen call instruction.
 */
public final class AotDispatchPlannerV1 {
    private AotDispatchPlannerV1() { }

    public enum CallKind {
        FREE_FUNCTION, STATIC_METHOD, INSTANCE_METHOD, CALLABLE_VALUE,
        ACTOR_MESSAGE, HOST_INTEROP, UNEXPANDED_MACRO
    }

    public enum ReceiverProof {
        NONE,
        EXACT_CONCRETE_RECEIVER,
        CLOSED_WORLD_SINGLE_IMPLEMENTATION
    }

    public enum Linkage {
        FROZEN_CODE_GENERATION,
        LATE_BOUND_GENERATION
    }

    public enum Strategy {
        DIRECT_SYMBOL,
        METHOD_SLOT,
        INDIRECT_CALLABLE,
        VERSIONED_LOOKUP,
        ACTOR_MAILBOX,
        HOST_CAPABILITY_BOUNDARY
    }

    /**
     * resolvedTarget means the type/link pass has validated its ABI and target
     * identity. For instance calls it certifies the method *slot*, not that a
     * particular implementation is the only possible runtime implementation.
     */
    public record Evidence(
            CallKind kind,
            ReceiverProof receiverProof,
            Linkage linkage,
            boolean resolvedTarget) {
        public Evidence {
            Objects.requireNonNull(kind, "call kind");
            Objects.requireNonNull(receiverProof, "receiver proof");
            Objects.requireNonNull(linkage, "linkage");
        }
    }

    /**
     * AOT has an explicit conservative fallback; it must never require a JIT.
     * Reject unresolved named calls and unexpanded macros before emission.
     */
    public static Strategy classify(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return switch (evidence.kind()) {
            case UNEXPANDED_MACRO -> throw new IllegalArgumentException(
                    "macro invocation must be expanded and checked before AOT dispatch lowering");
            case ACTOR_MESSAGE -> Strategy.ACTOR_MAILBOX;
            case HOST_INTEROP -> Strategy.HOST_CAPABILITY_BOUNDARY;
            case CALLABLE_VALUE -> Strategy.INDIRECT_CALLABLE;
            case FREE_FUNCTION, STATIC_METHOD, INSTANCE_METHOD -> {
                if (!evidence.resolvedTarget()) {
                    throw new IllegalArgumentException(
                            "unresolved named call cannot be emitted as an AOT call");
                }
                if (evidence.linkage() == Linkage.LATE_BOUND_GENERATION) {
                    yield Strategy.VERSIONED_LOOKUP;
                }
                if (evidence.kind() == CallKind.INSTANCE_METHOD
                        && evidence.receiverProof() == ReceiverProof.NONE) {
                    yield Strategy.METHOD_SLOT;
                }
                yield Strategy.DIRECT_SYMBOL;
            }
        };
    }
}
