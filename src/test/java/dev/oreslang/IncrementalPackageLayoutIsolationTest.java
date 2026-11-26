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

    @Test void duplicateNormalizedImportersCannotSilentlyRebindOrPoisonCache() {
        var compiler = new IncrementalCompiler();
        var sources = Map.of("a.atto", "pub fnc value(): int { return 42; }",
                "b.atto", "pub fnc value(): int { return 43; }",
                "client.atto", "import fnc {value} from 'selected'; pub fnc main(): int { return value(); }");
        var pins = Map.of("client.atto", Map.of("selected", "a.atto"));
        var first = compiler.compile(sources, pins);
        var ambiguous = new java.util.LinkedHashMap<String, Map<String, String>>();
        ambiguous.put("client.atto", Map.of("selected", "a.atto"));
        ambiguous.put("./client.atto", Map.of("selected", "b.atto"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources, ambiguous));
        var recovered = compiler.compile(sources, pins);
        assertTrue(recovered.rebuiltUnits().isEmpty());
        assertSame(first.units().get("client.atto"), recovered.units().get("client.atto"));
    }

    @Test void invalidEmptySnapshotAndNullSourceRetainCommittedCache() {
        var compiler = new IncrementalCompiler();
        var first = compiler.compile(SOURCE);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of(),
                Map.of(ID, Map.of("unused", "other.atto"))));
        var malformed = new java.util.LinkedHashMap<String, String>();
        malformed.put(ID, null);
        malformed.put("./" + ID, SOURCE.get(ID));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(malformed));
        var recovered = compiler.compile(SOURCE);
        assertTrue(recovered.reparsedUnits().isEmpty());
        assertSame(first.units().get(ID), recovered.units().get(ID));
    }
}
