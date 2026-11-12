package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte ownership and 1 MiB admission (not network or Atto guest execution).
 * No mutable source array is kept by the asynchronous transport.
 */
final class OutboundHttpRequestBodyAdmissionTest {
    @Test
    void utf8AndPortableByteListAreSnapshottedBeforeAsyncWorker() {
        assertArrayEquals(new byte[0], OutboundHttpRequest.requestBodyBytes(null));
        assertArrayEquals(new byte[0], OutboundHttpRequest.requestBodyBytes(""));
        assertArrayEquals("héllø".getBytes(StandardCharsets.UTF_8),
                OutboundHttpRequest.requestBodyBytes("héllø"));
        ArrayList<Integer> source = new ArrayList<>(List.of(0, 1, 127, 255));
        byte[] snapshot = OutboundHttpRequest.requestBodyBytes(source);
        source.set(0, 42);
        assertArrayEquals(new byte[] {0, 1, 127, (byte) 255}, snapshot,
                "mutation after admission must not alter physical I/O bytes");
    }

    @Test
    void limitsAreMeasuredInEncodedBytesAndRejectBeforeNetwork() {
        final int max = PinnedHttpTransport.MAX_REQUEST_BODY_BYTES;
        assertEquals(max, OutboundHttpRequest.requestBodyBytes(
                "a".repeat(max)).length);
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes("a".repeat(max + 1)));
        // UTF-8 may use multiple octets even when char count is within budget.
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes("é".repeat(max / 2 + 1)));
        assertEquals(max, OutboundHttpRequest.requestBodyBytes(
                java.util.Collections.nCopies(max, 255)).length);
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes(
                        java.util.Collections.nCopies(max + 1, 0)));
    }

    @Test
    void invalidOctetsAndUnsupportedObjectsFailClosed() {
        for (Object invalid : List.of(
                List.of(-1), List.of(256), List.of(1.5), List.of("1"),
                List.of(true), List.of(1, "2"),
                List.of(1L, null))) {
            // List.of(null) would fail before reaching validation, so the
            // last vector is built separately below if needed.
            assertThrows(IllegalArgumentException.class,
                    () -> OutboundHttpRequest.requestBodyBytes(invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes(5));
        assertThrows(IllegalArgumentException.class,
                () -> OutboundHttpRequest.requestBodyBytes(
                        java.util.Arrays.asList(1, null)));
    }
}
