package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The parse cache is an optimization, never a way to skip revalidation. */
final class IncrementalParseCacheTest {
    private static Map<String, String> sources() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("math.ores", """
                pub fnc answer(): int { return 42; }
                """);
        result.put("app.ores", """
                import fnc {answer} from './math.ores';
                pub fnc main(): void { return; }
                """);
        return result;
    }

    @Test
    void unchangedBuildDoesNotReparseOrRebuild() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var first = compiler.compile(sources());
        assertEquals(Set.of("math.ores", "app.ores"), first.reparsedUnits());
        assertEquals(Set.of("math.ores", "app.ores"), first.rebuiltUnits());

        var second = compiler.compile(sources());
        assertTrue(second.reparsedUnits().isEmpty());
        assertTrue(second.rebuiltUnits().isEmpty());
        assertEquals(Set.of("math.ores", "app.ores"), second.reusedUnits());
    }

    @Test
    void changedImplementationReparsesOneFileAndPublicAbiRebuildsImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = sources();
        compiler.compile(initial);

        Map<String, String> implementation = new LinkedHashMap<>(initial);
        implementation.put("math.ores", "pub fnc answer(): int { return 43; }");
        var implBuild = compiler.compile(implementation);
        assertEquals(Set.of("math.ores"), implBuild.reparsedUnits());
        assertEquals(Set.of("math.ores"), implBuild.rebuiltUnits());
        assertTrue(implBuild.reused("app.ores"));

        Map<String, String> changedApi = new LinkedHashMap<>(implementation);
        changedApi.put("math.ores", "pub fnc answer(): String { return \"43\"; }");
        var apiBuild = compiler.compile(changedApi);
        assertEquals(Set.of("math.ores"), apiBuild.reparsedUnits());
        assertTrue(apiBuild.rebuilt("math.ores"));
        assertTrue(apiBuild.rebuilt("app.ores"));
    }

    @Test
    void failedParseMustNotPoisonSuccessfulCache() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = sources();
        compiler.compile(initial);
        Map<String, String> bad = new LinkedHashMap<>(initial);
        bad.put("math.ores", "pub fnc answer(: int {");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(bad));

        var recovered = compiler.compile(initial);
        assertTrue(recovered.reparsedUnits().isEmpty());
        assertTrue(recovered.rebuiltUnits().isEmpty());

        Map<String, String> fixed = new LinkedHashMap<>(initial);
        fixed.put("math.ores", "pub fnc answer(): int { return 44; }");
        var changed = compiler.compile(fixed);
        assertEquals(Set.of("math.ores"), changed.reparsedUnits());
    }

    @Test
    void clearingOrBuildingEmptySnapshotDiscardsCachedUnits() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        compiler.compile(sources());
        compiler.clear();
        assertEquals(Set.of("math.ores", "app.ores"), compiler.compile(sources()).reparsedUnits());

        var empty = compiler.compile(Map.of());
        assertTrue(empty.units().isEmpty());
        assertTrue(empty.reparsedUnits().isEmpty());

        var restored = compiler.compile(sources());
        assertEquals(Set.of("math.ores", "app.ores"), restored.reparsedUnits());
        assertEquals(Set.of("math.ores", "app.ores"), restored.rebuiltUnits());
    }

    @Test
    void callerCannotModifyReparseTelemetry() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var result = compiler.compile(sources());
        assertThrows(UnsupportedOperationException.class, () -> result.reparsedUnits().clear());
    }
}
