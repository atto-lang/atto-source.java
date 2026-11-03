package dev.oreslang.runtime;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deliberately package-private Ed25519 byte-integrity helper for AOT V2.
 * This is NOT a JSON parser, signature-envelope parser, trust-key store,
 * native loader, actor admission decision, or evidence of guest execution.
 * A future caller MUST first strictly parse and RFC8785-canonicalize V2,
 * validate its full typed schema, and select an externally trusted public key.
 */
final class AotV2DetachedByteProof {
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    record Receipt(String classification, String manifestSha256, String artifactSha256,
                   boolean loaderVerified, boolean guestExecuted, boolean releaseReady) { }

    private AotV2DetachedByteProof() { }

    static Receipt verifyCanonicalBytes(
            byte[] canonicalManifestUtf8,
            byte[] detachedEd25519Signature,
            PublicKey pinnedExternalPublicKey,
            String manifestSha256,
            byte[] artifactBytes,
            String artifactSha256,
            long expectedArtifactSize) {
        Objects.requireNonNull(canonicalManifestUtf8, "canonicalManifestUtf8");
        Objects.requireNonNull(detachedEd25519Signature, "detachedEd25519Signature");
        Objects.requireNonNull(pinnedExternalPublicKey, "pinnedExternalPublicKey");
        Objects.requireNonNull(artifactBytes, "artifactBytes");
        requireHash(manifestSha256, "manifestSha256");
        requireHash(artifactSha256, "artifactSha256");
        if (canonicalManifestUtf8.length == 0 || canonicalManifestUtf8.length > 65536) {
            throw new IllegalArgumentException("canonical V2 manifest exceeds byte bounds");
        }
        if (detachedEd25519Signature.length != 64) {
            throw new IllegalArgumentException("detached Ed25519 signature must be 64 bytes");
        }
        if (expectedArtifactSize < 1 || expectedArtifactSize > MAX_SAFE_JSON_INTEGER
                || artifactBytes.length != expectedArtifactSize) {
            throw new IllegalArgumentException("artifact size mismatch");
        }
        if (!(pinnedExternalPublicKey instanceof EdECPublicKey edKey)
                || !"Ed25519".equals(edKey.getParams().getName())) {
            throw new IllegalArgumentException("external trust anchor must use Ed25519");
        }
        // Copy both untrusted byte arrays exactly once; subsequent digest and
        // signature checks operate on identical immutable snapshots.
        byte[] signedBytes = canonicalManifestUtf8.clone();
        byte[] signatureBytes = detachedEd25519Signature.clone();
        byte[] binary = artifactBytes.clone();
        if (!MessageDigest.isEqual(digest(signedBytes), HEX.parseHex(manifestSha256))) {
            throw new IllegalArgumentException("canonical manifest digest mismatch");
        }
        if (!MessageDigest.isEqual(digest(binary), HEX.parseHex(artifactSha256))) {
            throw new IllegalArgumentException("artifact digest mismatch");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(edKey);
            verifier.update(signedBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("detached signature verification failed");
            }
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("Ed25519 signature verification failed", invalid);
        }
        return new Receipt("CRYPTOGRAPHIC_BYTES_ONLY", manifestSha256, artifactSha256,
                false, false, false);
    }

    private static void requireHash(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
