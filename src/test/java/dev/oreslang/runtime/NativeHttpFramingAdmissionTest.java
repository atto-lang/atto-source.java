package dev.oreslang.runtime;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NativeHttpFramingAdmissionTest {
    private static Headers valid() {
        Headers headers = new Headers();
        headers.add("Host", "localhost:8080");
        headers.add("X-Request-Id", "good");
        return headers;
    }

    @Test
    void admitsOneHostAndAValidBoundedHeaderSet() {
        Headers headers = valid();
        headers.add("Content-Length", "8");
        assertDoesNotThrow(() -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 8));
    }

    @Test
    void rejectsDuplicateHostEvenWhenBothValuesAreIndividuallyValid() {
        Headers headers = valid();
        headers.add("Host", "attacker.test");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
    }

    @Test
    void rejectsCommaJoinedHostThatCouldHideCoalescedDuplicates() {
        Headers headers = valid();
        headers.set("Host", "localhost:8080,attacker.test");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
    }

    @Test
    void rejectsHttp11WithoutHostButAllowsLegacyHttp10() {
        Headers headers = new Headers();
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
        assertDoesNotThrow(() -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.0", 1024));
    }

    @Test
    void rejectsSingleTwelveKilobyteHeaderBeforeGuestDispatch() {
        Headers headers = valid();
        headers.add("X-Long", "a".repeat(12_000));
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
    }

    @Test
    void admitsBoundaryLengthHeaderButRejectsOneByteMore() {
        Headers headers = valid();
        headers.add("X-Long", "a".repeat(8192 - "X-Long".length()));
        assertDoesNotThrow(() -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
        headers.set("X-Long", "a".repeat(8193 - "X-Long".length()));
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024));
    }

    @Test
    void retainsAggregateHeaderAndCountLimits() {
        Headers large = valid();
        for (int i = 0; i < 5; i++) large.add("X-Field-" + i, "a".repeat(8000));
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(large, "HTTP/1.1", 1024));

        Headers many = valid();
        for (int i = 0; i < 100; i++) many.add("X-Field-" + i, "x");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(many, "HTTP/1.1", 1024));
    }

    @Test
    void rejectsNegativeOrOverQuotaContentLength() {
        Headers negative = valid();
        negative.add("Content-Length", "-1");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(negative, "HTTP/1.1", 1024));
        Headers oversized = valid();
        oversized.add("Content-Length", "1025");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(oversized, "HTTP/1.1", 1024));
    }

    @Test
    void mapsMalformedRequestBodyStreamToClientErrorOnly() {
        IOException malformedChunk = new IOException("invalid chunk size");
        assertEquals(400, NativeHttp.operationFailureStatus(malformedChunk, true));
        assertEquals(500, NativeHttp.operationFailureStatus(malformedChunk, false),
                "response-side I/O failures must not be disguised as client framing errors");
        assertEquals(413, NativeHttp.operationFailureStatus(
                new IllegalArgumentException("body too large"), true));
        assertEquals(500, NativeHttp.operationFailureStatus(
                new IllegalStateException("handler failure"), true));
    }
}
