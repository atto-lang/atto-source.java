package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SourceUnitDiagnosticException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Front-end and linker tests only; native AOT guest evidence is separate. */
final class StrictAttoLinkedGraphTest {
    private static Map<String, String> graph() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/helper.atto", """
                pub fnc answer(): int { return 7; }
                """);
        files.put("src/main.atto", """
                import fnc {answer} from "./helper.atto";
                pub fnc main(): bool { return 7 !eq 8; }
                """);
        return files;
    }

    @Test
    void linkedStrictImportIsCheckedAndInitializationGraphIsStable() {
        var compiler = new IncrementalCompiler();
        var first = assertDoesNotThrow(() -> compiler.compileStrictAtto(graph()));
        assertEquals(2, first.rebuiltUnits().size());
        assertTrue(first.units().get("src/main.atto").dependencies()
                .contains("src/helper.atto"));
        assertEquals(2, first.initializationOrder().size());
        var second = compiler.compileStrictAtto(graph());
        assertTrue(second.rebuiltUnits().isEmpty());
        assertEquals(2, second.reusedUnits().size());
        compiler.clear();
        assertEquals(2, compiler.compileStrictAtto(graph()).rebuiltUnits().size());
    }

    @Test
    void importedUnitMustAlsoPassStrictLexerEvenIfNeverExecuted() {
        var files = new LinkedHashMap<>(graph());
        files.put("src/helper.atto", """
                pub fnc answer(): bool { return 7 == 7; }
                """);
        SourceUnitDiagnosticException error = assertThrows(
                SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compileStrictAtto(files));
        assertEquals("src/helper.atto", error.unitId());
        assertTrue(error.detail().contains("ATTO_STRICT_INVALID_EQUALITY_TOKEN"),
                error::getMessage);
    }

    @Test
    void linkedPublicClassTypesRetainStrictNumericEqualityRules() {
        var files = Map.of(
                "src/types.atto", """
                        pub define class Options as
                          pub val int limit;
                        end
                        """,
                "src/use.atto", """
                        import class Options from "./types.atto";
                        pub fnc same(Options options): bool {
                          return options.limit eq options.limit;
                        }
                        """);
        assertEquals(2, new IncrementalCompiler().compileStrictAtto(files).units().size());

        var bad = Map.of(
                "src/types.atto", files.get("src/types.atto"),
                "src/use.atto", files.get("src/use.atto")
                        .replace("options.limit eq options.limit", "options.limit eq 7.0"));
        SourceUnitDiagnosticException error = assertThrows(
                SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compileStrictAtto(bad));
        assertEquals("src/use.atto", error.unitId());
        assertTrue(error.detail().contains("ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"),
                error::getMessage);
    }

    @Test
    void strictProfileRejectsUnresolvedExternalPackageEvenWhenLegacyAccepts() {
        var files = Map.of("src/app.atto", """
                import fnc {remote} from "missing.package";
                pub fnc main(): bool { return 5 eq 5; }
                """);
        SourceUnitDiagnosticException error = assertThrows(
                SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compileStrictAtto(files));
        assertTrue(error.detail().contains("ATTO_STRICT_IMPORT_GRAPH_UNVERIFIED"),
                error::getMessage);
    }

    @Test
    void explicitImportResolutionCannotHideInvalidDependency() {
        var files = Map.of(
                "src/use.atto", """
                        import fnc {answer} from "package.helper";
                        pub fnc main(): bool { return 7 neq 8; }
                        """,
                "src/helper.atto", """
                        pub fnc answer(): bool { return 7 != 8; }
                        """);
        var resolver = Map.of("src/use.atto",
                Map.of("package.helper", "src/helper.atto"));
        SourceUnitDiagnosticException error = assertThrows(
                SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compileStrictAtto(files, resolver));
        assertEquals("src/helper.atto", error.unitId());
        assertTrue(error.detail().contains("ATTO_STRICT_INVALID_EQUALITY_TOKEN"),
                error::getMessage);
    }

    @Test
    void twoSourceImportCycleStillHasSingleInitializationBarrier() {
        var files = Map.of(
                "src/a.atto", """
                        import fnc {b} from "./b.atto";
                        pub fnc a(): int { return 1; }
                        """,
                "src/b.atto", """
                        import fnc {a} from "./a.atto";
                        pub fnc b(): int { return 2; }
                        """);
        var result = new IncrementalCompiler().compileStrictAtto(files);
        assertEquals(1, result.initializationGroups().size());
        assertEquals(2, result.initializationGroups().getFirst().size());
    }

    @Test
    void strictCacheCanNeverReuseLegacyAcceptedSource() {
        var files = Map.of("src/main.atto",
                "pub fnc main(): bool { return 7 == 7; }");
        var compiler = new IncrementalCompiler();
        assertEquals(1, compiler.compile(files).rebuiltUnits().size());
        assertThrows(SourceUnitDiagnosticException.class,
                () -> compiler.compileStrictAtto(files));
        assertEquals(1, compiler.compile(files).reusedUnits().size());
    }

    @Test
    void strictGraphApiReportsEntireValidatedSourceSet() {
        var result = OresCompiler.parseAndTypeCheckStrictAttoGraph(graph(), Map.of());
        assertEquals(2, result.units().size());
        assertTrue(result.units().containsKey("src/helper.atto"));
    }
}
