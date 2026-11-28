package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OresPropertyKeyLayoutTest {
    @Test void symbolAndStringNamespacesAreDisjointEvenWithIdenticalNames() {
        var string = OresPropertyKey.string("status");
        var symbol = OresPropertyKey.symbol(OresSymbol.process("status"));
        assertNotEquals(string, symbol);
        var slots = OresPropertyLayout.declared(List.of(string, symbol));
        assertEquals(0, slots.requireSlot(string));
        assertEquals(1, slots.requireSlot(symbol));
        assertFalse(slots.findSlot(OresPropertyKey.string("#status")).isPresent());
    }

    @Test void symbolNfcIdentityAndDuplicatesAreCheckedBeforeSlotsAreAssigned() {
        var decomposed = OresPropertyKey.symbol(OresSymbol.process("cafe\u0301"));
        var composed = OresPropertyKey.symbol(OresSymbol.process("caf\u00e9"));
        assertEquals(decomposed, composed);
        assertThrows(IllegalArgumentException.class,
                () -> OresPropertyLayout.declared(List.of(decomposed, composed)));
        assertNotEquals(decomposed, OresPropertyKey.string("caf\u00e9"));
    }

    @Test void processAndStableSymbolsWithIdenticalTextRemainDifferent() {
        var name = "550e8400-e29b-41d4-a716-446655440000";
        var process = OresPropertyKey.symbol(OresSymbol.process(name));
        var stable = OresPropertyKey.symbol(OresSymbol.stable(name));
        assertNotEquals(process, stable);
        assertEquals(1, process.domain().wireTag());
        assertEquals(2, stable.domain().wireTag());
        assertEquals(2, OresPropertyLayout.declared(List.of(process, stable)).size());
    }

    @Test void stringKeysKeepTheirExactSpellingButRejectMalformedUnicode() {
        var decomposed = OresPropertyKey.string("cafe\u0301");
        var composed = OresPropertyKey.string("caf\u00e9");
        assertNotEquals(decomposed, composed);
        assertEquals(2, OresPropertyLayout.canonical(List.of(decomposed, composed)).size());
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.string("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.string("\udc00"));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.string("\ud800A"));
    }

    @Test void boundedKeyMetadataRejectsOversizedStringAndTooManyFields() {
        assertEquals(16384, OresPropertyKey.string("x".repeat(16384)).name().length());
        assertThrows(IllegalArgumentException.class,
                () -> OresPropertyKey.string("x".repeat(16385)));
        var keys = new java.util.ArrayList<OresPropertyKey>();
        for (int i = 0; i < OresPropertyLayout.MAX_FIELDS; i++) {
            keys.add(OresPropertyKey.string("field_" + i));
        }
        assertEquals(4096, OresPropertyLayout.declared(keys).size());
        keys.add(OresPropertyKey.string("overflow"));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyLayout.canonical(keys));
    }

    @Test void lengthIsMeasuredInUtf8BytesNotUtf16CodeUnits() {
        String emoji = "\uD83D\uDE00";
        assertEquals(4096 * 2, OresPropertyKey.string(emoji.repeat(4096)).name().length());
        assertThrows(IllegalArgumentException.class,
                () -> OresPropertyKey.string(emoji.repeat(4097)));
    }

    @Test void lyingCollectionSizeCannotBypassSnapshotQuota() {
        java.util.Collection<OresPropertyKey> hostile = new java.util.AbstractCollection<>() {
            @Override public int size() { return 0; }
            @Override public java.util.Iterator<OresPropertyKey> iterator() {
                return new java.util.Iterator<>() {
                    private int next;
                    @Override public boolean hasNext() { return next <= OresPropertyLayout.MAX_FIELDS; }
                    @Override public OresPropertyKey next() { return OresPropertyKey.string("key_" + next++); }
                };
            }
        };
        assertThrows(IllegalArgumentException.class, () -> OresPropertyLayout.declared(hostile));
    }

    @Test void aggregateTaggedKeyByteBudgetChecksBeforeInsertion() {
        var keys = new java.util.ArrayList<OresPropertyKey>();
        for (int i = 0; i < 63; i++) {
            String suffix = Integer.toString(i);
            keys.add(OresPropertyKey.string("x".repeat(16384 - suffix.length()) + suffix));
        }
        assertEquals(63, OresPropertyLayout.canonical(keys).size());
        keys.add(OresPropertyKey.string("z".repeat(16384)));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyLayout.declared(keys));
    }

    @Test void canonicalLayoutIsDeterministicAcrossInputOrders() {
        var a = OresPropertyKey.string("z");
        var b = OresPropertyKey.string("a");
        var c = OresPropertyKey.symbol(OresSymbol.process("a"));
        var d = OresPropertyKey.symbol(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"));
        var forward = OresPropertyLayout.canonical(List.of(a, d, c, b));
        var backward = OresPropertyLayout.canonical(List.of(b, c, d, a));
        assertEquals(forward.keys(), backward.keys());
        assertEquals(List.of(b, a, c, d), forward.keys());
        assertEquals(3, forward.requireSlot(d));
    }

    @Test void staticLayoutAndKeysCannotBeMutatedFromOutside() {
        var input = new java.util.ArrayList<OresPropertyKey>();
        input.add(OresPropertyKey.string("status"));
        var layout = OresPropertyLayout.declared(input);
        input.clear();
        assertEquals(1, layout.size());
        assertThrows(UnsupportedOperationException.class,
                () -> layout.keys().add(OresPropertyKey.string("other")));
        assertThrows(IllegalArgumentException.class,
                () -> layout.requireSlot(OresPropertyKey.symbol(OresSymbol.process("status"))));
    }

    @Test void guestBridgeAcceptsOnlyStringAndSymbolValues() {
        assertEquals(OresPropertyKey.string("ready"), OresPropertyKey.fromGuestValue("ready"));
        assertEquals(OresPropertyKey.symbol(OresSymbol.process("ready")),
                OresPropertyKey.fromGuestValue(OresSymbol.process("ready")));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.fromGuestValue(1));
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.fromGuestValue(null));
        assertThrows(IllegalArgumentException.class,
                () -> OresPropertyKey.fromGuestValue(OresPropertyKey.string("ready")));
    }

    @Test void taggedKeysPreserveMapLookupWithoutStringification() {
        Map<OresPropertyKey, Integer> map = new HashMap<>();
        map.put(OresPropertyKey.string("ready"), 10);
        map.put(OresPropertyKey.symbol(OresSymbol.process("ready")), 20);
        map.put(OresPropertyKey.string("#ready"), 30);
        assertEquals(3, map.size());
        assertEquals(20, map.get(OresPropertyKey.fromGuestValue(OresSymbol.process("ready"))));
        assertEquals(10, map.get(OresPropertyKey.fromGuestValue("ready")));
        assertEquals(30, map.get(OresPropertyKey.fromGuestValue("#ready")));
    }
}
