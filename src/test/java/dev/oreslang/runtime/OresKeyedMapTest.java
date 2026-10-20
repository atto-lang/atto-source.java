package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapTest {
    @Test
    void integralHostRepresentationsShareOneLogicalKey() {
        var duplicate = assertThrows(
                OresKeyedMap.DuplicateKeyException.class,
                () -> OresKeyedMap.fromEntries(List.of(
                        new OresEntry((byte) 1, "byte"),
                        new OresEntry(1L, "long"))));
        assertEquals(1L, duplicate.key());
    }

    @Test
    void bigIntegerLookupUsesCanonicalIntegerIdentityButSnapshotPreservesGuestKey() {
        BigInteger large = new BigInteger("922337203685477580812345");
        OresKeyedMap map = OresKeyedMap.fromEntries(List.of(
                new OresEntry(large, "large")));

        assertEquals("large", map.getRequired(large));
        assertEquals(large, map.entrySnapshot().getFirst().key());

        OresKeyedMap ordinary = OresKeyedMap.fromEntries(List.of(
                new OresEntry(7L, "seven")));
        assertEquals("seven", ordinary.getRequired(BigInteger.valueOf(7)));
        assertEquals(7L, ordinary.entrySnapshot().getFirst().key());
    }

    @Test
    void mixedKeyDomainsFailClosed() {
        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromEntries(List.of(
                        new OresEntry(1L, "one"),
                        new OresEntry("1", "string"))));
        assertTrue(mixed.getMessage().contains("domain"));
    }

    @Test
    void stringAndSymbolIdentityAreNeverCoerced() {
        OresKeyedMap strings = OresKeyedMap.fromEntries(List.of(
                new OresEntry("ready", 1L)));
        assertEquals(1L, strings.getRequired("ready"));
        assertThrows(
                IllegalArgumentException.class,
                () -> strings.getRequired(OresSymbol.process("ready")));

        OresKeyedMap symbols = OresKeyedMap.fromEntries(List.of(
                new OresEntry(OresSymbol.process("ready"), 2L)));
        assertEquals(2L, symbols.getRequired(OresSymbol.process("ready")));
        assertThrows(
                IllegalArgumentException.class,
                () -> symbols.getRequired("ready"));
    }

    @Test
    void symbolLogicalEqualityRejectsDuplicateObjects() {
        OresSymbol first = OresSymbol.process("ready");
        OresSymbol second = OresSymbol.process("ready");
        assertNotSame(first, second);

        assertThrows(
                OresKeyedMap.DuplicateKeyException.class,
                () -> OresKeyedMap.fromEntries(List.of(
                        new OresEntry(first, 1L),
                        new OresEntry(second, 2L))));
    }

    @Test
    void insertionOrderIsDeterministicAndSnapshotsAreDetached() {
        OresKeyedMap map = OresKeyedMap.fromEntries(List.of(
                new OresEntry(3L, 30L),
                new OresEntry(1L, 10L),
                new OresEntry(2L, 20L)));

        List<OresEntry> snapshot = map.entrySnapshot();
        assertEquals(List.of(3L, 1L, 2L),
                snapshot.stream().map(OresEntry::key).toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(new OresEntry(4L, 40L)));
    }

    @Test
    void inputSizeLiesAreRejectedRatherThanTrusted() {
        List<OresEntry> backing = List.of(
                new OresEntry(1L, 10L),
                new OresEntry(2L, 20L));

        AbstractList<OresEntry> underReports = new AbstractList<>() {
            @Override public OresEntry get(int index) { return backing.get(index); }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<OresEntry> iterator() {
                return backing.iterator();
            }
        };
        IllegalArgumentException under = assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromEntries(underReports));
        assertTrue(under.getMessage().contains("size changed"));

        AbstractList<OresEntry> overReports = new AbstractList<>() {
            @Override public OresEntry get(int index) { return backing.get(index); }
            @Override public int size() { return 3; }
            @Override public java.util.Iterator<OresEntry> iterator() {
                return backing.iterator();
            }
        };
        IllegalArgumentException over = assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromEntries(overReports));
        assertTrue(over.getMessage().contains("size changed"));
    }

    @Test
    void entryAndAggregateQuotasAreCheckedBeforePublication() {
        ArrayList<OresEntry> tooMany = new ArrayList<>();
        for (int i = 0; i < OresKeyedMap.MAX_ENTRIES + 1; i++) {
            tooMany.add(new OresEntry((long) i, (long) i));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromEntries(tooMany));

        String huge = "x".repeat(OresKeyedMap.MAX_KEY_BYTES + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromEntries(List.of(
                        new OresEntry(huge, 1L))));
    }

    @Test
    void missingLookupNeverUsesNullAsAGuestValue() {
        OresKeyedMap map = OresKeyedMap.fromEntries(List.of(
                new OresEntry(1L, 10L)));

        OresKeyedMap.Lookup missing = map.lookup(2L);
        assertFalse(missing.present());
        assertNull(missing.value());
        assertThrows(
                IllegalArgumentException.class,
                () -> map.getRequired(2L));
    }

    @Test
    void nullKeysAndValuesAreRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new OresEntry(null, 1L));
        assertThrows(
                NullPointerException.class,
                () -> new OresEntry(1L, null));
    }
}
