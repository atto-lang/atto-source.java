package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ActorFailureTest {

    @Test
    void explicitFailureSignalsHaveStableKindsAndCatchPolicies() {
        var thrown = new ActorFailure.Thrown("throw");
        var raised = new ActorFailure.Raised("raise");
        var panic = new ActorFailure.Panic("panic");

        assertEquals(ActorFailure.Kind.THROW, ActorFailure.kindOf(thrown));
        assertEquals(ActorFailure.Kind.RAISE, ActorFailure.kindOf(raised));
        assertEquals(ActorFailure.Kind.PANIC, ActorFailure.kindOf(panic));
        assertEquals(
                ActorFailure.Kind.RUNTIME_FAILURE,
                ActorFailure.kindOf(new IllegalStateException("runtime")));

        assertTrue(TrapFailurePolicy.isTrappable(
                thrown, ActorFailure.Panic.class),
                "ordinary throw remains recoverable by trap/catch policy");
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                thrown, ActorFailure.Panic.class));

        assertFalse(TrapFailurePolicy.isTrappable(
                raised, ActorFailure.Panic.class),
                "raise bypasses ordinary trap");
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                raised, ActorFailure.Panic.class),
                "raise bypasses ordinary guest catch");

        assertFalse(TrapFailurePolicy.isTrappable(
                panic, ActorFailure.Panic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                panic, ActorFailure.Panic.class));

        assertEquals(ActorFailure.Kind.NONE, ActorFailure.kindFromTypeName(""));
        assertEquals(
                ActorFailure.Kind.RUNTIME_FAILURE,
                ActorFailure.kindFromTypeName("example.UnknownFailure"));
    }
}
