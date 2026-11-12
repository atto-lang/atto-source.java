package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Closed, quota-checked binary Map key/value domains for Atto actor storage. */
final class OresBytesMapDomainTest {
    @Test
    void equalBinaryKeysUseContentHashAndRetainImmutableValues() {
        OresKeyedMap<OresBytes, OresBytes> values =
                OresKeyedMap.bytesKeys(OresKeyedMap.ValueDomain.BYTES);
        OresBytes key = OresBytes.copyOf(new byte[]{0, (byte) 255, 13, 10});
        OresBytes equivalent = OresBytes.copyOf(new byte[]{0, (byte) 255, 13, 10});
        OresBytes data = OresBytes.copyOf(new byte[]{0, 1, (byte) 254});
        values.put(key, data);
        assertTrue(values.lookup(equivalent).present());
        assertEquals(data, values.lookup(equivalent).value());
        assertEquals(1, values.size());
        assertTrue(values.take(equivalent).present());
        assertEquals(0, values.size());
    }

    @Test
    void cannotAliasBinaryKeyOrOverwriteThroughMutableCallerArray() {
        OresKeyedMap<OresBytes, OresBytes> values =
                OresKeyedMap.bytesKeys(OresKeyedMap.ValueDomain.BYTES);
        byte[] key = {1, 2};
        byte[] val = {3, 4};
        values.put(OresBytes.copyOf(key), OresBytes.copyOf(val));
        key[0] = 99;
        val[0] = 99;
        assertArrayEquals(new byte[]{3, 4},
                values.getRequired(OresBytes.copyOf(new byte[]{1, 2})).copyToArray());
        assertEquals(1, values.size());
    }

    @Test
    void oversizedBinaryKeyAndWrongDomainFailClosedBeforeMutation() {
        OresKeyedMap<OresBytes, Object> values =
                OresKeyedMap.bytesKeys(OresKeyedMap.ValueDomain.BYTES);
        OresBytes key = OresBytes.copyOf(new byte[]{7});
        assertThrows(IllegalArgumentException.class,
                () -> values.put(key, "not bytes"));
        assertThrows(IllegalArgumentException.class,
                () -> values.put(OresBytes.copyOf(new byte[4097]), OresBytes.copyOf(new byte[0])));
        assertEquals(0, values.size());
        assertThrows(IllegalArgumentException.class,
                () -> values.getRequired("7"));
    }
}
