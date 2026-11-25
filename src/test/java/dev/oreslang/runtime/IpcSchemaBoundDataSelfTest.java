package dev.oreslang.runtime;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

public final class IpcSchemaBoundDataSelfTest {
    private IpcSchemaBoundDataSelfTest() { }
    private static final byte[] KEY = new byte[32];
    private static final byte[] CONNECTION = new byte[16];
    private static final byte[] SHAPE = new byte[32];
    static { Arrays.fill(KEY,(byte)7); Arrays.fill(CONNECTION,(byte)8); Arrays.fill(SHAPE,(byte)9); }
    private static final byte[] BODY = { (byte)0x82, (byte)0xf5, 1 };
    private static int checks;
    private static void check(boolean ok) { ++checks; if (!ok) throw new AssertionError("schema DATA check "+checks); }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt f) throws Exception {
        try { f.run(); throw new AssertionError("schema DATA accepted invalid input"); }
        catch (IOException expected) { ++checks; }
    }
    private static IpcSchemaBoundDataV1.Binding binding(IpcSchemaBoundDataV1.Codec codec,long version,String schema,byte[] digest) {
        return new IpcSchemaBoundDataV1.Binding(codec,version,schema,digest);
    }
    private static IpcReceiptCorrelation.Envelope envelope(byte[] payload,long recipient) throws Exception {
        return new IpcReceiptCorrelation.Envelope("tenant","sender","receiver","schemaSession",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
                1,recipient,3,4,1,1);
    }
    private static byte[] frame(byte[] payload,IpcReceiptCorrelation.Envelope e) throws Exception {
        return IpcAuthenticatedSocketTransport.encodeDataFrame(e,payload,5,CONNECTION,KEY);
    }
    private static byte[] open(byte[] f,IpcReceiptCorrelation.Envelope e,IpcSchemaBoundDataV1.Binding b) throws Exception {
        return IpcSchemaBoundDataV1.authenticateAndUnwrap(f,e,5,
                new IpcAuthenticatedSocketTransport.InboundReplayGate(),CONNECTION,KEY,b);
    }
    public static void main(String[] args) throws Exception {
        var expected=binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"order.v1",SHAPE);
        var payload=IpcSchemaBoundDataV1.wrap(expected,BODY);
        var e=envelope(payload,2);
        var f=frame(payload,e);
        check(Arrays.equals(open(f,e,expected),BODY));
        for (var other : new IpcSchemaBoundDataV1.Binding[]{
                binding(IpcSchemaBoundDataV1.Codec.PROTOBUF,1,"order.v1",SHAPE),
                binding(IpcSchemaBoundDataV1.Codec.CBOR,2,"order.v1",SHAPE),
                binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"other.v1",SHAPE),
                binding(IpcSchemaBoundDataV1.Codec.CBOR,1,"order.v1",new byte[32])}) {
            reject(()->open(f,e,other)); // valid MAC but wrong binding
        }
        reject(()->open(f,envelope(payload,3),expected)); // stale recipient
        var replay=new IpcAuthenticatedSocketTransport.InboundReplayGate();
        check(Arrays.equals(IpcSchemaBoundDataV1.authenticateAndUnwrap(f,e,5,replay,CONNECTION,KEY,expected),BODY));
        reject(()->IpcSchemaBoundDataV1.authenticateAndUnwrap(f,e,5,replay,CONNECTION,KEY,expected));
        for(int i=0;i<f.length;i++){
            byte[] tampered=f.clone();tampered[i]^=1;
            reject(()->open(tampered,e,expected));
        }
        for(int n=0;n<payload.length;n++){
            byte[] truncated=Arrays.copyOf(payload,n);
            var te=envelope(truncated,2);
            reject(()->open(frame(truncated,te),te,expected));
        }
        byte[] trailing=Arrays.copyOf(payload,payload.length+1);
        var extra=envelope(trailing,2);
        reject(()->open(frame(trailing,extra),extra,expected));
        var legacy=envelope(BODY,2);
        reject(()->open(frame(BODY,legacy),legacy,expected));
        reject(()->IpcSchemaBoundDataV1.wrap(expected,new byte[1024*1024+1]));
        for(long version:new long[]{1,0x7fffffffL,0x80000000L,0xffffffffL}){
            var boundary=binding(IpcSchemaBoundDataV1.Codec.CBOR,version,"order.v1",SHAPE);
            byte[] wrapped=IpcSchemaBoundDataV1.wrap(boundary,BODY);
            check(Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(wrapped,5,4).getInt())==version);
            var envelope=envelope(wrapped,2);
            check(Arrays.equals(open(frame(wrapped,envelope),envelope,boundary),BODY));
            if(version!=1)reject(()->open(frame(wrapped,envelope),envelope,expected));
        }
        for(long bad:new long[]{0,-1,0x100000000L,Long.MAX_VALUE}){
            try {binding(IpcSchemaBoundDataV1.Codec.CBOR,bad,"order.v1",SHAPE);
                 throw new AssertionError("invalid u32 version admitted");}
            catch(IllegalArgumentException expectedFailure){++checks;}
        }
        System.out.println("JAVA_IPC_SCHEMA_BOUND_DATA_PASS checks="+checks);
    }
}
