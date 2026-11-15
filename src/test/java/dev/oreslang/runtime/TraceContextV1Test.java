package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-substrate W3C v00 vectors; not an OTLP or guest-language proof. */
final class TraceContextV1Test {
    private static final String SAMPLED =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void parsesAndSerializesCanonicalV00() {
        TraceContextV1 context = TraceContextV1.parseTraceparent(SAMPLED);
        assertTrue(context.sampled());
        assertEquals(SAMPLED, context.traceparent());
        assertEquals(1, context.flags());
        assertEquals("00f067aa0ba902b7", context.traceparent().substring(36, 52));
    }

    @Test
    void permitsValidUnsampledAndCanonicalizesUppercaseIds() {
        String input = SAMPLED.toUpperCase().replaceFirst("^00", "00");
        TraceContextV1 context = TraceContextV1.parseTraceparent(input);
        assertTrue(context.sampled());
        assertEquals(SAMPLED, context.traceparent());
        TraceContextV1 unsampled = TraceContextV1.parseTraceparent(
                SAMPLED.substring(0, 53) + "00");
        assertFalse(unsampled.sampled());
    }

    @Test
    void rejectsInvalidAndZeroIdsAndInjectedHeaders() {
        String[] bad = {
                null, "", " ", " " + SAMPLED, SAMPLED + " ",
                "ff" + SAMPLED.substring(2),
                "01" + SAMPLED.substring(2),
                "00_4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                SAMPLED.substring(0, 54),
                SAMPLED + "-extra",
                SAMPLED + "\r\nx-bad: 1",
                SAMPLED.replace("4bf9", "zbf9")
        };
        for (String input : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> TraceContextV1.parseTraceparent(input), String.valueOf(input));
        }
    }

    @Test
    void validatesConstructedIdentityAndFlags() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContextV1(0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContextV1(0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContextV1(0, 1, 1, 256));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceContextV1(0, 1, 1, -1));
    }
}
