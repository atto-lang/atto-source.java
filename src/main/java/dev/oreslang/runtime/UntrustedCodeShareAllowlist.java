package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable supervisor-owned allowlist for sharing code images with adversarial
 * / untrusted actor contexts.
 *
 * <p>Grants are exact (codeUnitId, SHA-256) pairs. A source edit, rename or
 * digest mismatch fails closed. The allowlist authorizes only reuse of
 * immutable code bytes/checked definitions; it never grants guest capabilities
 * and it never changes the sandbox policy.
 */
public final class UntrustedCodeShareAllowlist {
    private record Grant(String codeUnitId, String sha256) { }

    private static final UntrustedCodeShareAllowlist NONE =
            new UntrustedCodeShareAllowlist(Set.of());

    private final Set<Grant> grants;

    private UntrustedCodeShareAllowlist(Set<Grant> grants) {
        this.grants = Set.copyOf(grants);
    }

    public static UntrustedCodeShareAllowlist none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean permits(String codeUnitId, String sourceText) {
        Objects.requireNonNull(sourceText, "sourceText");
        return permitsDigest(codeUnitId, sha256(sourceText));
    }

    public boolean permitsDigest(String codeUnitId, String sha256) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sha256, "sha256");
        return grants.contains(new Grant(validateCodeUnitId(codeUnitId), validateDigest(sha256)));
    }

    public int size() {
        return grants.size();
    }

    static String sha256(String sourceText) {
        Objects.requireNonNull(sourceText, "sourceText");
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(sourceText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String validateCodeUnitId(String codeUnitId) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        return codeUnitId;
    }

    private static String validateDigest(String digest) {
        String normalized = Objects.requireNonNull(digest, "sha256").toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 64 hexadecimal characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("SHA-256 digest contains non-hexadecimal characters");
            }
        }
        return normalized;
    }

    public static final class Builder {
        private final Set<Grant> grants = new HashSet<>();

        private Builder() { }

        /** Pin the exact source currently approved by the supervisor. */
        public Builder allowSource(String codeUnitId, String sourceText) {
            return allowDigest(codeUnitId, sha256(sourceText));
        }

        /** Pin an exact deployment-manifest digest without loading source text. */
        public Builder allowDigest(String codeUnitId, String sha256) {
            grants.add(new Grant(validateCodeUnitId(codeUnitId), validateDigest(sha256)));
            return this;
        }

        public UntrustedCodeShareAllowlist build() {
            return grants.isEmpty()
                    ? NONE
                    : new UntrustedCodeShareAllowlist(grants);
        }
    }
}
