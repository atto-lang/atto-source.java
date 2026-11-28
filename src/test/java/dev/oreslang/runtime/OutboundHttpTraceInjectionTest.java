package dev.oreslang.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the actual HTTP request-header admission helper. The async native
 * worker receives the returned immutable snapshot, not a thread-local.
 * A real socket and OTLP CLIENT span remain separate proof requirements.
 */
final class OutboundHttpTraceInjectionTest {
    @Test
    void insertsW3CChildWithoutUserlandTelemetryCode() {
        TraceParent parent = TraceParent.newRoot(true);
        var headers = OutboundHttpRequest.withTraceparent(
                List.of(Map.entry("Accept", "application/octet-stream")), parent);
        assertEquals(2, headers.size());
        assertEquals("Accept", headers.getFirst().getKey());
        assertEquals("traceparent", headers.get(1).getKey());
        TraceParent child = TraceParent.parse(headers.get(1).getValue()).orElseThrow();
        assertEquals(parent.traceId(), child.traceId());
        assertNotEquals(parent.spanId(), child.spanId());
        assertTrue(child.sampled());
    }

    @Test
    void noActiveParentPassesOriginalHeadersThroughWithoutAllocation() {
        var original = List.of(Map.entry("Accept", "*/*"));
        assertSame(original, OutboundHttpRequest.withTraceparent(original, null));
    }

    @Test
    void unsampledContextStillPropagatesWithoutLocalRecordedSpan() {
        TraceParent parent = TraceParent.newRoot(false);
        var headers = OutboundHttpRequest.withTraceparent(List.of(), parent);
        TraceParent outgoing = TraceParent.parse(headers.getFirst().getValue()).orElseThrow();
        assertFalse(outgoing.sampled());
        assertEquals(parent.traceId(), outgoing.traceId());
    }

    @Test
    void userSuppliedTracingHeadersCannotOverwriteRuntimeOwnedParent() {
        TraceParent parent = TraceParent.newRoot(true);
        for (String key : List.of("TraceParent", "traceparent", "TRACEPARENT",
                "tracestate", "TraceState")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OutboundHttpRequest.withTraceparent(
                            List.of(Map.entry(key, "spoofed")), parent));
            assertTrue(error.getMessage().contains("runtime-owned"),
                    error.getMessage());
        }
    }

    @Test
    void injectionRespectsOriginalTransportHeaderBudgets() {
        TraceParent parent = TraceParent.newRoot(true);
        var full = new java.util.ArrayList<Map.Entry<String, String>>();
        for (int i = 0; i < 100; i++) full.add(Map.entry("X-Budget-" + i, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.withTraceparent(full, parent));

        // 11-byte "traceparent" name + 55-byte value + 4 framing bytes = 70.
        String padded = "z".repeat(32_768 - 70 - 5);
        var exact = OutboundHttpRequest.withTraceparent(
                List.of(Map.entry("X", padded)), parent);
        assertEquals(2, exact.size());
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.withTraceparent(
                        List.of(Map.entry("X", padded + "z")), parent));
    }
}
