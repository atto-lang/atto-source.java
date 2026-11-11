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
}
