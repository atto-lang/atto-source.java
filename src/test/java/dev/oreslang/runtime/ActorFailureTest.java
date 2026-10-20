package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

        assertEquals(
                ActorFailure.Kind.NONE,
                ActorFailure.kindFromTypeName(""));
        assertEquals(
                ActorFailure.Kind.RUNTIME_FAILURE,
                ActorFailure.kindFromTypeName("example.UnknownFailure"));
    }

    @Test
    @Timeout(30)
    void actorTerminalStateAndJournalRetainTypedFailureIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertTerminalKind(
                    runtime,
                    ActorFailure.Panic::new,
                    "panic",
                    ActorFailure.Kind.PANIC);
            assertTerminalKind(
                    runtime,
                    ActorFailure.Raised::new,
                    "raise",
                    ActorFailure.Kind.RAISE);
            assertTerminalKind(
                    runtime,
                    ActorFailure.Thrown::new,
                    "throw",
                    ActorFailure.Kind.THROW);
            assertTerminalKind(
                    runtime,
                    IllegalStateException::new,
                    "runtime",
                    ActorFailure.Kind.RUNTIME_FAILURE);
        }
    }

    private static void assertTerminalKind(
            ActorRuntime runtime,
            java.util.function.Function<String, ? extends RuntimeException> failure,
            String label,
            ActorFailure.Kind expected) throws Exception {
        ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                () -> (message, context) -> {
                    throw failure.apply(label);
                });
        actor.send("fail");
        assertTrue(actor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        ActorRuntime.ActorTerminalState retained =
                actor.terminalState().orElseThrow();
        assertEquals(ActorRuntime.ActorTerminationKind.FAILED, retained.terminationKind());
        assertEquals(expected, retained.failureKind());

        ActorRuntime.ActorTerminationEvent event =
                runtime.pollTerminationEvent().orElseThrow();
        assertEquals(actor.id(), event.actorId());
        assertEquals(actor.generation(), event.generation());
        assertEquals(ActorRuntime.ActorTerminationKind.FAILED, event.terminationKind());
        assertEquals(expected, event.failureKind());
        assertEquals(retained.failureType(), event.failureType());
    }
}
