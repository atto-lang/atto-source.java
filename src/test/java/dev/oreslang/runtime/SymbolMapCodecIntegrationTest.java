package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-level integration checks across the guest Map store, Symbol identity,
 * tagged wire decoder, and actor data boundary. These do not replace guest
 * compiler or JVM/Native Image/LLVM execution tests.
 */
final class SymbolMapCodecIntegrationTest {
    @Test void mapKeysKeepAllNominalDomainsAcrossTaggedWire() {
        OresKeyedMap<Object, Integer> map = OresKeyedMap.stringOrSymbolKeys(
                OresKeyedMap.ValueDomain.INT);
        map.put("ready", 1);
        map.put("#ready", 2);
        map.put(OresSymbol.process("ready"), 3);
        map.put(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"), 4);

        List<byte[]> frames = map.entrySnapshot().stream()
                .map(entry -> SymbolPropertyKeyCodec.encode(entry.getKey()))
                .toList();
        List<Object> decoded = SymbolPropertyKeyCodec.decodeDistinctKeys(frames);
        assertEquals(List.of("ready", "#ready", OresSymbol.process("ready"),
                OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000")), decoded);
        assertNotEquals(decoded.get(0), decoded.get(2));
        assertNotEquals(decoded.get(1), decoded.get(2));
        assertEquals(4, map.size());
    }

    @Test void untrustedCodecRejectsHiddenKeysInGuestMapSnapshot() {
        OresKeyedMap<OresSymbol, Integer> map = OresKeyedMap.symbolKeys(
                OresKeyedMap.ValueDomain.INT);
        map.put(OresSymbol.process("ready"), 1);
        map.put(OresSymbol.process("tenant-a/private"), 2);
        List<byte[]> frames = map.entrySnapshot().stream()
                .map(entry -> SymbolPropertyKeyCodec.encode(entry.getKey()))
                .toList();

        assertThrows(SecurityException.class, () ->
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(frames));
        assertEquals(List.of(OresSymbol.process("ready")),
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(frames.getFirst())));
    }

    @Test void mutableGuestMapIsNeverImplicitlySendable() {
        OresKeyedMap<OresSymbol, Integer> map = OresKeyedMap.symbolKeys(
                OresKeyedMap.ValueDomain.INT);
        map.put(OresSymbol.process("ready"), 1);

        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(map));
        assertThrows(IllegalArgumentException.class, () ->
                ActorRuntime.freeze(List.of(Map.of("embedded", map))));
    }
}
