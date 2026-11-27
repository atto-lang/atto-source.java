package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageLayout;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class IncrementalPackageLayoutIsolationTest {
    private static final String ID = "src/net/http/a.atto";
    private static final Map<String, String> SOURCE = Map.of(ID,
            "pub fnc answer(): int { return 42; }");
    private static final PackageLayout SHOP = new PackageLayout(Map.of("src", "shop"));
    private static final PackageLayout OTHER = new PackageLayout(Map.of("src", "other"));

    @Test void defaultBuildDoesNotInheritPackageLayout() {
        var compiler = new IncrementalCompiler();
        var packaged = compiler.compile(SOURCE, Map.of(), SHOP);
        assertEquals("net.http", packaged.units().get(ID).namespace());
        assertEquals("pkg:shop", packaged.units().get(ID).packageId());
        var standalone = compiler.compile(SOURCE);
        assertEquals(Set.of(ID), standalone.reparsedUnits());
        assertNull(standalone.units().get(ID).namespace());
        assertEquals("file:" + ID, standalone.units().get(ID).packageId());
        assertTrue(compiler.compile(SOURCE).rebuiltUnits().isEmpty());
    }

    @Test void failedLayoutTransitionRetainsCommittedSnapshot() {
        var compiler = new IncrementalCompiler();
        var first = compiler.compile(SOURCE, Map.of(), SHOP);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                Map.of(ID, "pub fnc answer(: int {"), Map.of(), OTHER));
        var recovered = compiler.compile(SOURCE, Map.of(), SHOP);
        assertTrue(recovered.reparsedUnits().isEmpty());
        assertTrue(recovered.rebuiltUnits().isEmpty());
        assertSame(first.units().get(ID), recovered.units().get(ID));
    }

    @Test void strictAndLegacyLayoutsAreIndependent() {
        var compiler = new IncrementalCompiler();
        var legacy = compiler.compile(SOURCE, Map.of(), SHOP);
        var strict = compiler.compileStrictAtto(SOURCE);
        assertNull(strict.units().get(ID).namespace());
        assertEquals("file:" + ID, strict.units().get(ID).packageId());
        var strictPackaged = compiler.compileStrictAtto(SOURCE, Map.of(), OTHER);
        assertEquals("pkg:other", strictPackaged.units().get(ID).packageId());
        assertEquals(Set.of(ID), strictPackaged.rebuiltUnits());
        assertTrue(compiler.compileStrictAtto(SOURCE, Map.of(), OTHER).rebuiltUnits().isEmpty());
        assertSame(legacy.units().get(ID), compiler.compile(SOURCE, Map.of(), SHOP).units().get(ID));
    }

    @Test void emptySnapshotAndClearResetOnlyIntendedCache() {
        var compiler = new IncrementalCompiler();
        compiler.compile(SOURCE, Map.of(), SHOP);
        compiler.compileStrictAtto(SOURCE, Map.of(), OTHER);
        compiler.compile(Map.of(), Map.of(), SHOP);
        assertTrue(compiler.compileStrictAtto(SOURCE, Map.of(), OTHER).rebuiltUnits().isEmpty());
        assertEquals(Set.of(ID), compiler.compile(SOURCE, Map.of(), SHOP).rebuiltUnits());
        compiler.clear();
        assertEquals(Set.of(ID), compiler.compileStrictAtto(SOURCE, Map.of(), OTHER).rebuiltUnits());
    }

    @Test void distinctRootsWithSameUnpinnedNameFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new PackageLayout(Map.of("vendor/v1", "shop", "vendor/v2", "shop")));
        var layout = new PackageLayout(Map.of("vendor/v1", "shop", "vendor/v2", "other"));
        assertNotEquals(layout.packageId("vendor/v1/a.atto"), layout.packageId("vendor/v2/a.atto"));
    }

    @Test void structuredNamespaceMetadataDoesNotTruncatePackageIdentity() {
        var compiler = new IncrementalCompiler();
        var result = compiler.compile(Map.of("src#one.atto",
                "pub fnc answer(): int { return 42; }"));
        assertEquals(Set.of("file:src#one.atto"), result.packageInterfaces().keySet());
        assertEquals(Set.of("file:src%23one.atto#"), result.namespaceInterfaces().keySet());
        assertEquals(result.namespaceInterfaces().keySet(), result.rebuiltNamespaces());
        assertEquals(1, result.structuredNamespaceInterfaces().size());
    }

    @Test void malformedUnicodeCannotAliasCachedSource() {
        var compiler = new IncrementalCompiler();
        var valid = compiler.compile(SOURCE, Map.of(), SHOP);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                Map.of(ID, SOURCE.get(ID) + "\ud800"), Map.of(), SHOP));
        var warm = compiler.compile(SOURCE, Map.of(), SHOP);
        assertSame(valid.units().get(ID), warm.units().get(ID));
        assertTrue(warm.rebuiltUnits().isEmpty());
    }

    @Test void namespaceDigestTracksAbiNotImplementation() {
        var compiler = new IncrementalCompiler();
        var original = compiler.compile(SOURCE, Map.of(), SHOP);
        var body = compiler.compile(Map.of(ID,
                "pub fnc answer(): int { return 43; }"), Map.of(), SHOP);
        assertEquals(original.namespaceInterfaces(), body.namespaceInterfaces());
        assertEquals(original.packageInterfaces(), body.packageInterfaces());
        var abi = compiler.compile(Map.of(ID,
                "pub fnc answer(int n): int { return n; }"), Map.of(), SHOP);
        assertNotEquals(body.namespaceInterfaces(), abi.namespaceInterfaces());
        assertNotEquals(body.packageInterfaces(), abi.packageInterfaces());
    }
}
