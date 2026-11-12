package dev.oreslang.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict HTTP SERVER admission helper; NOT a full request/actor/OTLP guest test.
 * The admission callback stores this immutable value with the accepted
 * Exchange, and the built-in Handler.dispatch sends it as mailbox metadata.
 */
final class NativeHttpInboundTraceContextTest {
    private static final String REMOTE =
            "00-0123456789abcdef0123456789abcdef-abcdef0123456789-01";

    @Test
    void incomingW3CHeaderCreatesDistinctChildFromValidRemoteParent() {
        TraceParent remote = TraceParent.parse(REMOTE).orElseThrow();
        TraceParent accepted =
                NativeHttp.admittedServerTraceContext(List.of(REMOTE), true);
        assertNotNull(accepted);
        assertEquals(remote.traceId(), accepted.traceId());
        assertNotEquals(remote.spanId(), accepted.spanId());
        assertTrue(accepted.sampled());
    }

    @Test
    void disabledTracingNeitherExtractsNorGeneratesContext() {
        assertNull(NativeHttp.admittedServerTraceContext(List.of(REMOTE), false));
        assertNull(NativeHttp.admittedServerTraceContext(null, false));
        assertNull(NativeHttp.admittedServerTraceContext(
                List.of("not-a-traceparent"), false));
    }

    @Test
    void maliciousOrDuplicateHeadersNeverBecomeTrustedParents() {
        for (List<String> values : List.of(
                List.of(REMOTE, REMOTE),
                List.of("00-0123456789abcdef0123456789abcdef-0000000000000000-01"),
                List.of("00-0123456789abcdef0123456789abcdef-abcdef0123456789-0g"),
                List.of(REMOTE + "\r\nX-Injected: evil"),
                List.of())) {
            TraceParent result = NativeHttp.admittedServerTraceContext(values, true);
            assertNotNull(result);
            assertNotEquals(
                    TraceParent.parse(REMOTE).orElseThrow().traceId(),
                    result.traceId(),
                    "invalid header must start an independent trace");
        }
        assertNotNull(NativeHttp.admittedServerTraceContext(null, true));
    }

    @Test
    void validUnsampledRemoteParentStillPropagatesWithoutLocalEvents() {
        String incoming = REMOTE.substring(0, 53) + "00";
        TraceParent remote = TraceParent.parse(incoming).orElseThrow();
        TraceParent child = NativeHttp.admittedServerTraceContext(
                List.of(incoming), true);
        assertEquals(remote.traceId(), child.traceId());
        assertFalse(child.sampled());
        assertNotEquals(remote.spanId(), child.spanId());
    }
}
