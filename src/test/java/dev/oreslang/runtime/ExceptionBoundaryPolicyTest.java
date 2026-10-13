package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.ExceptionBoundaryPolicy.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

final class ExceptionBoundaryPolicyTest {
    @Test void unhandledActorThrowAndRaiseAreSupervisorPanicsWithExactOrigin() {
        for (var origin : new ExceptionBoundaryPolicy.Effect[]{THROW, RAISE, PANIC}) {
            var result = ExceptionBoundaryPolicy.actorTerminal(origin, 0xffff_ffffL);
            assertEquals(PANIC, result.delivered());
            assertEquals(origin, result.origin());
            assertEquals(0xffff_ffffL, result.originCode());
            assertEquals(1, result.defaultExitStatus());
            assertTrue(result.failed());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ExceptionBoundaryPolicy.actorTerminal(RETURN));
    }

    @Test void rootBoundaryNormalizesOnlyAfterLexicalHandlingIsExhausted() {
        var ok = ExceptionBoundaryPolicy.taskOrRootTerminal(RETURN, 42);
        assertFalse(ok.failed());
        assertEquals(RETURN, ok.origin());
        assertEquals(42, ok.originCode());
        assertEquals(0, ok.defaultExitStatus());

        for (var origin : new ExceptionBoundaryPolicy.Effect[]{THROW, RAISE, PANIC}) {
            var result = ExceptionBoundaryPolicy.taskOrRootTerminal(origin, 123456);
            assertEquals(PANIC, result.delivered());
            assertEquals(origin, result.origin());
            assertEquals(123456, result.originCode());
            assertEquals(1, result.defaultExitStatus());
        }
    }

    @Test void cancellationAndHostFatalRemainIndependent() {
        for (var effect : new ExceptionBoundaryPolicy.Effect[]{CANCEL, HOST_FATAL}) {
            assertEquals(effect, ExceptionBoundaryPolicy.actorTerminal(effect).delivered());
            assertEquals(effect, ExceptionBoundaryPolicy.taskOrRootTerminal(effect).delivered());
        }
    }

    @Test void invalidOriginCodesAndForgedTerminalPairsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ExceptionBoundaryPolicy.actorTerminal(THROW, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ExceptionBoundaryPolicy.taskOrRootTerminal(PANIC, 0x1_0000_0000L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExceptionBoundaryPolicy.Terminal(THROW, THROW, 0, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExceptionBoundaryPolicy.Terminal(RAISE, RAISE, 0, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExceptionBoundaryPolicy.Terminal(PANIC, CANCEL, 0, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExceptionBoundaryPolicy.Terminal(RETURN, RETURN, 0, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExceptionBoundaryPolicy.Terminal(PANIC, THROW, 0, true, 0));
    }
}
