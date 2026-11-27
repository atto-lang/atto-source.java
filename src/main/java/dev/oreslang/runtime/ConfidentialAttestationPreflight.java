package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Host-only, one-shot attestation policy preflight. UNVERIFIED_CLAIMS_MATCH is
 * never a hardware attestation verdict or permission to open a TenantVM.
 *
 * Vendor signature chains, TCB verification, report-data channel binding and
 * secret release require a separate independently reviewed provider. None is
 * registered here and confidential admission remains unconditionally denied.
 */
public final class ConfidentialAttestationPreflight {
    private ConfidentialAttestationPreflight() {}

    public enum Provider { AMD_SEV_SNP, INTEL_TDX }
    public enum State { CREATED, CHALLENGE_ISSUED, UNVERIFIED_CLAIMS_MATCH, REJECTED }
    public enum Verdict {
        UNVERIFIED_CLAIMS_MATCH,
        REJECTED_STATE, REJECTED_EVIDENCE, REJECTED_PROVIDER,
        REJECTED_NONCE, REJECTED_MEASUREMENT, REJECTED_TCB,
        REJECTED_DEBUG, REJECTED_REVOKED, REJECTED_FRESHNESS
    }

    public record Requirements(Provider provider, byte[] measurement_sha256,
                               long min_tcb, long max_evidence_age_ms) {
        public Requirements {
            Objects.requireNonNull(provider, "provider");
            if (measurement_sha256 == null || measurement_sha256.length != 32
                    || all_zero(measurement_sha256) || min_tcb <= 0
                    || max_evidence_age_ms <= 0) {
                throw new IllegalArgumentException("invalid attestation requirements");
            }
            measurement_sha256 = measurement_sha256.clone();
        }

        @Override public byte[] measurement_sha256() { return measurement_sha256.clone(); }
    }

    /** Entirely UNTRUSTED metadata until hardware signature verification exists. */
    public record UntrustedClaims(Provider provider, byte[] measurement_sha256,
                                  byte[] challenge_nonce, long issued_at_ms,
                                  long tcb, boolean debug_enabled, boolean revoked,
                                  byte[] opaque_quote) {
        public UntrustedClaims {
            measurement_sha256 = clone_or_null(measurement_sha256);
            challenge_nonce = clone_or_null(challenge_nonce);
            opaque_quote = clone_or_null(opaque_quote);
        }
        @Override public byte[] measurement_sha256() { return clone_or_null(measurement_sha256); }
        @Override public byte[] challenge_nonce() { return clone_or_null(challenge_nonce); }
        @Override public byte[] opaque_quote() { return clone_or_null(opaque_quote); }
    }

    public record Challenge(byte[] nonce, long issued_at_ms) {
        public Challenge { nonce = nonce.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
    }

    public static final class Session {
        private final Requirements policy;
        private State state = State.CREATED;
        private byte[] nonce;
        private long issued_at_ms;
        private long ttl_ms;

        public Session(Requirements policy) { this.policy = Objects.requireNonNull(policy); }
        public synchronized State state() { return state; }

        /** Must be called by the trusted host using an operating-system CSPRNG. */
        public synchronized Challenge issue_challenge(SecureRandom random, long now_ms, long ttl_ms) {
            Objects.requireNonNull(random, "random");
            if (state != State.CREATED || now_ms < 0 || ttl_ms <= 0) {
                throw new IllegalStateException("challenge already issued or invalid time");
            }
            byte[] next = new byte[32];
            random.nextBytes(next);
            if (all_zero(next)) throw new IllegalStateException("invalid challenge entropy");
            this.nonce = next;
            this.issued_at_ms = now_ms;
            this.ttl_ms = ttl_ms;
            this.state = State.CHALLENGE_ISSUED;
            return new Challenge(next, now_ms);
        }

        /** Consumes the challenge even on failure. Does NOT verify the quote signature. */
        public synchronized Verdict check_untrusted_claims(UntrustedClaims claims, long now_ms) {
            if (state != State.CHALLENGE_ISSUED) return Verdict.REJECTED_STATE;
            state = State.REJECTED; // one-shot: no retries or reused nonce
            if (claims == null || claims.opaque_quote == null || claims.opaque_quote.length == 0
                    || claims.measurement_sha256 == null || claims.measurement_sha256.length != 32
                    || claims.challenge_nonce == null || claims.challenge_nonce.length != 32) {
                return Verdict.REJECTED_EVIDENCE;
            }
            if (claims.provider != policy.provider) return Verdict.REJECTED_PROVIDER;
            if (!MessageDigest.isEqual(nonce, claims.challenge_nonce)) return Verdict.REJECTED_NONCE;
            if (!MessageDigest.isEqual(policy.measurement_sha256, claims.measurement_sha256)) {
                return Verdict.REJECTED_MEASUREMENT;
            }
            if (claims.debug_enabled) return Verdict.REJECTED_DEBUG;
            if (claims.revoked) return Verdict.REJECTED_REVOKED;
            if (claims.tcb < policy.min_tcb) return Verdict.REJECTED_TCB;
            // Subtract only after ordered comparisons to prevent overflow/future quote bypass.
            if (now_ms < issued_at_ms || now_ms - issued_at_ms > ttl_ms
                    || claims.issued_at_ms < issued_at_ms
                    || claims.issued_at_ms > now_ms
                    || now_ms - claims.issued_at_ms > policy.max_evidence_age_ms) {
                return Verdict.REJECTED_FRESHNESS;
            }
            state = State.UNVERIFIED_CLAIMS_MATCH;
            return Verdict.UNVERIFIED_CLAIMS_MATCH;
        }
    }

    private static byte[] clone_or_null(byte[] data) {
        return data == null ? null : data.clone();
    }

    private static boolean all_zero(byte[] data) {
        int acc = 0;
        for (byte b : data) acc |= b;
        return acc == 0;
    }
}
