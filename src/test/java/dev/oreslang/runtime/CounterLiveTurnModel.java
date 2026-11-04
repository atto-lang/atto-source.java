package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-only Java host actor-turn oracle for the CounterLive V1 application.
 * It is NOT a compiled Oreslang guest, not a WebSocket transport, and not a
 * production JSON parser. The trusted host must validate its incoming
 * WebSocket envelope and authenticated principal before invoking it.
 */
final class CounterLiveTurnModel {
    static final long MAX_REV = 9_007_199_254_740_991L;
    private static final Pattern EVENT_ID = Pattern.compile("[0-9a-fA-F-]{8,128}");
    private static final int MAX_DEDUP = 256;
    private final LinkedHashMap<String, Seen> seen = new LinkedHashMap<>();
    private long count;
    private long revision;
    private boolean receiving;
    // Event-turn serialization is separate from retirement linearization.
    // A supervisor must not wait for a guest reducer/renderer callback to return
    // before revoking an uncommitted actor generation.
    private final Object commitLock = new Object();
    private volatile boolean retired;

    record Outcome(List<String> frames, boolean applied, long revision) {}
    private record Seen(String fingerprint, long acceptedRevision) {}

    synchronized long count() { return count; }
    synchronized long revision() { return revision; }

    void retire() {
        synchronized (commitLock) { retired = true; }
    }

    synchronized Outcome increment(String user, String eventId, long baseRev) {
        return increment(user, eventId, baseRev, null);
    }

    // Separate hook for adversarial same-thread reentrancy and retirement tests.
    synchronized Outcome increment(String user, String eventId, long baseRev, Runnable beforeCommit) {
        if (receiving) throw new IllegalStateException("nested actor turn");
        if (retired) throw new IllegalStateException("retired actor");
        receiving = true;
        try {
            if (user == null || user.isEmpty()) throw new IllegalArgumentException("missing authenticated user");
            if (eventId == null || !EVENT_ID.matcher(eventId).matches())
                throw new IllegalArgumentException("invalid event id");
            if (baseRev < 0 || baseRev > MAX_REV) throw new IllegalArgumentException("invalid base revision");
            // The first guest fixture supports only increment with an empty payload.
            // Fingerprint follows the canonical event v1 string-array encoding.
            String fingerprint = sha256("[" + jsonString(user) + ",\"increment\"," + baseRev + ",\"{}\"]");
            Seen old = seen.get(eventId);
            if (old != null) {
                if (!old.fingerprint.equals(fingerprint))
                    throw new IllegalArgumentException("event id reused for a different authenticated command");
                return replyIfActive(new Outcome(
                        List.of(ack(eventId, old.acceptedRevision)), false, revision));
            }
            if (baseRev != revision) {
                String rejected = "{\"v\":1,\"type\":\"event_rejected\",\"event_id\":" + jsonString(eventId)
                        + ",\"code\":\"stale_revision\",\"rev\":" + revision + "}";
                return replyIfActive(new Outcome(List.of(rejected), false, revision));
            }
            if (revision == MAX_REV || count == MAX_REV)
                throw new IllegalStateException("counter or revision exhausted");
            if (beforeCommit != null) beforeCommit.run();
            // Prepare bounded deterministic frames without committing state.
            // All concurrent event turns still serialize on the actor monitor.
            long previous = revision;
            long nextRevision = revision + 1;
            long nextCount = count + 1;
            String patch = "{\"v\":1,\"type\":\"patch\",\"prev\":" + previous
                    + ",\"rev\":" + nextRevision
                    + ",\"ops\":[{\"op\":\"set_text\",\"id\":\"count_value\",\"text\":"
                    + jsonString(Long.toString(nextCount)) + "}]}";
            String acknowledgment = ack(eventId, nextRevision);
            // The short commit and independent retire() both acquire this lock.
            // If retirement won while a callback was stalled, no event can
            // publish state, dedup, patches or ACKs afterward. If commit won,
            // the subsequent retirement is ordered after that state revision.
            synchronized (commitLock) {
                if (retired) throw new IllegalStateException("actor retired during turn");
                revision = nextRevision;
                count = nextCount;
                seen.put(eventId, new Seen(fingerprint, nextRevision));
                if (seen.size() > MAX_DEDUP) seen.remove(seen.keySet().iterator().next());
            }
            return new Outcome(List.of(patch, acknowledgment), true, nextRevision);
        } finally {
            receiving = false;
        }
    }

    // Replay ACKs and stale-revision rejections are outputs too: they must
    // linearize against supervisor retirement just like a successful patch.
    private Outcome replyIfActive(Outcome plan) {
        synchronized (commitLock) {
            if (retired) throw new IllegalStateException("actor retired before reply");
            return plan;
        }
    }

    private static String ack(String id, long rev) {
        return "{\"v\":1,\"type\":\"event_ack\",\"event_id\":" + jsonString(id)
                + ",\"rev\":" + rev + "}";
    }

    // JSON.stringify-compatible for valid Unicode scalars, including control
    // escapes; no runtime reflection or dynamic type inspection.
    static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                            throw new IllegalArgumentException("unpaired high surrogate");
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("unpaired low surrogate");
                    } else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
