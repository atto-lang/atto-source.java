package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.runtime.ActorMessageWireV1.*;

/** Stable cross-backend bytes are also asserted by LLVM's native C test. */
final class ActorMessageWireV1Test {
    private static final long SCHEMA = 0x0102030405060708L;
    private static final int VARIANT = 0x11223344;
    private static final String GOLDEN =
            "4f4d5347010000000102030405060708112233440000002a"
          + "040000000403000000026869020000000000000007"
          + "050000000201010100060000000103000000026f6b";

    private static Value sample() {
        return new TupleValue(List.of(
                new StringValue("hi"), new IntValue(7),
                new ArrayValue(List.of(new BoolValue(true), new BoolValue(false))),
                new ListValue(List.of(new StringValue("ok")))));
    }

    @Test
    void canonicalBytesAndDetachedRoundTrip() {
        List<Value> source = new ArrayList<>();
        source.add(new StringValue("hi"));
        TupleValue copied = new TupleValue(source);
        source.clear();
        assertEquals(1, copied.values().size());
        assertThrows(UnsupportedOperationException.class,
                () -> copied.values().add(new StringValue("escape")));

        byte[] encoded = encode(SCHEMA, VARIANT, sample());
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN), encoded);
        Decoded decoded = decode(encoded, SCHEMA, VARIANT);
        assertEquals(sample(), decoded.value());
        assertEquals(SCHEMA, decoded.schemaId());
        assertEquals(VARIANT, decoded.variantId());

        encoded[34] = 'X';  // caller mutation after decode cannot alias the value
        assertEquals(sample(), decoded.value());
    }

    @Test
    void failClosedOnWrongIdentityAndMalformedPayload() {
        byte[] good = encode(SCHEMA, VARIANT, sample());
        assertThrows(IllegalArgumentException.class,
                () -> decode(good, SCHEMA + 1, VARIANT));
        assertThrows(IllegalArgumentException.class,
                () -> decode(good, SCHEMA, VARIANT + 1));
        assertThrows(IllegalArgumentException.class,
                () -> encode(0, VARIANT, sample()));
        assertThrows(IllegalArgumentException.class,
                () -> decode(new byte[] {1, 2, 3}, SCHEMA, VARIANT));

        byte[] extra = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> decode(extra, SCHEMA, VARIANT));
        byte[] trailing = good.clone();
        trailing[23] = (byte) 41; // declared length does not match
        assertThrows(IllegalArgumentException.class,
                () -> decode(trailing, SCHEMA, VARIANT));
        byte[] badBool = good.clone();
        badBool[51] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> decode(badBool, SCHEMA, VARIANT));
        byte[] utf8 = good.clone();
        utf8[34] = (byte) 0xc0;
        utf8[35] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class,
                () -> decode(utf8, SCHEMA, VARIANT));
        byte[] oversizedCount = good.clone();
        oversizedCount[25] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class,
                () -> decode(oversizedCount, SCHEMA, VARIANT));
        byte[] unknownTag = good.clone();
        unknownTag[24] = 99;
        assertThrows(IllegalArgumentException.class,
                () -> decode(unknownTag, SCHEMA, VARIANT));
    }

    @Test
    void recursionLimitsAndUnicodeValidation() {
        Value v = new BoolValue(true);
        for (int i = 0; i < MAX_DEPTH + 1; i++) {
            v = new TupleValue(List.of(v));
        }
        Value tooDeep = v;
        assertThrows(IllegalArgumentException.class,
                () -> encode(SCHEMA, VARIANT, tooDeep));

        assertThrows(IllegalArgumentException.class,
                () -> encode(SCHEMA, VARIANT, new StringValue("\uD800")));
        assertEquals(new StringValue("¡hola 🐢!"),
                decode(encode(SCHEMA, VARIANT, new StringValue("¡hola 🐢!")),
                        SCHEMA, VARIANT).value());
    }
}
