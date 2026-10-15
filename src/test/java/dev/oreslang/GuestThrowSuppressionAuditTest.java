package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Exercises the evaluator's private guest-throw boundary without ever
 * constructing a real actor-owned Error outside its arena.
 *
 * The synthetic GuestThrowSignal has a null guest payload only because the
 * redaction logic must never dereference or retain that payload.
 */
final class GuestThrowSuppressionAuditTest {
    @Test
    void cleanupSuppressionCannotRetainDirectOrWrappedGuestError() throws Exception {
        Class<?> evaluator = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Class<?> signalType = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Evaluator$GuestThrowSignal");
        Constructor<?> createSignal = signalType.getDeclaredConstructors()[0];
        createSignal.setAccessible(true);
        Throwable guest = (Throwable) createSignal.newInstance(new Object[] {null});

        Method scrub = evaluator.getDeclaredMethod("safeSuppressedFailure", Throwable.class);
        scrub.setAccessible(true);

        Throwable direct = (Throwable) scrub.invoke(null, guest);
        assertNotSame(guest, direct);
        assertNull(direct.getCause());
        assertEquals(0, direct.getSuppressed().length);
        assertEquals(0, direct.getStackTrace().length);

        RuntimeException nested = new CompletionException(
                new IllegalStateException("host wrapper", guest));
        assertSame(direct, scrub.invoke(null, nested),
                "a wrapped guest Error must not enter a host suppression graph");

        IllegalStateException withSuppressed = new IllegalStateException("host");
        withSuppressed.addSuppressed(guest);
        assertSame(direct, scrub.invoke(null, withSuppressed));

        IllegalStateException cleanHostFailure = new IllegalStateException("safe");
        assertSame(cleanHostFailure, scrub.invoke(null, cleanHostFailure),
                "ordinary host diagnostics should remain intact");
    }
}
