package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.ExceptionBoundaryPolicy.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

final class ExceptionBoundaryPolicyTest {
    @Test void uncaughtActorThrowAndRaiseAreSupervisorPanicsWithTypedOrigin() {
        for (var origin : new ExceptionBoundaryPolicy.Effect[]{THROW, RAISE, PANIC}) {
            var result = ExceptionBoundaryPolicy.actorTerminal(origin);
            assertEquals(PANIC, result.delivered());
            assertEquals(origin, result.origin());
            assertTrue(result.failed());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ExceptionBoundaryPolicy.actorTerminal(RETURN));
    }

    @Test void rootBoundaryNormalizesAfterAllLexicalHandlersHaveBeenExhausted() {
        assertFalse(ExceptionBoundaryPolicy.taskOrRootTerminal(RETURN).failed());
        for (var origin : new ExceptionBoundaryPolicy.Effect[]{THROW, RAISE, PANIC}) {
            var result = ExceptionBoundaryPolicy.taskOrRootTerminal(origin);
            assertEquals(PANIC, result.delivered());
            assertEquals(origin, result.origin());
            assertEquals(1, result.defaultExitStatus());
        }
    }

    @Test void cancelAndHostFatalNeverBecomeOrdinaryGuestPanic() {
        for (var effect : new ExceptionBoundaryPolicy.Effect[]{CANCEL, HOST_FATAL}) {
            assertEquals(effect, ExceptionBoundaryPolicy.actorTerminal(effect).delivered());
            assertEquals(effect, ExceptionBoundaryPolicy.taskOrRootTerminal(effect).delivered());
        }
    }
}
