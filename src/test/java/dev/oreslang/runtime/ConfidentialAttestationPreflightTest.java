package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class ConfidentialAttestationPreflightTest {
    private static byte[] bytes(int value) {
        byte[] data = new byte[32];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static ConfidentialAttestationPreflight.Requirements policy() {
        return new ConfidentialAttestationPreflight.Requirements(
                ConfidentialAttestationPreflight.Provider.AMD_SEV_SNP,
                bytes(7), 5, 100);
    }

    private static ConfidentialAttestationPreflight.UntrustedClaims claims(byte[] nonce) {
        return new ConfidentialAttestationPreflight.UntrustedClaims(
                ConfidentialAttestationPreflight.Provider.AMD_SEV_SNP,
                bytes(7), nonce, 1000, 5, false, false, new byte[] {1});
    }

    @Test
    void matchingUntrustedClaimsNeverBecomeHardwareAttestation() {
        var session = new ConfidentialAttestationPreflight.Session(policy());
        assertEquals(ConfidentialAttestationPreflight.Verdict.REJECTED_STATE,
                session.check_untrusted_claims(claims(bytes(9)), 1000));
        var challenge = session.issue_challenge(new SecureRandom(), 1000, 50);
        assertEquals(32, challenge.nonce().length);
        assertEquals(ConfidentialAttestationPreflight.Verdict.UNVERIFIED_CLAIMS_MATCH,
                session.check_untrusted_claims(claims(challenge.nonce()), 1000));
        assertEquals(ConfidentialAttestationPreflight.State.UNVERIFIED_CLAIMS_MATCH, session.state());
        // A plausible but fake opaque quote is NEVER cryptographically verified.
        assertEquals(ConfidentialAttestationPreflight.Verdict.REJECTED_STATE,
                session.check_untrusted_claims(claims(challenge.nonce()), 1000));
        assertThrows(IllegalStateException.class,
                () -> session.issue_challenge(new SecureRandom(), 1001, 50));
    }

    @Test
    void policyRejectsHostileClaimsAndAlwaysConsumesTheChallenge() {
        for (int variant = 0; variant < 10; ++variant) {
            var session = new ConfidentialAttestationPreflight.Session(policy());
            byte[] nonce = session.issue_challenge(new SecureRandom(), 1000, 50).nonce();
            var good = claims(nonce);
            var altered = switch (variant) {
                case 0 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        ConfidentialAttestationPreflight.Provider.INTEL_TDX,
                        bytes(7), nonce, 1000, 5, false, false, new byte[]{1});
                case 1 -> claims(bytes(9)); // nonce mismatch
                case 2 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(8), nonce, 1000, 5, false, false, new byte[]{1});
                case 3 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 1000, 4, false, false, new byte[]{1});
                case 4 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 1000, 5, true, false, new byte[]{1});
                case 5 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 1000, 5, false, true, new byte[]{1});
                case 6 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 1000, 5, false, false, new byte[]{});
                case 7 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 999, 5, false, false, new byte[]{1});
                case 8 -> new ConfidentialAttestationPreflight.UntrustedClaims(
                        good.provider(), bytes(7), nonce, 1051, 5, false, false, new byte[]{1});
                default -> null;
            };
            long now = variant == 8 ? 1051 : 1001;
            assertNotEquals(ConfidentialAttestationPreflight.Verdict.UNVERIFIED_CLAIMS_MATCH,
                    session.check_untrusted_claims(altered, now), "variant " + variant);
            assertEquals(ConfidentialAttestationPreflight.State.REJECTED, session.state());
            assertEquals(ConfidentialAttestationPreflight.Verdict.REJECTED_STATE,
                    session.check_untrusted_claims(good, 1001));
        }
    }

    @Test
    void policyAndBytesAreDefensivelyCopied() {
        byte[] expected = bytes(7);
        var requirements = new ConfidentialAttestationPreflight.Requirements(
                ConfidentialAttestationPreflight.Provider.AMD_SEV_SNP, expected, 5, 100);
        expected[0] = 0;
        assertEquals(7, requirements.measurement_sha256()[0]);
        byte[] copy = requirements.measurement_sha256();
        copy[0] = 0;
        assertEquals(7, requirements.measurement_sha256()[0]);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidentialAttestationPreflight.Requirements(
                        ConfidentialAttestationPreflight.Provider.INTEL_TDX, bytes(0), 5, 100));
    }
}
