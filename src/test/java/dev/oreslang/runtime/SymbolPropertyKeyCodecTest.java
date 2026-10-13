package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolPropertyKeyCodecTest {
    @Test void tagsKeepStringAndSymbolKeysNominallyDistinct() {
        Object text = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode("ready"));
        Object process = SymbolPropertyKeyCodec.decode(
                SymbolPropertyKeyCodec.encode(OresSymbol.process("ready")));
        assertEquals("ready", text);
        assertEquals(OresSymbol.process("ready"), process);
        assertNotEquals(text, process);
        assertArrayEquals(
                new byte[]{'O', 'K', 1, 0, 0, 3, 'f', 'o', 'o'},
                SymbolPropertyKeyCodec.encode("foo"));
        assertArrayEquals(
                new byte[]{'O', 'K', 1, 1, 0, 3, 'f', 'o', 'o'},
                SymbolPropertyKeyCodec.encode(OresSymbol.process("foo")));
        assertEquals("#foo", SymbolPropertyKeyCodec.decode(
                SymbolPropertyKeyCodec.encode("#foo")));
    }

    @Test void stableAndProcessSymbolsRemainSeparateAcrossRoundTrips() {
        String name = "550e8400-e29b-41d4-a716-446655440000";
        Object process = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(OresSymbol.process(name)));
        Object stable = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(OresSymbol.stable(name)));
        assertNotEquals(process, stable);
        assertEquals(OresSymbol.stable(name), stable);
        assertNotSame(stable, SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(stable)));
    }

    @Test void duplicatesAreRejectedButNamespacedKeysCanCoexist() {
        var keys = List.of(
                SymbolPropertyKeyCodec.encode("foo"),
                SymbolPropertyKeyCodec.encode(OresSymbol.process("foo")));
        assertDoesNotThrow(() -> SymbolPropertyKeyCodec.requireDistinct(keys));
        assertThrows(IllegalArgumentException.class, () ->
                SymbolPropertyKeyCodec.requireDistinct(List.of(
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("café")),
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("cafe\u0301")))));
        assertThrows(IllegalArgumentException.class, () ->
                SymbolPropertyKeyCodec.requireDistinct(List.of(
                        SymbolPropertyKeyCodec.encode("foo"),
                        SymbolPropertyKeyCodec.encode("foo"))));
    }

    @Test void wireDecoderRejectsMalformedLengthsKindsUtf8AndNfc() {
        byte[] valid = SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"));
        byte[] badKind = valid.clone();
        badKind[3] = (byte) 0x7f;
        byte[] badVersion = valid.clone();
        badVersion[2] = 2;
        byte[] badLength = valid.clone();
        badLength[5] = 2;
        byte[] extraByte = Arrays.copyOf(valid, valid.length + 1);
        byte[] badUtf8 = new byte[]{'O', 'K', 1, 1, 0, 2, (byte) 0xc0, (byte) 0x80};
        byte[] emptySymbol = new byte[]{'O', 'K', 1, 1, 0, 0};
        byte[] decomposedName = wire(1, "cafe\u0301".getBytes(StandardCharsets.UTF_8));
        for (byte[] bad : List.of(badKind, badVersion, badLength, extraByte,
                badUtf8, emptySymbol, decomposedName)) {
            assertThrows(IllegalArgumentException.class,
                    () -> SymbolPropertyKeyCodec.decode(bad),
                    Arrays.toString(bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.encode("\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.encode("x".repeat(16_385)));
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.decode(new byte[100_000]));
    }

    @Test void untrustedAdmissionRequiresExactScopedIdentity() {
        OresSymbol secret = OresSymbol.process("tenant-a/private");
        byte[] secretWire = SymbolPropertyKeyCodec.encode(secret);
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(secretWire));
        var tenantA = new SymbolPropertyKeyCodec.AdmissionScope(
                "tenant-a", "actor-notification", "v1");
        var tenantB = new SymbolPropertyKeyCodec.AdmissionScope(
                "tenant-b", "actor-notification", "v1");
        var tenantAV2 = new SymbolPropertyKeyCodec.AdmissionScope(
                "tenant-a", "actor-notification", "v2");
        var sourceExports = new java.util.HashSet<>(Set.of(secret));
        var sourceManifest = new java.util.HashMap<SymbolPropertyKeyCodec.AdmissionScope, Set<OresSymbol>>();
        sourceManifest.put(tenantA, sourceExports);
        var exports = new SymbolPropertyKeyCodec.ExportManifest(sourceManifest);
        // Changes to trusted inputs after sealing cannot expand or revoke the
        // permissions of an admitted manifest snapshot.
        sourceExports.clear();
        sourceManifest.clear();
        assertEquals(secret,
                SymbolPropertyKeyCodec.decodeForUntrusted(secretWire, tenantA, exports));
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(secretWire, tenantB, exports));
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(secretWire, tenantAV2, exports));
        assertThrows(IllegalArgumentException.class,
                () -> new SymbolPropertyKeyCodec.AdmissionScope("", "actor", "v1"));
        assertEquals(OresSymbol.process("ready"),
                SymbolPropertyKeyCodec.decodeForUntrusted(
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"))));
        assertEquals("tenant-a/private",
                SymbolPropertyKeyCodec.decodeForUntrusted(
                        SymbolPropertyKeyCodec.encode("tenant-a/private")));
    }

    @Test void scopedUntrustedAdmissionCannotLaunderStableSymbols() {
        String stableName = "550e8400-e29b-41d4-a716-446655440000";
        OresSymbol stable = OresSymbol.stable(stableName);
        var scope = new SymbolPropertyKeyCodec.AdmissionScope("tenant-a", "worker", "v1");
        var manifest = new SymbolPropertyKeyCodec.ExportManifest(
                java.util.Map.of(scope, Set.of(stable)));
        byte[] stableWire = SymbolPropertyKeyCodec.encode(stable);
        // v1 exports are restricted to PROCESS identities, regardless of
        // whether a trusted component mistakenly includes a stable one.
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(stableWire, scope, manifest));
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(stableWire), scope, manifest));
        assertEquals(stable, SymbolPropertyKeyCodec.decode(stableWire),
                "trusted decoding may still roundtrip stable Symbols");
    }

    @Test void bulkUntrustedDecodeAppliesPolicyBeforeBuildingKeys() {
        var scope = new SymbolPropertyKeyCodec.AdmissionScope("tenant-a", "worker", "v1");
        var other = new SymbolPropertyKeyCodec.AdmissionScope("tenant-b", "worker", "v1");
        OresSymbol exported = OresSymbol.process("tenant-a/exported");
        OresSymbol hidden = OresSymbol.process("tenant-a/hidden");
        var manifest = new SymbolPropertyKeyCodec.ExportManifest(
                java.util.Map.of(scope, Set.of(exported)));
        byte[] text = SymbolPropertyKeyCodec.encode("#ready");
        byte[] core = SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"));
        byte[] allowed = SymbolPropertyKeyCodec.encode(exported);
        byte[] denied = SymbolPropertyKeyCodec.encode(hidden);
        assertEquals(List.of("#ready", OresSymbol.process("ready"), exported),
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(text, core, allowed), scope, manifest));
        assertThrows(SecurityException.class, () ->
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(List.of(allowed)));
        assertThrows(SecurityException.class, () ->
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(allowed), other, manifest));
        assertThrows(SecurityException.class, () ->
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(text, denied), scope, manifest));
        assertThrows(IllegalArgumentException.class, () ->
                SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                        List.of(core, core), scope, manifest));
        List<Object> frozen = SymbolPropertyKeyCodec.decodeDistinctKeysForUntrusted(
                List.of(text, core), scope, manifest);
        text[text.length - 1] = 'X';
        assertEquals("#ready", frozen.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> frozen.add("bad"));
    }

    @Test void entryCountQuotaIsEnforcedBeforeDecode() {
        List<byte[]> tooMany = new ArrayList<>();
        byte[] foo = SymbolPropertyKeyCodec.encode("foo");
        for (int i = 0; i <= SymbolPropertyKeyCodec.MAX_KEYS_PER_OBJECT; i++) {
            tooMany.add(foo);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.requireDistinct(tooMany));
    }

    @Test void validatedKeyCollectionIsImmutableAndDoesNotAliasWireArrays() {
        byte[] wire = SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"));
        List<Object> decoded = SymbolPropertyKeyCodec.decodeDistinctKeys(List.of(wire));
        assertEquals(List.of(OresSymbol.process("ready")), decoded);
        wire[wire.length - 1] = (byte) 'x';
        assertEquals(OresSymbol.process("ready"), decoded.getFirst(),
                "decoded keys must not change when a sender mutates its bytes");
        assertThrows(UnsupportedOperationException.class, () -> decoded.add("evil"));
    }

    @Test void boundedKeyCollectionRejectsExcessiveAggregateBytes() {
        List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            entries.add(SymbolPropertyKeyCodec.encode(
                    "x".repeat(16_000) + Integer.toString(i)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.decodeDistinctKeys(entries));
    }

    @Test void wrongKindAndWireCannotAliasAfterDefensiveSnapshot() {
        byte[] original = SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"));
        Object decoded = SymbolPropertyKeyCodec.decode(original);
        original[3] = (byte) 0; // now says String
        assertEquals(OresSymbol.process("ready"), decoded);
        assertNotEquals("ready", decoded);
    }

    private static byte[] wire(int kind, byte[] name) {
        return ByteBuffer.allocate(6 + name.length)
                .put((byte) 'O').put((byte) 'K').put((byte) 1)
                .put((byte) kind).putShort((short) name.length)
                .put(name).array();
    }
}
