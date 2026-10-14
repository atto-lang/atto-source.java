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
    void failedTypecheckAndMissingDependencyDoNotCommitCacheState() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = sources();
        compiler.compile(initial);

        Map<String, String> typeError = new LinkedHashMap<>(initial);
        typeError.put("math.ores",
                "pub fnc answer(): int { return \"invalid\"; }");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(typeError));
        assertThrows(IllegalArgumentException.class, () ->
                compiler.compile(Map.of("app.ores", initial.get("app.ores"))));

        var recovered = compiler.compile(initial);
        assertTrue(recovered.reparsedUnits().isEmpty(),
                "failed semantic/import checks must not mutate parsed cache");
        assertTrue(recovered.rebuiltUnits().isEmpty(),
                "failed semantic/import checks must not mutate checked cache");
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
    void reorderingPublicDeclarationsDoesNotInvalidateImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "math.ores", """
                        pub fnc alpha(): int { return 1; }
                        pub fnc beta(): int { return 2; }
                        """,
                "app.ores", """
                        import fnc {alpha} from './math.ores';
                        pub fnc main(): void { return; }
                        """);
        compiler.compile(initial);

        Map<String, String> reordered = Map.of(
                "math.ores", """
                        pub fnc beta(): int { return 2; }
                        pub fnc alpha(): int { return 1; }
                        """,
                "app.ores", initial.get("app.ores"));
        var after = compiler.compile(reordered);
        assertEquals(Set.of("math.ores"), after.reparsedUnits());
        assertTrue(after.rebuilt("math.ores"));
        assertTrue(after.reused("app.ores"),
                "changing top-level declaration order is not a public ABI change");
    }

    @Test
    void linkedClassRevalidationUsesTheCurrentRawAst() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "model.ores", """
                        define module exported
                          pub define class Payload as
                            @FromJson("external_key")
                            foo: String;
                          end
                        end
                        """,
                "consumer.ores", """
                        import class Payload from "./model.ores";
                        pub fnc consume(Payload payload): void { return; }
                        """);
        var first = compiler.compile(initial);
        assertEquals(Set.of("model.ores", "consumer.ores"), first.reparsedUnits());
        assertTrue(compiler.compile(initial).reparsedUnits().isEmpty());

        Map<String, String> changed = new LinkedHashMap<>(initial);
        changed.put("model.ores", initial.get("model.ores").replace(
                "external_key", "different_key"));
        var next = compiler.compile(changed);
        assertEquals(Set.of("model.ores"), next.reparsedUnits());
        assertTrue(next.rebuilt("consumer.ores"),
                "annotation-derived wire ABI must invalidate linked class importers");
    }

    @Test
    void callerCannotModifyReparseTelemetry() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var result = compiler.compile(sources());
        assertThrows(UnsupportedOperationException.class, () -> result.reparsedUnits().clear());
    }
}
