package dev.oreslang.runtime;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3C header shape/secure-ID primitive only. No real HTTP, actor or OTLP
 * propagation is implied by these tests.
 */
final class TraceParentTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SPAN = "abcdef0123456789";
    private static final String VALID = "00-" + TRACE + "-" + SPAN + "-01";

    @Test
    void strictV00ParsingAndFormattingRoundTrip() {
        TraceParent p = TraceParent.parse(VALID).orElseThrow();
        assertEquals(TRACE, p.traceId());
        assertEquals(SPAN, p.spanId());
        assertEquals(1, p.flags());
        assertTrue(p.sampled());
        assertEquals(VALID, p.toHeader());
        assertEquals(p, TraceParent.parse(p.toHeader()).orElseThrow());
    }

    @Test
    void rejectsMalformedOversizedUnknownUppercaseAndZeroIdentifiers() {
        for (String bad : new String[] {
                "", " ", VALID + " ", " " + VALID,
                "ff-" + TRACE + "-" + SPAN + "-01",
                "00-" + TRACE.toUpperCase() + "-" + SPAN + "-01",
                "00-" + "0".repeat(32) + "-" + SPAN + "-01",
                "00-" + TRACE + "-" + "0".repeat(16) + "-01",
                "00-" + TRACE + "-" + SPAN + "-0g",
                VALID + "-extra", VALID.substring(0, 54),
                "00-" + TRACE + "-" + SPAN + "-01\r\nX: evil"
        }) {
            assertTrue(TraceParent.parse(bad).isEmpty(), bad);
        }
        assertTrue(TraceParent.parse(null).isEmpty());
    }

    @Test
    void createsNonzeroSecureRootAndPreservesUnsampledContextWithChild() {
        TraceParent sampled = TraceParent.newRoot(true);
        TraceParent unsampled = TraceParent.newRoot(false);
        assertTrue(sampled.sampled());
        assertFalse(unsampled.sampled());
        assertEquals(55, sampled.toHeader().length());
        assertEquals(sampled, TraceParent.parse(sampled.toHeader()).orElseThrow());

        TraceParent child = unsampled.newChild();
        assertEquals(unsampled.traceId(), child.traceId());
        assertNotEquals(unsampled.spanId(), child.spanId());
        assertFalse(child.sampled());
        assertEquals(child, TraceParent.parse(child.toHeader()).orElseThrow());
    }

    @Test
    void newlyMintedSpanIdsAreNotConstantAcrossIndependentRoots() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 64; i++) {
            TraceParent next = TraceParent.newRoot(i % 2 == 0);
            assertTrue(seen.add(next.spanId()));
            assertNotEquals("0".repeat(32), next.traceId());
            assertNotEquals("0".repeat(16), next.spanId());
        }
    }

    @Test
    void immutableConstructorCannotBypassValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceParent(TRACE, SPAN, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceParent(TRACE, SPAN, 256));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceParent(TRACE, SPAN.toUpperCase(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceParent("0".repeat(32), SPAN, 1));
    }
}
