package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class AotV2DetachedByteProofTest {
    static final String M_SHA = "8ad9bd52439c16e94301cb86e0afc13bd53e5cb959493c2f1ddb48a44820a58a";
    static final String A_SHA = "cfd83f63b6dcbaf49bbf80732cd4086a9971d0f32cfc31b6f22396740629f157";
    static final String PUB = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=";
    static final String SIG = "6bbbXMRHcf49WEt3NctVlk4PrlagTXEvdlBXJVW7SrMTgUvCl4zuE6Oe75gQvT/IXIGiNnMAsrxHW1foyIcJDg==";
    static final String CANONICAL = "{\"artifact\":{\"abi\":\"gnu\",\"arch\":\"x86_64\",\"format\":\"elf\",\"kind\":\"native-executable\",\"os\":\"linux\",\"sha256\":\"" + A_SHA + "\",\"size_bytes\":128,\"target_triple\":\"x86_64-unknown-linux-gnu\"},\"compiler\":{\"backend\":\"llvm\",\"git_commit\":\"" + "a".repeat(40) + "\",\"mode\":\"per-guest-aot\",\"version\":\"0.1.0-fixture\"},\"provenance\":{\"builder_id\":\"test-only-not-a-release-builder\"},\"runtime\":{\"guest_jit\":false,\"interpreter_fallback\":false,\"runtime_guest_compilation\":false},\"schema_version\":\"atto.aot-artifact.v2\",\"source\":{\"ast_sha256\":\"" + "d".repeat(64) + "\",\"expanded_sha256\":\"" + "c".repeat(64) + "\",\"sha256\":\"" + "b".repeat(64) + "\"}}";
    static final byte[] ARTIFACT = new byte[128];
    static {
        ARTIFACT[0] = 0x7f; ARTIFACT[1] = 'E'; ARTIFACT[2] = 'L'; ARTIFACT[3] = 'F';
        ARTIFACT[4] = 2; ARTIFACT[5] = 1; ARTIFACT[6] = 1;
        ARTIFACT[16] = 2; ARTIFACT[18] = 62; ARTIFACT[20] = 1;
        ARTIFACT[32] = 64; ARTIFACT[52] = 64; ARTIFACT[54] = 56; ARTIFACT[56] = 1;
        ARTIFACT[64] = 1;
    }
    static PublicKey key() throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUB)));
    }
    static AotV2DetachedByteProof.Receipt check(
            byte[] canonical, byte[] signature, PublicKey key, byte[] artifact,
            String manifestDigest, String artifactDigest, long size) {
        return AotV2DetachedByteProof.verifyCanonicalBytes(
                canonical, signature, key, manifestDigest, artifact, artifactDigest, size);
    }
    static void bad(Runnable action) {
        try {
            action.run();
            throw new AssertionError("unexpected admission");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed admission.
        }
    }

    @Test
    void validatesIndependentGoldenVectorAndRejectsTampering() throws Exception {
        byte[] canonical = CANONICAL.getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getDecoder().decode(SIG);
        PublicKey publicKey = key();
        var proof = check(canonical, signature, publicKey, ARTIFACT, M_SHA, A_SHA, 128);
        if (!"CRYPTOGRAPHIC_BYTES_ONLY".equals(proof.classification())
                || proof.guestExecuted() || proof.releaseReady() || proof.loaderVerified()) {
            throw new AssertionError(proof);
        }
        byte[] changedArtifact = ARTIFACT.clone();
        changedArtifact[40] = 1;
        bad(() -> check(canonical, signature, publicKey, changedArtifact, M_SHA, A_SHA, 128));
        byte[] changedCanonical = canonical.clone();
        changedCanonical[0] ^= 1;
        bad(() -> check(changedCanonical, signature, publicKey, ARTIFACT, M_SHA, A_SHA, 128));
        byte[] changedSignature = signature.clone();
        changedSignature[0] ^= 1;
        bad(() -> check(canonical, changedSignature, publicKey, ARTIFACT, M_SHA, A_SHA, 128));
        bad(() -> check(canonical, signature, publicKey, ARTIFACT, M_SHA, A_SHA, 129));
        bad(() -> check(canonical, signature, publicKey, ARTIFACT, "0".repeat(64), A_SHA, 128));
        System.out.println("JAVA_V2_DETACHED_BYTES_ONLY_7_CHECKS_OK");
    }

    public static void main(String[] args) throws Exception {
        new AotV2DetachedByteProofTest().validatesIndependentGoldenVectorAndRejectsTampering();
    }
}
