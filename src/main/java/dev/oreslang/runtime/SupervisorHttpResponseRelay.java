package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Trusted supervisor-side HTTP response decoder for actor outputs.
 *
 * An UNTRUSTED actor can emit ONLY data-only, serialized Map frames using the
 * existing ActorRef.outputs() transport. It never receives the socket/FD.
 * This relay must be called by the authenticated parent output consumer,
 * passing the ActorOutput.actorId() and .sequence() metadata — not identity
 * fields supplied by the guest payload. The sink must be nonblocking.
 *
 * It is a per-request, host-level protocol substrate. Full source-level
 * HttpResponse<T>, async credit grants and native socket wiring remain gated.
 */
public final class SupervisorHttpResponseRelay {
    public interface Sink {
        void headers(int status, Map<String, String> headers);
        void chunk(byte[] bytes);
        void end();
        void abort(String reason);
    }

    public enum State { NEW, BODY, ENDED, ABORTED }

    private static final Set<String> GUEST_HEADERS = Set.of(
            "content-type", "cache-control", "etag", "vary",
            "content-language", "last-modified", "expires");

    private final String actorId;
    private final String requestId;
    private final int maxChunkBytes;
    private final long maxResponseBytes;
    private final Sink sink;
    private long remainingCredit;
    private long totalBytes;
    private long lastSequence = -1;
    private State state = State.NEW;
    private boolean bodyAllowed = true;

    public SupervisorHttpResponseRelay(
            String actorId,
            String requestId,
            int maxChunkBytes,
            long maxResponseBytes,
            Sink sink) {
        this.actorId = requireId(actorId, "actor id");
        this.requestId = requireId(requestId, "request id");
        if (maxChunkBytes <= 0 || maxChunkBytes > 1_048_576) {
            throw new IllegalArgumentException("invalid HTTP chunk size");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("invalid HTTP response byte quota");
        }
        this.maxChunkBytes = maxChunkBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.sink = Objects.requireNonNull(sink, "supervisor response sink");
    }

    /**
     * Credits must come from the supervisor only AFTER downstream write/queue
     * capacity has been reserved. A guest cannot grant its own credits.
     */
    public synchronized void grantCredit(long bytes) {
        if (state == State.ENDED || state == State.ABORTED) {
            throw new IllegalStateException("response is already terminal");
        }
        if (bytes <= 0) throw new IllegalArgumentException("credit must be positive");
        long next = Math.addExact(remainingCredit, bytes);
        if (next > maxResponseBytes) {
            throw new IllegalArgumentException("credit exceeds request quota");
        }
        remainingCredit = next;
    }

    /** The caller must get these metadata values from ActorOutput, not the Map. */
    public synchronized void accept(String sourceActorId, long sequence, Object encoded) {
        if (!actorId.equals(sourceActorId)) {
            throw new SecurityException("response output is not from the assigned actor");
        }
        if (sequence < 0 || sequence <= lastSequence) {
            throw new IllegalArgumentException("duplicate or out-of-order actor output");
        }
        if (state == State.ENDED || state == State.ABORTED) {
            throw new IllegalStateException("response is already terminal");
        }
        if (!(encoded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("HTTP response frame must be a serializable map");
        }
        if (!requestId.equals(raw.get("request_id"))) {
            throw new SecurityException("HTTP response request identity mismatch");
        }
        Object kind = raw.get("kind");
        if (!(kind instanceof String)) {
            throw new IllegalArgumentException("HTTP response frame kind must be text");
        }
        switch ((String) kind) {
            case "headers" -> {
                requireKeys(raw, Set.of("request_id", "kind", "status", "headers"));
                if (state != State.NEW) throw new IllegalStateException("duplicate headers");
                Object code = raw.get("status");
                if (!(code instanceof Integer status) || status < 200 || status > 599) {
                    throw new IllegalArgumentException("unsupported HTTP final response status");
                }
                Map<String, String> headers = validateHeaders(raw.get("headers"));
                forward(() -> sink.headers(status, headers));
                bodyAllowed = status != 204 && status != 205 && status != 304;
                state = State.BODY;
            }
            case "chunk", "bytes" -> {
                if (state != State.BODY) {
                    throw new IllegalStateException("HTTP body before headers");
                }
                if (!bodyAllowed) {
                    throw new IllegalStateException("HTTP body not allowed for response status");
                }
                byte[] bytes;
                if ("chunk".equals(kind)) {
                    requireKeys(raw, Set.of("request_id", "kind", "body"));
                    if (!(raw.get("body") instanceof String body)) {
                        throw new IllegalArgumentException("text chunk must be a String");
                    }
                    bytes = body.getBytes(StandardCharsets.UTF_8);
                } else {
                    requireKeys(raw, Set.of("request_id", "kind", "data"));
                    bytes = decodeBytes(raw.get("data"), maxChunkBytes);
                }
                if (bytes.length > maxChunkBytes
                        || bytes.length > remainingCredit
                        || bytes.length > maxResponseBytes - totalBytes) {
                    throw new IllegalStateException("HTTP actor response exceeded write credit/quota");
                }
                forward(() -> sink.chunk(bytes));
                remainingCredit -= bytes.length;
                totalBytes += bytes.length;
            }
            case "end" -> {
                requireKeys(raw, Set.of("request_id", "kind"));
                if (state != State.BODY) throw new IllegalStateException("response ended before headers");
                forward(sink::end);
                state = State.ENDED;
            }
            case "abort" -> {
                requireKeys(raw, Set.of("request_id", "kind", "reason"));
                if (!(raw.get("reason") instanceof String reason)
                        || reason.length() > 256 || reason.indexOf('\n') >= 0
                        || reason.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("invalid response abort reason");
                }
                state = State.ABORTED;
                sink.abort(reason);
            }
            default -> throw new IllegalArgumentException("unsupported HTTP response frame: " + kind);
        }
        lastSequence = sequence;
    }

    /** Trusted parent path for actor exit, timeout or cancellation. */
    public synchronized void abortFromSupervisor(String reason) {
        if (state == State.ENDED || state == State.ABORTED) return;
        state = State.ABORTED;
        sink.abort(reason == null ? "request aborted" : reason);
    }

    /**
     * A failing downstream writer may have partially committed bytes. Make
     * the stream terminal rather than allowing retries to duplicate headers
     * or corrupt an HTTP response. This does not run on the guest actor lane.
     */
    private void forward(Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException downstream) {
            state = State.ABORTED;
            try {
                sink.abort("downstream write failed");
            } catch (RuntimeException secondary) {
                downstream.addSuppressed(secondary);
            }
            throw downstream;
        }
    }

    public synchronized State state() { return state; }
    public synchronized long totalBytes() { return totalBytes; }
    public synchronized long remainingCredit() { return remainingCredit; }

    private static String requireId(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static void requireKeys(Map<?, ?> raw, Set<String> keys) {
        if (!raw.keySet().equals(keys)) {
            throw new IllegalArgumentException("invalid HTTP frame field set");
        }
    }

    private static Map<String, String> validateHeaders(Object raw) {
        if (!(raw instanceof Map<?, ?> input) || input.size() > 32) {
            throw new IllegalArgumentException("invalid HTTP response headers");
        }
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("HTTP header must have String key and value");
            }
            if (!GUEST_HEADERS.contains(name)
                    || value.length() > 2048
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || value.indexOf('\0') >= 0) {
                throw new SecurityException("disallowed untrusted HTTP response header: " + name);
            }
            if (headers.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate HTTP header");
            }
        }
        return Map.copyOf(headers);
    }

    private static byte[] decodeBytes(Object data, int maxChunkBytes) {
        if (!(data instanceof List<?> list) || list.size() > maxChunkBytes) {
            throw new IllegalArgumentException("invalid binary response chunk");
        }
        byte[] bytes = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object number = list.get(i);
            if (!(number instanceof Integer b) || b < 0 || b > 255) {
                throw new IllegalArgumentException("byte values must be integers 0..255");
            }
            bytes[i] = (byte) b.intValue();
        }
        return bytes;
    }
}
