package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Host-only, value-safe v1 actor failure catalogue. Guest exceptions live in
 * std/actor_errors; this type is NOT the guest Error class and does not transfer
 * Java Throwable instances or executable objects between actor heaps.
 */
public final class ActorErrorCatalog {
    private ActorErrorCatalog() { }

    public enum Kind {
        ACTOR_STOPPED(1001, "stopped", true),
        ACTOR_MAILBOX_FULL(1002, "mailbox_full", true),
        ACTOR_MAILBOX_CLOSED(1003, "mailbox_closed", true),
        ACTOR_SEND_REJECTED(1004, "send_rejected", true),
        ACTOR_TRANSPORT(1005, "transport", true),
        ACTOR_ISOLATION(1006, "isolation", false),
        ACTOR_CAPABILITY_REVOKED(1007, "capability_revoked", false),
        ACTOR_SPAWN(1008, "spawn", true),
        ACTOR_SUPERVISION(1009, "supervision", false),
        ACTOR_RESTART_LIMIT(1010, "restart_limit", false),
        ACTOR_DEADLINE_EXCEEDED(1011, "deadline_exceeded", false),
        ACTOR_CANCELLED(1012, "cancelled", false),
        ACTOR_LEASE_EXPIRED(1013, "lease_expired", false),
        ACTOR_FENCING(1014, "fencing", false),
        ACTOR_GROUP_CLOSED(1015, "group_closed", true),
        ACTOR_GROUP_PERMIT(1016, "group_permit", false),
        ACTOR_REMOTE_UNAVAILABLE(1017, "remote_unavailable", true),
        ACTOR_SERIALIZATION(1018, "serialization", true),
        ACTOR_HEAP_EXHAUSTED(1019, "heap_exhausted", false),
        ACTOR_RUNTIME_INVARIANT(1020, "runtime_invariant", false);

        private final int code;
        private final String wireName;
        private final boolean recoverable;
        Kind(int code, String wireName, boolean recoverable) {
            this.code = code;
            this.wireName = wireName;
            this.recoverable = recoverable;
        }
        public int code() { return code; }
        public String wireName() { return wireName; }
        public boolean recoverable() { return recoverable; }
    }

    public static Kind fromCode(int code) {
        if (code < 1001 || code > 1020) {
            throw new IllegalArgumentException("unknown actor error code: " + code);
        }
        return Kind.values()[code - 1001];
    }

    /** No Throwable, stack trace, function or actor heap pointer is retained. */
    public record Envelope(Kind kind, String diagnostic, String actorId, String supervisorId) {
        public Envelope {
            Objects.requireNonNull(kind, "kind");
            diagnostic = boundedDiagnostic(diagnostic);
            actorId = safeIdentity(actorId);
            supervisorId = safeIdentity(supervisorId);
        }
        public int code() { return kind.code(); }
    }

    private static String safeIdentity(String input) {
        Objects.requireNonNull(input, "identity");
        if (input.length() > 128 || !input.matches("[A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("invalid actor identity");
        }
        return input;
    }

    /** Limit UTF-8 to 1024 bytes, repairing malformed UTF-16 without leaking host data. */
    public static String boundedDiagnostic(String input) {
        Objects.requireNonNull(input, "diagnostic");
        StringBuilder out = new StringBuilder(Math.min(input.length(), 1024));
        int bytes = 0;
        for (int i = 0; i < input.length();) {
            int codePoint = input.codePointAt(i);
            int length = Character.charCount(codePoint);
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) codePoint = '?';
            int size = codePoint <= 0x7F ? 1
                    : codePoint <= 0x7FF ? 2
                    : codePoint <= 0xFFFF ? 3 : 4;
            if (bytes + size > 1024) break;
            out.appendCodePoint(codePoint);
            bytes += size;
            i += length;
        }
        return out.toString();
    }
}
