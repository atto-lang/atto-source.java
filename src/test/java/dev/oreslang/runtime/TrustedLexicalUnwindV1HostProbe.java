package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** No external libraries: execute this probe on JDK21 and in exact-blob CI. */
public final class TrustedLexicalUnwindV1HostProbe {
    private TrustedLexicalUnwindV1HostProbe() { }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] ignored) {
        AtomicInteger cleanups = new AtomicInteger();
        int value = TrustedLexicalUnwindV1.run(() -> 42, cleanups::incrementAndGet);
        check(value == 42 && cleanups.get() == 1, "normal return cleanup once");

        RuntimeException bodyFailure = new IllegalArgumentException("body failed");
        RuntimeException cleanupFailure = new IllegalStateException("cleanup failed");
        try {
            TrustedLexicalUnwindV1.run(() -> { throw bodyFailure; },
                    () -> { cleanups.incrementAndGet(); throw cleanupFailure; });
            throw new AssertionError("lost original failure");
        } catch (IllegalArgumentException caught) {
            check(caught == bodyFailure, "original body exception identity lost");
            check(caught.getSuppressed().length == 1
                    && caught.getSuppressed()[0] == cleanupFailure,
                    "cleanup failure not suppressed on original");
        }
        check(cleanups.get() == 2, "exception cleanup once");

        SecurityException securityFailure = new SecurityException("trusted cleanup denied");
        try {
            TrustedLexicalUnwindV1.run(() -> { throw new IllegalArgumentException("body"); },
                    () -> { throw securityFailure; });
            throw new AssertionError("authority failure hidden");
        } catch (SecurityException caught) {
            check(caught == securityFailure && caught.getSuppressed().length == 1,
                    "security failure must outrank ordinary error");
        }

        RuntimeException shared = new IllegalArgumentException("same instance");
        try {
            TrustedLexicalUnwindV1.run(() -> { throw shared; }, () -> { throw shared; });
            throw new AssertionError("shared failure lost");
        } catch (IllegalArgumentException caught) {
            check(caught == shared && caught.getSuppressed().length == 0,
                    "self-suppression attempted");
        }

        var events = new ArrayList<Integer>();
        int nested = TrustedLexicalUnwindV1.run(() -> {
            events.add(1);
            return TrustedLexicalUnwindV1.run(() -> {
                events.add(2);
                return 7;
            }, () -> events.add(3));
        }, () -> events.add(4));
        check(nested == 7 && events.equals(List.of(1, 2, 3, 4)), "nested cleanup ordering");

        try {
            TrustedLexicalUnwindV1.<Integer>run(() -> 42,
                    () -> { throw new IllegalStateException("failed cleanup after return"); });
            throw new AssertionError("cleanup error silently accepted");
        } catch (IllegalStateException expected) {
            check("failed cleanup after return".equals(expected.getMessage()),
                    "normal cleanup failure identity");
        }
        System.out.println("ATTO_TRUSTED_LEXICAL_UNWIND_JDK21_PASS");
    }
}
