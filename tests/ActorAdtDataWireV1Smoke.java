import dev.oreslang.runtime.ActorAdtDataWireV1;
import java.util.Arrays;
import java.util.HexFormat;

/** Standalone exact-byte Java runtime DATA profile test; no GraalVM dependencies. */
public final class ActorAdtDataWireV1Smoke {
    static void check(boolean valid, String why) {
        if (!valid) throw new AssertionError(why);
    }
    static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    static void roundTrip(ActorAdtDataWireV1.Value value, String expected) {
        var wire = ActorAdtDataWireV1.encode(value);
        check(Arrays.equals(wire, hex(expected)), "wire drift: " + value);
        check(ActorAdtDataWireV1.decode(wire).equals(value), "ADT identity lost: " + value);
    }
    static void deny(byte[] bytes) {
        try {
            ActorAdtDataWireV1.decode(bytes);
            throw new AssertionError("malformed/in-band CONTROL bytes accepted");
        } catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        roundTrip(new ActorAdtDataWireV1.None(), "41544144010100");
        roundTrip(new ActorAdtDataWireV1.SomeOk(42), "415441440101012a00000000000000");
        roundTrip(new ActorAdtDataWireV1.SomeOk(-1), "41544144010101ffffffffffffffff");
        roundTrip(new ActorAdtDataWireV1.SomeOk(Long.MIN_VALUE), "415441440101010000000000000080");
        roundTrip(new ActorAdtDataWireV1.SomeErr(""), "415441440101020000");
        roundTrip(new ActorAdtDataWireV1.SomeErr("é"), "415441440101020200c3a9");
        var good = hex("415441440101020200c3a9");
        for (int n = 0; n < good.length; ++n) deny(Arrays.copyOf(good, n));
        for (int idx = 0; idx < 7; ++idx) {
            var changed = good.clone();
            changed[idx] = (byte) (idx == 6 ? 127 : changed[idx] ^ 1);
            deny(changed);
        }
        deny(hex("41544144010100ff")); // extra None payload
        deny(hex("4154414401010100000000000000")); // truncated i64
        deny(hex("415441440101020300c3a9")); // false string length
        deny(hex("415441440101020200c328")); // invalid UTF-8
        var over = new byte[266];
        System.arraycopy(good, 0, over, 0, 7);
        deny(over);
        try {
            new ActorAdtDataWireV1.SomeErr("\uD800");
            throw new AssertionError("unpaired UTF-16 surrogate encoded");
        } catch (IllegalArgumentException expected) { }
        try {
            new ActorAdtDataWireV1.SomeErr("a".repeat(257));
            throw new AssertionError("unbounded Err payload encoded");
        } catch (IllegalArgumentException expected) { }
        System.out.println("JAVA_ADT_DATA_WIRE_V1_PASS|profiles=6|control_authority=none|guest_execution=no");
    }
}
