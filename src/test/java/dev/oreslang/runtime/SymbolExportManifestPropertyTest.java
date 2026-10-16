package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic adversarial properties of an export manifest's sealed view.
 * These do not rely on HashMap/HashSet identity or a VM-wide Symbol intern pool.
 */
final class SymbolExportManifestPropertyTest {
    @Test
    void randomizedIdentitySetRejectsEveryLogicalSymbolCollision() {
        Random random = new Random(0x5EED_2026L);
        var scope = new SymbolPropertyKeyCodec.AdmissionScope(
                "tenant-a", "mailbox", "v1");
        for (int round = 0; round < 300; round++) {
            Set<OresSymbol> source = Collections.newSetFromMap(
                    new IdentityHashMap<OresSymbol, Boolean>());
            Set<OresSymbol> expected = new LinkedHashSet<>();
            boolean collision = false;
            int count = 1 + random.nextInt(28);
            for (int index = 0; index < count; index++) {
                int number = random.nextInt(42);
                String name = number % 3 == 0 ? "café-" + number
                        : "symbol-" + number;
                // Deliberately create non-interned, logically equal Symbols.
                if (number % 3 == 0 && random.nextBoolean()) {
                    name = "cafe\u0301-" + number;
                }
                OresSymbol symbol = OresSymbol.process(name);
                collision |= !expected.add(symbol);
                assertTrue(source.add(symbol), "identity-backed set must keep source entries");
            }

            if (collision) {
                IllegalArgumentException denied = assertThrows(
                        IllegalArgumentException.class,
                        () -> new SymbolPropertyKeyCodec.ExportManifest(
                                Map.of(scope, source)));
                assertTrue(denied.getMessage().contains("duplicate logical Symbol"),
                        denied.getMessage());
            } else {
                var manifest = new SymbolPropertyKeyCodec.ExportManifest(
                        Map.of(scope, source));
                for (OresSymbol symbol : expected) {
                    assertTrue(manifest.permits(scope, symbol));
                }
                assertFalse(manifest.permits(scope, OresSymbol.process("not-exported")));
                assertFalse(manifest.permits(
                        new SymbolPropertyKeyCodec.AdmissionScope(
                                "tenant-b", "mailbox", "v1"),
                        expected.iterator().next()));
                source.clear();
                for (OresSymbol symbol : expected) {
                    assertTrue(manifest.permits(scope, symbol),
                            "caller mutations must never revoke sealed snapshot");
                }
            }
        }
    }

    @Test
    void randomizedIdentityMapRejectsEveryLogicalScopeCollision() {
        Random random = new Random(0xAC70_2026L);
        for (int round = 0; round < 240; round++) {
            IdentityHashMap<SymbolPropertyKeyCodec.AdmissionScope, Set<OresSymbol>> source =
                    new IdentityHashMap<>();
            Set<SymbolPropertyKeyCodec.AdmissionScope> expected = new LinkedHashSet<>();
            boolean collision = false;
            int count = 1 + random.nextInt(20);
            for (int i = 0; i < count; i++) {
                var scope = new SymbolPropertyKeyCodec.AdmissionScope(
                        "tenant-" + random.nextInt(3),
                        "endpoint-" + random.nextInt(14), "v1");
                collision |= !expected.add(scope);
                source.put(scope, Set.of(OresSymbol.process("allowed")));
            }
            if (collision) {
                IllegalArgumentException denied = assertThrows(
                        IllegalArgumentException.class,
                        () -> new SymbolPropertyKeyCodec.ExportManifest(source));
                assertTrue(denied.getMessage().contains("duplicate logical export scope"),
                        denied.getMessage());
            } else {
                var manifest = new SymbolPropertyKeyCodec.ExportManifest(source);
                for (var scope : expected) {
                    assertTrue(manifest.permits(scope, OresSymbol.process("allowed")));
                }
            }
        }
    }

    @Test
    void aggregateExportCountCannotBeEvadedByManyIndividuallyValidSets() {
        var scopeA = new SymbolPropertyKeyCodec.AdmissionScope("tenant", "a", "v1");
        var scopeB = new SymbolPropertyKeyCodec.AdmissionScope("tenant", "b", "v1");
        Set<OresSymbol> first = new LinkedHashSet<>();
        Set<OresSymbol> second = new LinkedHashSet<>();
        for (int i = 0; i < 2049; i++) {
            first.add(OresSymbol.process("a-" + i));
            second.add(OresSymbol.process("b-" + i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SymbolPropertyKeyCodec.ExportManifest(
                        Map.of(scopeA, first, scopeB, second)));
        second.remove(OresSymbol.process("b-2048"));
        first.remove(OresSymbol.process("a-2048"));
        var exactLimit = new SymbolPropertyKeyCodec.ExportManifest(
                Map.of(scopeA, first, scopeB, second));
        assertTrue(exactLimit.permits(scopeA, OresSymbol.process("a-2047")));
        assertTrue(exactLimit.permits(scopeB, OresSymbol.process("b-2047")));
    }
}
