package dev.oreslang.compiler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local trusted-ledger receipt primitive. Not a compiler attestation service.
 * Only a trusted compiler publisher may hold the external secret; never store
 * it alongside cache entries. Callers must authenticate the complete source
 * graph and expected stage key before lookup. No executable loading here. */
public final class CompilerArtifactReceipt {
    private CompilerArtifactReceipt() {}
    public static final int MAX_ARTIFACT=4*1024*1024, HEADER=112;
    private static final byte[] MAGIC="ATTOCAC1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN="atto.compiler.artifact-receipt.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("E_CACHE_RECEIPT: invalid envelope or key");}
    private static byte[] key(String value){
        if(value==null||value.length()!=64||value.chars().anyMatch(c->!(c>='0'&&c<='9'||c>='a'&&c<='f')))throw invalid();
        return HexFormat.of().parseHex(value);
    }
    private static void secret(byte[] secret){if(secret==null||secret.length!=32)throw invalid();}
    private static byte[] digest(byte[] data){
        try{return MessageDigest.getInstance("SHA-256").digest(data);}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static byte[] mac(byte[] secret,byte[] header){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update(DOMAIN);mac.update(header,0,80);return mac.doFinal();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public static byte[] seal(String expectedKey,byte[] artifact,byte[] trustedSecret){
        secret(trustedSecret);
        if(artifact==null||artifact.length>MAX_ARTIFACT)throw invalid();
        byte[] bytes=artifact.clone();
        byte[] result=new byte[HEADER+bytes.length];
        ByteBuffer.wrap(result).put(MAGIC).put(key(expectedKey)).putLong(bytes.length).put(digest(bytes));
        System.arraycopy(mac(trustedSecret,result),0,result,80,32);
        System.arraycopy(bytes,0,result,HEADER,bytes.length);
        return result;
    }
    /** Returns a defensive copy of exactly the verified bytes, never a file path. */
    public static byte[] verify(String expectedKey,byte[] envelope,byte[] trustedSecret){
        secret(trustedSecret);byte[] expected=key(expectedKey);
        if(envelope==null||envelope.length<HEADER||envelope.length>HEADER+MAX_ARTIFACT)throw invalid();
        byte[] data=envelope.clone();
        if(!Arrays.equals(MAGIC,Arrays.copyOfRange(data,0,8))||
           !MessageDigest.isEqual(expected,Arrays.copyOfRange(data,8,40))||
           ByteBuffer.wrap(data,40,8).getLong()!=data.length-HEADER||
           !MessageDigest.isEqual(mac(trustedSecret,data),Arrays.copyOfRange(data,80,112)))throw invalid();
        byte[] bytes=Arrays.copyOfRange(data,HEADER,data.length);
        if(!MessageDigest.isEqual(digest(bytes),Arrays.copyOfRange(data,48,80)))throw invalid();
        return bytes;
    }
}
