package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/** Typed guest-control slice; compiler source throw remains intentionally gated. */
final class GuestOrdinaryThrowTest {
    private static final class GuestPanic extends RuntimeException { }

    @Test
    void guestErrorIsAUniqueNominalObjectNotAJavaThrowable() {
        GuestErrorValue original = GuestErrorValue.baseError("same text");
        GuestErrorValue other = GuestErrorValue.baseError("same text");
        assertNotSame(original, other);
        assertNotEquals(original, other);
        assertEquals("Error", original.nominalType());
        assertEquals("same text", original.message());
        assertEquals("Error", original.toString());
        assertFalse(java.lang.Throwable.class.isInstance(original));
    }

    @Test
    void directOrdinaryThrowPreservesOriginalGuestErrorReference() {
        GuestErrorValue original = GuestErrorValue.baseError("secret guest payload");
        GuestOrdinaryThrow signal = GuestOrdinaryThrow.signal(original);
        assertSame(original, signal.error());
        assertEquals("ordinary guest throw", signal.getMessage());
        assertNull(signal.getCause());
        assertEquals(0, signal.getStackTrace().length);
        assertFalse(signal.getMessage().contains(original.message()));
        assertTrue(TrapFailurePolicy.isTrappable(signal, GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(signal, GuestPanic.class));
    }

    @Test
    void repeatedRethrowRetainsErrorIdentityButNotJvmSignalIdentity() {
        GuestErrorValue original = GuestErrorValue.baseError("returned Err identity");
        GuestOrdinaryThrow first = GuestOrdinaryThrow.signal(original);
        GuestOrdinaryThrow second = GuestOrdinaryThrow.signal(first.error());
        assertNotSame(first, second);
        assertSame(first.error(), second.error());
    }

    @Test
    void wrappedTypedSignalIsRejectedRatherThanSilentIdentityLoss() {
        GuestErrorValue original = GuestErrorValue.baseError("will not be reconstructed");
        RuntimeException wrapped = new CompletionException(
                new IllegalStateException("host wrapper", GuestOrdinaryThrow.signal(original)));
        assertFalse(TrapFailurePolicy.isTrappable(wrapped, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(wrapped, GuestPanic.class));
    }

    @Test
    void sourceTraceMarkersDoNotBreakDirectSignalButControlFaultDoes() {
        GuestOrdinaryThrow signal = GuestOrdinaryThrow.signal(
                GuestErrorValue.baseError("trace stays guest-owned"));
        SourceBoundaryTrace.record(signal, "sample.atto", "f", "throw");
        assertTrue(TrapFailurePolicy.isTrappable(signal, GuestPanic.class));
        assertSame(signal.error(), signal.error());
        signal.addSuppressed(new CancellationException("scheduler cancellation"));
        assertFalse(TrapFailurePolicy.isTrappable(signal, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(signal, GuestPanic.class));
    }

    @Test
    void unwrapSignalsPreserveNominalIdentityAndOriginalPayloadWithoutFormatting() {
        Object payload = new Object() {
            @Override public String toString() { throw new AssertionError("payload formatted"); }
        };
        GuestErrorValue option = GuestErrorValue.optionUnwrap();
        GuestErrorValue result = GuestErrorValue.resultUnwrap(payload);
        assertEquals("OptionUnwrapError", option.nominalType());
        assertEquals("ResultUnwrapError", result.nominalType());
        assertNull(option.payload());
        assertSame(payload, result.payload());
        assertSame(result, GuestOrdinaryThrow.signal(result).error());
        assertEquals("ResultUnwrapError", result.toString());
        assertNotSame(option, GuestErrorValue.optionUnwrap());
        assertTrue(TrapFailurePolicy.isTrappable(GuestOrdinaryThrow.signal(option), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isTrappable(GuestOrdinaryThrow.signal(result), GuestPanic.class));
    }

    @Test
    void terminalSuppressionStillBypassesCheckedUnwrapTrap() {
        GuestOrdinaryThrow signal = GuestOrdinaryThrow.signal(GuestErrorValue.resultUnwrap("data"));
        signal.addSuppressed(new GuestPanic());
        assertFalse(TrapFailurePolicy.isTrappable(signal, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(signal, GuestPanic.class));
    }

    private static final class OtherTaskTerminal extends RuntimeException
            implements UntrappableTaskFailure { }

    @Test
    void directlySuppressedTypedCleanupPreservesPrimaryError() {
        GuestErrorValue original = GuestErrorValue.resultUnwrap("primary");
        GuestOrdinaryThrow primary = GuestOrdinaryThrow.signal(original);
        GuestOrdinaryThrow cleanup = GuestOrdinaryThrow.signal(GuestErrorValue.optionUnwrap());
        primary.addSuppressed(cleanup);
        cleanup.addSuppressed(GuestOrdinaryThrow.signal(GuestErrorValue.resultUnwrap("nested")));
        assertTrue(TrapFailurePolicy.isTrappable(primary, GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(primary, GuestPanic.class));
        assertSame(original, primary.error());
        cleanup.addSuppressed(new OtherTaskTerminal());
        assertFalse(TrapFailurePolicy.isTrappable(primary, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(primary, GuestPanic.class));
    }

    @Test
    void typedCleanupBehindHostWrapperCannotAuthenticateOrdinaryFailure() {
        GuestOrdinaryThrow primary = GuestOrdinaryThrow.signal(GuestErrorValue.optionUnwrap());
        primary.addSuppressed(new CompletionException(
                GuestOrdinaryThrow.signal(GuestErrorValue.resultUnwrap("wrapped"))));
        assertFalse(TrapFailurePolicy.isTrappable(primary, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(primary, GuestPanic.class));
    }

    @Test
    void independentRuntimeTerminalMarkerCannotBeCaptured() {
        OtherTaskTerminal terminal = new OtherTaskTerminal();
        assertFalse(TrapFailurePolicy.isTrappable(terminal, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(terminal, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isTrappable(new CompletionException(terminal), GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(new CompletionException(terminal), GuestPanic.class));
    }

    @Test
    void invalidPayloadCannotCreateTypedGuestSignal() {
        assertThrows(NullPointerException.class, () -> GuestErrorValue.baseError(null));
        assertThrows(IllegalArgumentException.class,
                () -> GuestErrorValue.baseError("x".repeat(4097)));
        assertThrows(NullPointerException.class, () -> GuestOrdinaryThrow.signal(null));
    }
}
