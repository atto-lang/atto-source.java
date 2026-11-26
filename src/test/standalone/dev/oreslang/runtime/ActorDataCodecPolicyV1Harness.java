package dev.oreslang.runtime;

import java.util.Arrays;
import static dev.oreslang.runtime.ActorDataCodecPolicyV1.*;
/** Run with javac -d OUT [policy source] [this source], then java -cp OUT dev.oreslang.runtime.ActorDataCodecPolicyV1Harness. HOST ONLY. */
public final class ActorDataCodecPolicyV1Harness {
    private static void rejected(Reject want, Runnable action) {
        try { action.run(); }
        catch (PolicyException ex) {
            if (ex.reason != want) throw new AssertionError(ex);
            return;
        }
        throw new AssertionError("expected " + want);
    }
    public static void main(String[] args) {
        byte[] h = new byte[32]; Arrays.fill(h, (byte)7);
        Binding b = select(null,15,1,2,"order.v1",h);
        b.requireMatch("messagepack",2,"order.v1",h);
        select("protobuf",15,2,1,"s",h).requireMatch("protobuf",1,"s",h);
        select("cbor",4,4,1,"s",h).requireMatch("cbor",1,"s",h);
        select("flatbuffers",8,8,1,"s",h).requireMatch("flatbuffers",1,"s",h);
        rejected(Reject.Denied, () -> select(null,2,2,1,"s",h));
        rejected(Reject.Unsupported, () -> select("protobuf",15,1,1,"s",h));
        rejected(Reject.UnknownCodec, () -> select("CBOR",15,15,1,"s",h));
        rejected(Reject.UnknownCodec, () -> select("",15,15,1,"s",h));
        rejected(Reject.InvalidMask, () -> select(null,16,1,1,"s",h));
        rejected(Reject.InvalidMask, () -> select(null,-1,1,1,"s",h));
        rejected(Reject.InvalidBinding, () -> select(null,1,1,0,"s",h));
        rejected(Reject.InvalidBinding, () -> select(null,1,1,0x1_0000_0000L,"s",h));
        for (String s : new String[]{"","a b","é","x".repeat(257)}) {
            rejected(Reject.InvalidBinding, () -> select(null,1,1,2,s,h));
        }
        select(null,1,1,2,"x".repeat(256),h);
        rejected(Reject.InvalidBinding, () -> select(null,1,1,2,"s",new byte[31]));
        rejected(Reject.Mismatch, () -> b.requireMatch("protobuf",2,"order.v1",h));
        rejected(Reject.Mismatch, () -> b.requireMatch("messagepack",1,"order.v1",h));
        rejected(Reject.Mismatch, () -> b.requireMatch("messagepack",2,"order.v2",h));
        rejected(Reject.Mismatch, () -> b.requireMatch("MESSAGEPACK",2,"order.v1",h));
        h[0] = 8;
        rejected(Reject.Mismatch, () -> b.requireMatch("messagepack",2,"order.v1",h));
        h[0] = 7; b.requireMatch("messagepack",2,"order.v1",h);
        System.out.println("ACTOR_DATA_CODEC_POLICY_V1_HOST_PASS");
    }
}
