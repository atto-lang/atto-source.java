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
    void rejectsAmbiguousContentLengthEvenIfTransportParserAcceptedIt() {
        Headers repeated = valid();
        repeated.add("Content-Length", "0");
        repeated.add("Content-Length", "0");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(repeated, "HTTP/1.1", 1024));

        Headers joined = valid();
        joined.add("Content-Length", "0,0");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(joined, "HTTP/1.1", 1024));

        Headers signed = valid();
        signed.add("Content-Length", "+1");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(signed, "HTTP/1.1", 1024));

        Headers nondigit = valid();
        nondigit.add("Content-Length", "1x");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(nondigit, "HTTP/1.1", 1024));
    }

    @Test
    void requiresExactlyOneSupportedTransferEncodingWithoutContentLength() {
        Headers chunked = valid();
        chunked.add("Transfer-Encoding", "chunked");
        assertDoesNotThrow(
                () -> NativeHttp.validateHeadersForAdmission(chunked, "HTTP/1.1", 1024));

        Headers conflict = valid();
        conflict.add("Transfer-Encoding", "chunked");
        conflict.add("Content-Length", "0");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(conflict, "HTTP/1.1", 1024));

        Headers repeated = valid();
        repeated.add("Transfer-Encoding", "chunked");
        repeated.add("Transfer-Encoding", "chunked");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(repeated, "HTTP/1.1", 1024));

        Headers unsupported = valid();
        unsupported.add("Transfer-Encoding", "gzip, chunked");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(unsupported, "HTTP/1.1", 1024));

        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(chunked, "HTTP/1.0", 1024));
    }

    @Test
    void rejectsWhitespaceAndUriDelimiterHostAmbiguity() {
        for (String host : new String[] {"example.test/path", "bad host",
                "example.test@attacker.test", "example.test#fragment",
                "example.test,attacker.test"}) {
            Headers headers = valid();
            headers.set("Host", host);
            assertThrows(IllegalArgumentException.class,
                    () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024),
                    "unexpected Host admission: " + host);
        }
        Headers ipv6 = valid();
        ipv6.set("Host", "[::1]:8080");
        assertDoesNotThrow(
                () -> NativeHttp.validateHeadersForAdmission(ipv6, "HTTP/1.1", 1024));

        Headers legacy = new Headers();
        assertDoesNotThrow(
                () -> NativeHttp.validateHeadersForAdmission(legacy, "HTTP/1.0", 1024));
        legacy.add("Host", "bad host");
        assertThrows(IllegalArgumentException.class,
                () -> NativeHttp.validateHeadersForAdmission(legacy, "HTTP/1.0", 1024));
    }

    @Test
    void rejectsMalformedHostAuthorityPortsAndLiterals() {
        for (String host : new String[] {
                "example.test:abc", ":8080", "localhost:65536", "[gggg]",
                "localhost:", "::1", "example.test%20attacker", "localhost/other",
                "[:]", "[2001:::db8]", "[1::2::3]", "[1:2:3:4:5:6:7:8::]",
                "[::ffff:999.1.1.1]", "[::ffff:01.1.1.1]",
                "localhost:12:34", "[::1]extra", "localhost:-1",
                "999.1.1.1", "127.1", "127.0.1", "2130706433",
                "127.000.0.1", "192.0.2.256", "1.2.3.4.5",
                "999.1.1.1:8080", "127.1:80", "2130706433:443"
        }) {
            Headers headers = valid();
            headers.set("Host", host);
            assertThrows(IllegalArgumentException.class,
                    () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024),
                    "unexpected Host authority admission: " + host);
        }
    }

    @Test
    void acceptsUnambiguousHostAuthoritiesWithoutDns() {
        for (String host : new String[] {
                "localhost", "example.test", "localhost:0", "example.test:65535",
                "[::1]", "[::1]:8080", "[2001:db8::1]",
                "[1:2:3:4:5:6:7:8]", "[::ffff:192.0.2.1]",
                "127.0.0.1", "127.0.0.1:8443", "192.0.2.1:443"
        }) {
            Headers headers = valid();
            headers.set("Host", host);
            assertDoesNotThrow(
                    () -> NativeHttp.validateHeadersForAdmission(headers, "HTTP/1.1", 1024),
                    "valid Host authority rejected: " + host);
        }
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
