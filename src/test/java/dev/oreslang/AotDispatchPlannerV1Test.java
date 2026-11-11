package dev.oreslang;

import dev.oreslang.compiler.AotDispatchPlannerV1;
import org.junit.jupiter.api.Test;

import static dev.oreslang.compiler.AotDispatchPlannerV1.*;
import static org.junit.jupiter.api.Assertions.*;

final class AotDispatchPlannerV1Test {
    private static Evidence known(CallKind kind, ReceiverProof receiver) {
        return new Evidence(kind, receiver, Linkage.FROZEN_CODE_GENERATION, true);
    }

    @Test
    void namedFreeAndStaticCallsAreDirectOnlyWithinFrozenCodeGeneration() {
        assertEquals(Strategy.DIRECT_SYMBOL,
                AotDispatchPlannerV1.classify(known(CallKind.FREE_FUNCTION, ReceiverProof.NONE)));
        assertEquals(Strategy.DIRECT_SYMBOL,
                AotDispatchPlannerV1.classify(known(CallKind.STATIC_METHOD, ReceiverProof.NONE)));
        for (CallKind kind : new CallKind[] {CallKind.FREE_FUNCTION, CallKind.STATIC_METHOD}) {
            assertEquals(Strategy.VERSIONED_LOOKUP, AotDispatchPlannerV1.classify(
                    new Evidence(kind, ReceiverProof.NONE, Linkage.LATE_BOUND_GENERATION, true)));
        }
    }

    @Test
    void openReceiverNeverBecomesDirectFromMethodNameOrArityAlone() {
        assertEquals(Strategy.METHOD_SLOT,
                AotDispatchPlannerV1.classify(known(CallKind.INSTANCE_METHOD, ReceiverProof.NONE)));
        assertEquals(Strategy.DIRECT_SYMBOL, AotDispatchPlannerV1.classify(
                known(CallKind.INSTANCE_METHOD, ReceiverProof.EXACT_CONCRETE_RECEIVER)));
        assertEquals(Strategy.DIRECT_SYMBOL, AotDispatchPlannerV1.classify(
                known(CallKind.INSTANCE_METHOD, ReceiverProof.CLOSED_WORLD_SINGLE_IMPLEMENTATION)));
    }

    @Test
    void hotReloadInvalidatesEvenAnExactReceiverDirectBinding() {
        assertEquals(Strategy.VERSIONED_LOOKUP, AotDispatchPlannerV1.classify(
                new Evidence(CallKind.INSTANCE_METHOD, ReceiverProof.EXACT_CONCRETE_RECEIVER,
                        Linkage.LATE_BOUND_GENERATION, true)));
    }

    @Test
    void actorMailboxesAndHostCapabilityBoundariesAreNotGuestMethodCalls() {
        assertEquals(Strategy.ACTOR_MAILBOX,
                AotDispatchPlannerV1.classify(known(CallKind.ACTOR_MESSAGE,
                        ReceiverProof.EXACT_CONCRETE_RECEIVER)));
        assertEquals(Strategy.HOST_CAPABILITY_BOUNDARY,
                AotDispatchPlannerV1.classify(known(CallKind.HOST_INTEROP,
                        ReceiverProof.EXACT_CONCRETE_RECEIVER)));
        assertEquals(Strategy.INDIRECT_CALLABLE,
                AotDispatchPlannerV1.classify(known(CallKind.CALLABLE_VALUE,
                        ReceiverProof.EXACT_CONCRETE_RECEIVER)));
    }

    @Test
    void unsupportedMacroAndUnresolvedNamedCallsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> AotDispatchPlannerV1.classify(
                known(CallKind.UNEXPANDED_MACRO, ReceiverProof.NONE)));
        for (CallKind kind : new CallKind[] {
                CallKind.INSTANCE_METHOD, CallKind.FREE_FUNCTION, CallKind.STATIC_METHOD}) {
            assertThrows(IllegalArgumentException.class, () -> AotDispatchPlannerV1.classify(
                    new Evidence(kind, ReceiverProof.EXACT_CONCRETE_RECEIVER,
                            Linkage.FROZEN_CODE_GENERATION, false)));
        }
        assertThrows(NullPointerException.class, () -> AotDispatchPlannerV1.classify(null));
    }

    @Test
    void exhaustiveDispatchEvidenceMatrixCannotPromoteUnprovedVirtualCalls() {
        // All combinations matter: a JIT profile or a source receiver name
        // must not turn open-world/late-generation virtual dispatch into a
        // direct symbol. This is source-policy coverage, not guest AOT proof.
        int checked = 0;
        for (CallKind kind : CallKind.values()) {
            for (ReceiverProof proof : ReceiverProof.values()) {
                for (Linkage linkage : Linkage.values()) {
                    for (boolean resolved : new boolean[] {false, true}) {
                        Evidence evidence = new Evidence(kind, proof, linkage, resolved);
                        checked++;
                        if (kind == CallKind.UNEXPANDED_MACRO ||
                                (!resolved && (kind == CallKind.INSTANCE_METHOD ||
                                        kind == CallKind.FREE_FUNCTION ||
                                        kind == CallKind.STATIC_METHOD))) {
                            assertThrows(IllegalArgumentException.class,
                                    () -> AotDispatchPlannerV1.classify(evidence));
                            continue;
                        }
                        Strategy selected = AotDispatchPlannerV1.classify(evidence);
                        if (kind == CallKind.INSTANCE_METHOD) {
                            Strategy expected = linkage == Linkage.LATE_BOUND_GENERATION
                                    ? Strategy.VERSIONED_LOOKUP
                                    : proof == ReceiverProof.NONE ? Strategy.METHOD_SLOT
                                    : Strategy.DIRECT_SYMBOL;
                            assertEquals(expected, selected, evidence.toString());
                        }
                        if (kind == CallKind.ACTOR_MESSAGE) {
                            assertEquals(Strategy.ACTOR_MAILBOX, selected);
                        }
                        if (kind == CallKind.HOST_INTEROP) {
                            assertEquals(Strategy.HOST_CAPABILITY_BOUNDARY, selected);
                        }
                        if (kind == CallKind.CALLABLE_VALUE) {
                            assertEquals(Strategy.INDIRECT_CALLABLE, selected);
                        }
                        if (linkage == Linkage.LATE_BOUND_GENERATION &&
                                (kind == CallKind.INSTANCE_METHOD ||
                                 kind == CallKind.FREE_FUNCTION ||
                                 kind == CallKind.STATIC_METHOD)) {
                            assertNotEquals(Strategy.DIRECT_SYMBOL, selected);
                        }
                    }
                }
            }
        }
        assertEquals(CallKind.values().length * ReceiverProof.values().length *
                Linkage.values().length * 2, checked);
    }
}
