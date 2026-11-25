package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncTrapAdmissionTest {
    private static GuestOrdinaryThrow signal() {
        return GuestOrdinaryThrow.signal(GuestErrorValue.baseError("ordinary"));
    }

    private static final class HostFailure extends RuntimeException {
        @Override public Throwable getCause() { throw new AssertionError("host callback"); }
    }

    @Test void directTypedSignalRequiresRetiredCleanupAndPreservesIdentity() {
        GuestErrorValue error = GuestErrorValue.baseError("ordinary");
        GuestOrdinaryThrow ordinary = GuestOrdinaryThrow.signal(error);
        assertTrue(AsyncTrapAdmission.accepts(ordinary, false, true));
        assertSame(error, ordinary.error());
        assertFalse(AsyncTrapAdmission.accepts(ordinary, true, true));
        assertFalse(AsyncTrapAdmission.accepts(ordinary, false, false));
        assertFalse(AsyncTrapAdmission.accepts(null, false, true));
    }

    @Test void wrappedAndSuppressedHostOrControlFailuresAreNeverAdmitted() {
        GuestOrdinaryThrow ordinary = signal();
        for (Throwable failure : new Throwable[]{new HostFailure(), new CancellationException(),
                new ArithmeticException(), new AssertionError(), new OutOfMemoryError(),
                new CompletionException(ordinary), new ExecutionException(ordinary)}) {
            assertFalse(AsyncTrapAdmission.accepts(failure, false, true));
            GuestOrdinaryThrow primary = signal();
            primary.addSuppressed(failure);
            assertFalse(AsyncTrapAdmission.accepts(primary, false, true));
        }
    }

    @Test void onlyBoundedInertSourceMarkersMayAccompanyDirectThrow() {
        GuestOrdinaryThrow traced = signal();
        for (int i = 0; i < 100; i++)
            SourceBoundaryTrace.record(traced, "fixture.atto", "work", "await");
        assertEquals(64, traced.getSuppressed().length);
        assertTrue(AsyncTrapAdmission.accepts(traced, false, true));
        traced.addSuppressed(new CancellationException());
        assertFalse(AsyncTrapAdmission.accepts(traced, false, true));
        GuestOrdinaryThrow two = signal();
        two.addSuppressed(signal());
        assertFalse(AsyncTrapAdmission.accepts(two, false, true));
    }
}
