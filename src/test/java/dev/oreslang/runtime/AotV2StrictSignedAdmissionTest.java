package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;
import java.util.Set;

/** Signed raw V2 JSON, strict-parser, JCS and trust-key end-to-end regression. */
final class AotV2StrictSignedAdmissionTest {
    static final String MANIFEST=AotV2DetachedByteProofTest.CANONICAL;
    static final String SIGNATURE="{\"schema_version\":\"atto.aot-artifact-signature.v2\","
            +"\"manifest_sha256\":\""+AotV2DetachedByteProofTest.M_SHA+"\","
            +"\"algorithm\":\"ed25519\",\"key_id\":\"RFC8032-test-only\","
            +"\"signature_base64\":\""+AotV2DetachedByteProofTest.SIG+"\"}";
    static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static AotV2StrictSignedAdmission.Receipt admit(String manifest,String signature,
                                                     byte[] bytes,Map<String,PublicKey> keys,
                                                     Set<String> revoked){
        return AotV2StrictSignedAdmission.admit(utf8(manifest),utf8(signature),bytes,keys,revoked);
    }
    static void reject(Runnable r) {
        try{r.run();throw new AssertionError("unexpected signed V2 admission");}
        catch(IllegalArgumentException expected){/* fail-closed */}
    }

    @Test void validatesCompleteRawSignedVectorWithoutPromotingRelease() throws Exception {
        PublicKey key=AotV2DetachedByteProofTest.key();
        var proof=admit(MANIFEST,SIGNATURE,AotV2DetachedByteProofTest.ARTIFACT,
                Map.of("RFC8032-test-only",key),Set.of());
        if(!"AUTHENTICATED_NOT_EXECUTED".equals(proof.tier())
                ||proof.guestExecuted()||proof.loaderVerified()||proof.releaseReady()
                ||proof.manifest().guestExecuted()||proof.manifest().authenticated())
            throw new AssertionError("inadmissible evidence tier "+proof);
        if(!MANIFEST.equals(AotV2StrictSignedAdmission.canonicalize(
                ActorArtifactManifestJsonV1.parseStrictJsonValue(utf8(MANIFEST)))))
            throw new AssertionError("JCS canonical bytes drifted");
        System.out.println("JAVA_SIGNED_V2_CANONICAL_ED25519_INGRESS_OK");
    }

    @Test void rejectsAlteredSignaturesManifestAndArtifactBytes() throws Exception {
        PublicKey key=AotV2DetachedByteProofTest.key();
        Map<String,PublicKey> keys=Map.of("RFC8032-test-only",key);
        byte[] original=AotV2DetachedByteProofTest.ARTIFACT;
        byte[] mutated=original.clone();mutated[40]^=1;
        reject(()->admit(MANIFEST,SIGNATURE,mutated,keys,Set.of()));
        reject(()->admit(MANIFEST.replace("test-only-not-a-release-builder","imposter"),SIGNATURE,original,keys,Set.of()));
        reject(()->admit(MANIFEST,SIGNATURE.replace(AotV2DetachedByteProofTest.M_SHA,"0".repeat(64)),original,keys,Set.of()));
        reject(()->admit(MANIFEST,SIGNATURE.replace(AotV2DetachedByteProofTest.SIG,"A".repeat(86)+"=="),original,keys,Set.of()));
    }
    @Test void enforcesExternallyPinnedAndRevocableTrustAnchors() throws Exception {
        PublicKey key=AotV2DetachedByteProofTest.key();
        reject(()->admit(MANIFEST,SIGNATURE,AotV2DetachedByteProofTest.ARTIFACT,Map.of(),Set.of()));
        reject(()->admit(MANIFEST,SIGNATURE,AotV2DetachedByteProofTest.ARTIFACT,
                Map.of("RFC8032-test-only",key),Set.of("RFC8032-test-only")));
        reject(()->admit(MANIFEST,SIGNATURE.replace("RFC8032-test-only","unknown-id"),
                AotV2DetachedByteProofTest.ARTIFACT,Map.of("RFC8032-test-only",key),Set.of()));
    }
    @Test void strictRawJsonDeniesDuplicateKeysWrongSchemaInvalidSizesAndJit() throws Exception {
        PublicKey key=AotV2DetachedByteProofTest.key();
        var keys=Map.of("RFC8032-test-only",key);
        byte[] bin=AotV2DetachedByteProofTest.ARTIFACT;
        for(String bad: new String[]{
                "{\"schema_version\":\"atto.aot-artifact.v2\",\"schema_version\":\"atto.aot-artifact.v2\"}",
                "{\"schema_version\":\"atto.aot-artifact.v2\",\"\\u0073chema_version\":\"atto.aot-artifact.v2\"}",
                MANIFEST.replace("\"size_bytes\":128","\"size_bytes\":128.00000000000001"),
                MANIFEST.replace("\"size_bytes\":128","\"size_bytes\":9007199254740993"),
                MANIFEST.replace("\"guest_jit\":false","\"guest_jit\":true"),
                MANIFEST.replace("\"target_triple\":\"x86_64-unknown-linux-gnu\"",
                        "\"target_triple\":\"x86_64-unknown-windows-msvc-linux-gnu\""),
                MANIFEST.replace("\"schema_version\":\"atto.aot-artifact.v2\"",
                        "\"schema_version\":\"oreslang.aot-artifact.v1\""),
                MANIFEST.replace("\"builder_id\":\"test-only-not-a-release-builder\"",
                        "\"builder_id\":\"test-only-not-a-release-builder\",\"signature\":\"fake\"")
        }){
            reject(()->admit(bad,SIGNATURE,bin,keys,Set.of()));
        }
        reject(()->AotV2StrictSignedAdmission.admit(new byte[]{'{','\"','x','\"',':','\"',(byte)0xc3,0x28,'\"','}'},
                utf8(SIGNATURE),bin,keys,Set.of()));
        reject(()->admit(MANIFEST,SIGNATURE.replace("\"algorithm\":\"ed25519\"",
                "\"algorithm\":\"rsa\""),bin,keys,Set.of()));
    }
    @Test void parsesMathematicallyExactExponentNumbersWithoutChangingSignature() throws Exception {
        PublicKey key=AotV2DetachedByteProofTest.key();
        var keys=Map.of("RFC8032-test-only",key);
        for(String spelling:new String[]{"128.00000000000000000","1.28e2","128e0"}){
            var proof=admit(MANIFEST.replace("\"size_bytes\":128","\"size_bytes\":"+spelling),
                    SIGNATURE,AotV2DetachedByteProofTest.ARTIFACT,keys,Set.of());
            if(proof.manifest().artifactSize()!=128)throw new AssertionError(spelling);
        }
        reject(()->admit(MANIFEST.replace("\"size_bytes\":128","\"size_bytes\":1e100000000"),
                SIGNATURE,AotV2DetachedByteProofTest.ARTIFACT,keys,Set.of()));
    }
    @Test void enforcesJcsUnicodeOrderingAndControlEscapes() {
        var keys=Map.<String,Object>of("\uE000",new java.math.BigDecimal("2"),
                "😀",new java.math.BigDecimal("1"));
        String expected="{\"😀\":1,\"\uE000\":2}";
        if(!expected.equals(AotV2StrictSignedAdmission.canonicalize(keys)))
            throw new AssertionError("JCS UTF16 key ordering drift");
        if(!"{\"x\":0}".equals(AotV2StrictSignedAdmission.canonicalize(
                Map.of("x",new java.math.BigDecimal("-0.0000")))))
            throw new AssertionError("JCS negative zero drift");
        String escaped=AotV2StrictSignedAdmission.canonicalize(Map.of("x","\b\t\n"));
        String expectedEscaped="{\"x\":\"\\b\\t\\n\"}";
        if(!expectedEscaped.equals(escaped))
            throw new AssertionError("JCS escaped controls drift: "+escaped);
        reject(()->AotV2StrictSignedAdmission.canonicalize(
                Map.of("broken",new String(new char[]{(char)0xd800}))));
    }
    public static void main(String[] args) throws Exception {
        var self=new AotV2StrictSignedAdmissionTest();
        self.validatesCompleteRawSignedVectorWithoutPromotingRelease();
        self.rejectsAlteredSignaturesManifestAndArtifactBytes();
        self.enforcesExternallyPinnedAndRevocableTrustAnchors();
        self.strictRawJsonDeniesDuplicateKeysWrongSchemaInvalidSizesAndJit();
        self.parsesMathematicallyExactExponentNumbersWithoutChangingSignature();
        self.enforcesJcsUnicodeOrderingAndControlEscapes();
        System.out.println("JAVA_V2_STRICT_SIGNED_INGRESS_6_TESTS_OK");
    }
}
