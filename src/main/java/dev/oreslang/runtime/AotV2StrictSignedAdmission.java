package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Package-private signed V2 JSON admission for external native artifact bytes.
 *
 * It authenticates the closed V2 manifest and file *bytes*, but NEVER loads an
 * executable or claims native guest execution. V1's public admission API and
 * manifest schema remain unchanged. Only a trusted host may supply pinned
 * Ed25519 public keys and revocations; no key is accepted from manifest JSON.
 */
final class AotV2StrictSignedAdmission {
    static final String TIER="AUTHENTICATED_NOT_EXECUTED";
    private static final Pattern SHA=Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY_ID=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:\\-]{0,127}");
    private static final Pattern ED25519_B64=Pattern.compile("[A-Za-z0-9+/]{85}[AQgw]==");
    record Receipt(AotV2ManifestPolicy.Typed manifest, String manifestSha256,
                   String artifactSha256, String tier, boolean loaderVerified,
                   boolean guestExecuted, boolean releaseReady) { }
    private AotV2StrictSignedAdmission(){}

    static Receipt admit(byte[] rawManifest, byte[] rawSignature, byte[] artifact,
                         Map<String, PublicKey> externallyPinnedKeys,
                         Set<String> revokedKeyIds) {
        Objects.requireNonNull(rawManifest,"rawManifest");
        Objects.requireNonNull(rawSignature,"rawSignature");
        Objects.requireNonNull(artifact,"artifact");
        Objects.requireNonNull(externallyPinnedKeys,"externallyPinnedKeys");
        Objects.requireNonNull(revokedKeyIds,"revokedKeyIds");
        // The V1 tokenizer deliberately owns its input. The artifact is copied
        // by the cryptographic byte verifier, and never executed here.
        Object parsedManifest=ActorArtifactManifestJsonV1.parseStrictJsonValue(rawManifest);
        AotV2ManifestPolicy.Typed typed=AotV2ManifestPolicy.validateTree(parsedManifest);
        Object parsedEnvelope=ActorArtifactManifestJsonV1.parseStrictJsonValue(rawSignature);
        Map<String,Object> signature=object(parsedEnvelope,"signature");
        if(!signature.keySet().equals(Set.of("schema_version","manifest_sha256",
                "algorithm","key_id","signature_base64")))fail("closed detached-signature envelope");
        if(!"atto.aot-artifact-signature.v2".equals(text(signature,"schema_version")))
            fail("signature schema version");
        if(!"ed25519".equals(text(signature,"algorithm")))fail("signature algorithm");
        String digest=text(signature,"manifest_sha256");
        if(!SHA.matcher(digest).matches())fail("manifest SHA256");
        String keyId=text(signature,"key_id");
        if(!KEY_ID.matcher(keyId).matches())fail("signing key ID");
        String base64=text(signature,"signature_base64");
        if(!ED25519_B64.matcher(base64).matches())fail("noncanonical Ed25519 encoding");
        byte[] detached;
        try{detached=Base64.getDecoder().decode(base64);}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("invalid detached signature",invalid);}
        if(detached.length!=64||!Base64.getEncoder().encodeToString(detached).equals(base64))
            fail("detached signature length/encoding");
        if(revokedKeyIds.contains(keyId))fail("revoked signing key");
        PublicKey trusted=externallyPinnedKeys.get(keyId);
        if(trusted==null)fail("no independently pinned signing key");
        byte[] canonical=canonicalize(parsedManifest).getBytes(StandardCharsets.UTF_8);
        AotV2DetachedByteProof.Receipt byteProof=AotV2DetachedByteProof.verifyCanonicalBytes(
                canonical,detached,trusted,digest,artifact,typed.artifactSha256(),typed.artifactSize());
        if(byteProof.loaderVerified()||byteProof.guestExecuted()||byteProof.releaseReady())
            throw new IllegalStateException("byte proof unexpectedly claims native execution");
        return new Receipt(typed,digest,typed.artifactSha256(),TIER,false,false,false);
    }

    /** RFC 8785/JCS for the deliberately restricted V2 JSON types.
     * V2 has no arrays and only one numeric field, a JSON-safe integer.
     * Java String ordering is unsigned UTF-16 code-unit ordering, as in JCS.
     */
    static String canonicalize(Object value) {
        StringBuilder out=new StringBuilder();
        appendCanonical(out,value);
        return out.toString();
    }

    private static void appendCanonical(StringBuilder out,Object value) {
        if(value==null){out.append("null");return;}
        if(value instanceof String s){appendString(out,s);return;}
        if(value instanceof Boolean b){out.append(b?"true":"false");return;}
        if(value instanceof BigDecimal decimal){
            BigInteger integer;
            try{integer=decimal.toBigIntegerExact();}
            catch(ArithmeticException noninteger){throw new IllegalArgumentException("JCS V2 nonintegral number",noninteger);}
            if(integer.abs().compareTo(BigInteger.valueOf(9_007_199_254_740_991L))>0)
                fail("JCS V2 unsafe integer");
            out.append(integer.toString());
            return;
        }
        if(value instanceof Map<?,?> entries){
            List<String> keys=new ArrayList<>(entries.size());
            for(Object key:entries.keySet()){
                if(!(key instanceof String s))throw new IllegalArgumentException("non-string canonical key");
                keys.add(s);
            }
            keys.sort(String::compareTo);
            out.append('{');
            for(int i=0;i<keys.size();i++){
                if(i>0)out.append(',');
                String key=keys.get(i);
                appendString(out,key);
                out.append(':');
                appendCanonical(out,entries.get(key));
            }
            out.append('}');
            return;
        }
        // Arrays, object wrappers and non-JSON values are not in V2.
        fail("unsupported canonical V2 JSON value");
    }

    private static void appendString(StringBuilder out,String value){
        out.append('"');
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            switch(c){
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if(c<0x20){
                        out.append("\\u");
                        String hex=Integer.toHexString(c);
                        out.append("0".repeat(4-hex.length())).append(hex);
                    }else if(Character.isHighSurrogate(c)){
                        if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))
                            fail("unpaired JCS high surrogate");
                        out.append(c).append(value.charAt(++i));
                    }else if(Character.isLowSurrogate(c)){
                        fail("unpaired JCS low surrogate");
                    }else out.append(c);
                }
            }
        }
        out.append('"');
    }
    private static Map<String,Object> object(Object raw,String field) {
        if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException(field+" must be object");
        for(Object key:m.keySet())if(!(key instanceof String))fail(field+" invalid object key");
        @SuppressWarnings("unchecked") Map<String,Object> cast=(Map<String,Object>)m;
        return cast;
    }
    private static String text(Map<String,Object> m,String key){
        Object value=m.get(key);
        if(!(value instanceof String s))throw new IllegalArgumentException("signature field type "+key);
        return s;
    }
    private static void fail(String reason){throw new IllegalArgumentException("V2 signed admission rejected: "+reason);}
}
