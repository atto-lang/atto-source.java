package dev.oreslang.compiler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Candidate host ledger admission only. The expected digest must come from
 * an independent current host authority. No durable anti-rollback provider,
 * persistent master storage, compiler cache activation or signing service. */
public final class CompilerCacheLedger {
    private CompilerCacheLedger() {}
    private static final byte[] MAGIC="ATTOLED1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN="atto.compiler.local-receipt-key.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("E_CACHE_LEDGER: invalid or stale record");}
    private static byte[] scope(String value){
        if(value==null||value.isEmpty()||value.length()>512||value.chars().anyMatch(c->c<33||c>126))throw invalid();
        return value.getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] digest(byte[] data){
        try{return MessageDigest.getInstance("SHA-256").digest(data);}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static byte[] derive(byte[] scope,long epoch,byte[] master){
        // long is an unsigned 64-bit wire bit pattern; zero is invalid.
        if(epoch==0||master==null||master.length!=32||MessageDigest.isEqual(master,new byte[32]))throw invalid();
        try{
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(master,"HmacSHA256"));
            mac.update(DOMAIN);
            return mac.doFinal(ByteBuffer.allocate(4+scope.length+8).putInt(scope.length).put(scope).putLong(epoch).array());
        }catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public static byte[] record(String scope,long epoch,byte[] master){
        byte[] id=scope(scope);
        return ByteBuffer.allocate(52+id.length).put(MAGIC).putInt(id.length).put(id).putLong(epoch).put(digest(derive(id,epoch,master))).array();
    }
    public static byte[] admit(String scope,byte[] record,byte[] currentPin,byte[] master){
        byte[] id=scope(scope);
        if(record==null||record.length!=52+id.length||currentPin==null||currentPin.length!=32)throw invalid();
        byte[] bytes=record.clone();
        if(!MessageDigest.isEqual(digest(bytes),currentPin)||!Arrays.equals(MAGIC,Arrays.copyOfRange(bytes,0,8))||
            ByteBuffer.wrap(bytes,8,4).getInt()!=id.length||!Arrays.equals(id,Arrays.copyOfRange(bytes,12,12+id.length)))throw invalid();
        long epoch=ByteBuffer.wrap(bytes,12+id.length,8).getLong();
        byte[] key=derive(id,epoch,master);
        if(!MessageDigest.isEqual(digest(key),Arrays.copyOfRange(bytes,20+id.length,bytes.length)))throw invalid();
        return key;
    }
}
