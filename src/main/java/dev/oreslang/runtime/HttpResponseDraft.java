package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Trusted transport-side, single-use response envelope. This is NOT yet an
 * Oreslang guest-visible class or an ordinary sendable actor message.
 *
 * The body stream is opened once, on the HTTP virtual I/O worker, after the
 * return/handoff. The response owns its buffered bytes and frozen header pairs.
 */
public final class HttpResponseDraft {
    private static final Pattern FIELD = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> TRANSPORT_OWNED = Set.of(
            "content-length", "transfer-encoding", "connection", "trailer",
            "upgrade", "te", "host", "keep-alive", "proxy-connection",
            "http2-settings", "proxy-authenticate", "proxy-authorization");
    private static final int MAX_FIELDS = 100;
    private static final int MAX_HEADER_BYTES = 32768;

    private final int status;
    private final List<Map.Entry<String, String>> headers;
    private final byte[] bytes;
    private final Supplier<? extends InputStream> stream;
    private final boolean bodyPresent;
    private final AtomicBoolean handedOff = new AtomicBoolean();

    private HttpResponseDraft(int status, List<Map.Entry<String, String>> headers,
                              byte[] bytes, Supplier<? extends InputStream> stream,
                              boolean bodyPresent) {
        if (status < 200 || status > 599) throw new IllegalArgumentException("HTTP status must be 200..599");
        if (bodyPresent && (status == 204 || status == 205 || status == 304))
            throw new IllegalArgumentException("body forbidden for 204, 205 and 304");
        if (bodyPresent && (bytes == null) == (stream == null))
            throw new IllegalArgumentException("response has exactly one body representation");
        if (!bodyPresent && (bytes != null || stream != null))
            throw new IllegalArgumentException("bodyless response has a body");
        this.status = status;
        this.headers = checkHeaders(headers);
        this.bytes = bytes == null ? null : bytes.clone();
        this.stream = stream;
        this.bodyPresent = bodyPresent;
    }

    public static HttpResponseDraft empty() { return empty(200, List.of()); }
    public static HttpResponseDraft empty(int status, List<Map.Entry<String, String>> headers) {
        return new HttpResponseDraft(status, headers, null, null, false);
    }
    public static HttpResponseDraft text(String body) { return text(body, 200, List.of()); }
    public static HttpResponseDraft text(String body, int status, List<Map.Entry<String, String>> headers) {
        Objects.requireNonNull(body, "body");
        List<Map.Entry<String, String>> provided = checkHeaders(headers);
        boolean hasType = provided.stream().anyMatch(h -> h.getKey().equalsIgnoreCase("Content-Type"));
        if (!hasType) {
            ArrayList<Map.Entry<String, String>> withType = new ArrayList<>(provided);
            withType.add(Map.entry("Content-Type", "text/plain; charset=utf-8"));
            provided = withType;
        }
        return new HttpResponseDraft(status, provided, body.getBytes(StandardCharsets.UTF_8), null, true);
    }
    public static HttpResponseDraft binary(byte[] body, int status, List<Map.Entry<String, String>> headers) {
        return new HttpResponseDraft(status, headers, Objects.requireNonNull(body, "body"), null, true);
    }
    public static HttpResponseDraft streaming(Supplier<? extends InputStream> body,
                                              int status, List<Map.Entry<String, String>> headers) {
        return new HttpResponseDraft(status, headers, null, Objects.requireNonNull(body, "body"), true);
    }
    public static HttpResponseDraft withTrailers() {
        throw new UnsupportedOperationException("HTTP trailers are not supported by this HTTP/1.1 adapter");
    }

    private static List<Map.Entry<String, String>> checkHeaders(List<Map.Entry<String, String>> input) {
        Objects.requireNonNull(input, "headers");
        if (input.size() > MAX_FIELDS) throw new IllegalArgumentException("too many response fields");
        List<Map.Entry<String, String>> result = new ArrayList<>(input.size());
        int total = 0;
        for (Map.Entry<String, String> field : input) {
            Objects.requireNonNull(field, "header");
            String name = Objects.requireNonNull(field.getKey(), "header name");
            String value = Objects.requireNonNull(field.getValue(), "header value");
            if (!FIELD.matcher(name).matches()
                    || TRANSPORT_OWNED.contains(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("invalid or transport-owned response header");
            for (int i = 0; i < value.length(); i++) {
                int c = value.charAt(i);
                if (c < 32 || c == 127 || c > 255)
                    throw new IllegalArgumentException("invalid HTTP response header value");
            }
            total += name.length() + value.length() + 4;
            if (total > MAX_HEADER_BYTES) throw new IllegalArgumentException("response headers too large");
            result.add(Map.entry(name, value)); // Preserve individual Set-Cookie entries.
        }
        return List.copyOf(result);
    }

    public int status() { return status; }
    public boolean hasBody() { return bodyPresent; }
    public boolean streaming() { return stream != null; }
    public List<Map.Entry<String, String>> headers() { return headers; }
    public boolean ok() { return status >= 200 && status <= 299; }
    public int bufferedLength() { return bytes == null ? 0 : bytes.length; }

    void claim() {
        if (!handedOff.compareAndSet(false, true))
            throw new IllegalStateException("HttpResponseDraft already handed off");
    }
    InputStream openBody() {
        if (!handedOff.get()) throw new IllegalStateException("response not handed off");
        if (!bodyPresent) throw new IllegalStateException("bodyless response");
        return stream == null ? new ByteArrayInputStream(bytes)
                : Objects.requireNonNull(stream.get(), "body stream supplier produced null");
    }
}
