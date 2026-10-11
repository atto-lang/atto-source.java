package dev.oreslang;

import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.runtime.MessagePackCollectionCodec.Shape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static dev.oreslang.runtime.MessagePackCollectionCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class MessagePackCollectionCodecTest {
    private static void vector(String schema, Shape shape, Object input, String expectedHex, Object expectedValue) {
        byte[] encoded = encode(schema, shape, input);
        assertArrayEquals(HexFormat.of().parseHex(expectedHex), encoded, "MessagePack golden vector");
        assertEquals(expectedValue, decode(schema, shape, encoded), "schema-guided decode");
        assertArrayEquals(encoded, encode(schema, shape, expectedValue), "canonical re-encode");
    }

    @Test
    void pythonMessagePackCompatibleGoldenVectorsDistinguishContainerSchemas() {
        vector("demo.TupleBoolString.v1", tuple(bool(), string()), List.of(true, "foo"),
                "9301b764656d6f2e5475706c65426f6f6c537472696e672e763192c3a3666f6f",
                List.of(true, "foo"));
        vector("demo.FixedArrayInts.v1", fixedArray(int64(), int64(), int64()),
                new long[]{1, 2, 3},
                "9301b664656d6f2e46697865644172726179496e74732e763193010203",
                List.of(1L, 2L, 3L));
        vector("demo.FixedListNested.v1", fixedList(list(string()), bool()),
                List.of(List.of("a", "b"), false),
                "9301b764656d6f2e46697865644c6973744e65737465642e76319292a161a162c2",
                List.of(List.of("a", "b"), false));
        vector("demo.ArrayStrings.v1", array(string()), List.of("a", "b"),
                "9301b464656d6f2e4172726179537472696e67732e763192a161a162",
                List.of("a", "b"));
        vector("demo.ListStrings.v1", list(string()), List.of("a", "b"),
                "9301b364656d6f2e4c697374537472696e67732e763192a161a162",
                List.of("a", "b"));
        vector("demo.String.v1", string(), "foo",
                "9301ae64656d6f2e537472696e672e7631a3666f6f", "foo");
    }

    @Test
    void rejectsWrongSchemaMalformedInputAndInvalidUTF8() {
        Shape shape = tuple(bool(), string());
        byte[] bytes = encode("demo.TupleBoolString.v1", shape, List.of(true, "foo"));
        assertThrows(IllegalArgumentException.class,
                () -> decode("other.Schema.v1", shape, bytes));
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", fixedArray(string(), string()), bytes));
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", shape, Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", shape, extra));

        byte[] malformed = encode("demo.String.v1", string(), "foo");
        malformed[malformed.length - 3] = (byte) 0xc0;
        malformed[malformed.length - 2] = (byte) 0xaf;
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.String.v1", string(), malformed));
    }

    @Test
    void rejectsUnsafeObjectGraphsAndInvalidFixedArity() {
        Shape typed = fixedArray(int64(), string());
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1, new Object())));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1, "ok", 3)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.Bad.v1", string(), new Object()));

        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.Cyclic.v1", list(list(string())), cyclic));
    }

    @Test
    void decoderEnforcesBudgetsAndNumericalTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.X.v1", list(string()), new byte[MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", string(), "x".repeat(MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", float64(), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", float64(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", int64(), 1.25));
        assertEquals(-128L, decode("demo.I.v1", int64(), encode("demo.I.v1", int64(), -128L)));
        assertEquals(Long.MAX_VALUE,
                decode("demo.I.v1", int64(), encode("demo.I.v1", int64(), Long.MAX_VALUE)));
    }
}
