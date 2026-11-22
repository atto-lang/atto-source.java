package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests actual Java front-end parse/type/borrow cache behavior, not a
 * standalone planning model and not a codegen/Native Image guest proof.
 */
final class IncrementalFileFallbackV1Test {
    private static final String ONE = "pub fnc one(): int { return 1; }";
    private static final String TWO = "pub fnc two(): int { return 2; }";

    private static Map<String, String> isolated() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/one.atto", ONE);
        files.put("src/two.atto", TWO);
        files.put("src/namespaced.atto", """
                namespace tools;
                pub fnc tool(): int { return 7; }
                """);
        return files;
    }

    @Test
    void twoFilesWithNoNamespaceReparseAndRecheckIndependently() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = isolated();
        var cold = compiler.compile(sources);
        assertEquals(Set.copyOf(sources.keySet()), cold.reparsedUnits());
        assertEquals(Set.copyOf(sources.keySet()), cold.rebuiltUnits());
        assertNull(cold.units().get("src/one.atto").namespace());
        assertNull(cold.units().get("src/two.atto").namespace());
        assertNotEquals(cold.units().get("src/one.atto").packageId(),
                cold.units().get("src/two.atto").packageId(),
                "unnamespaced files cannot merge into an ambient global package identity");

        var noop = compiler.compile(sources);
        assertTrue(noop.reparsedUnits().isEmpty());
        assertTrue(noop.rebuiltUnits().isEmpty());
        assertEquals(Set.copyOf(sources.keySet()), noop.reusedUnits());

        Map<String, String> modified = new LinkedHashMap<>(sources);
        modified.put("src/one.atto", ONE.replace("return 1", "return 9"));
        var edited = compiler.compile(modified);
        assertEquals(Set.of("src/one.atto"), edited.reparsedUnits());
        assertEquals(Set.of("src/one.atto"), edited.rebuiltUnits());
        assertTrue(edited.reused("src/two.atto"));
        assertTrue(edited.reused("src/namespaced.atto"));
        assertSame(noop.units().get("src/two.atto"), edited.units().get("src/two.atto"),
                "an unrelated no-namespace file must retain its verified checked unit");
    }

    @Test
    void publicApiChangesRecheckImportersButNotUnrelatedFiles() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.atto", "pub fnc answer(): int { return 42; }");
        sources.put("client.atto", """
                import fnc {answer} from './lib.atto';
                pub fnc main(): void { return; }
                """);
        sources.put("unrelated.atto", TWO);
        compiler.compile(sources);

        Map<String, String> bodyEdit = new LinkedHashMap<>(sources);
        bodyEdit.put("lib.atto", "pub fnc answer(): int { return 43; }");
        var body = compiler.compile(bodyEdit);
        assertEquals(Set.of("lib.atto"), body.reparsedUnits());
        assertEquals(Set.of("lib.atto"), body.rebuiltUnits());
        assertTrue(body.reused("client.atto"));
        assertTrue(body.reused("unrelated.atto"));

        Map<String, String> changedApi = new LinkedHashMap<>(bodyEdit);
        changedApi.put("lib.atto", "pub fnc answer(): String { return \"hello\"; }");
        var result = compiler.compile(changedApi);
        assertEquals(Set.of("lib.atto"), result.reparsedUnits(),
                "dependency ABI changes may retypecheck but need not reparse unchanged caller syntax");
        assertTrue(result.rebuilt("lib.atto"));
        assertTrue(result.rebuilt("client.atto"));
        assertTrue(result.reused("unrelated.atto"));
    }

    @Test
    void sameDependencySetWithChangedImportBindingsMustInvalidate() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = Map.of(
                "a.atto", """
                        pub fnc left(): int { return 1; }
                        pub fnc right(): int { return 2; }
                        """,
                "b.atto", """
                        pub fnc left(): int { return 3; }
                        pub fnc right(): int { return 4; }
                        """,
                "consumer.atto", """
                        import fnc {left} from 'api/left';
                        import fnc {right} from 'api/right';
                        pub fnc main(): void { return; }
                        """,
                "caller.atto", """
                        import fnc {main} from './consumer.atto';
                        pub fnc launch(): void { return; }
                        """);
        Map<String, Map<String, String>> original =
                Map.of("consumer.atto", Map.of("api/left", "a.atto", "api/right", "b.atto"));
        Map<String, Map<String, String>> swapped =
                Map.of("consumer.atto", Map.of("api/left", "b.atto", "api/right", "a.atto"));
        compiler.compile(files, original);
        var changed = compiler.compile(files, swapped);
        assertTrue(changed.reparsedUnits().isEmpty());
        assertEquals(Set.of("a.atto", "b.atto"),
                changed.units().get("consumer.atto").dependencies());
        assertTrue(changed.rebuilt("consumer.atto"));
        assertTrue(changed.rebuilt("caller.atto"),
                "import target swaps may change re-export semantics transitively");
        assertTrue(changed.reused("a.atto"));
        assertTrue(changed.reused("b.atto"));
        assertTrue(compiler.compile(files, swapped).rebuiltUnits().isEmpty());
    }

    @Test
    void failedParseAndImportValidationCannotPoisonWarmCaches() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = isolated();
        compiler.compile(files);

        Map<String, String> malformed = new LinkedHashMap<>(files);
        malformed.put("src/one.atto", "pub fnc one(: int {");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(malformed));

        Map<String, String> missing = new LinkedHashMap<>(files);
        missing.put("src/one.atto", """
                import fnc {missing} from './absent.atto';
                pub fnc one(): int { return 1; }
                """);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(missing));

        var recovered = compiler.compile(files);
        assertTrue(recovered.reparsedUnits().isEmpty());
        assertTrue(recovered.rebuiltUnits().isEmpty());
        assertEquals(Set.copyOf(files.keySet()), recovered.reusedUnits());
    }

    @Test
    void emptySnapshotEvictsCheckedAndParsedSources() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = isolated();
        compiler.compile(files);
        var empty = compiler.compile(Map.of());
        assertTrue(empty.units().isEmpty());
        assertTrue(empty.reparsedUnits().isEmpty());

        var coldAgain = compiler.compile(files);
        assertEquals(Set.copyOf(files.keySet()), coldAgain.reparsedUnits());
        assertEquals(Set.copyOf(files.keySet()), coldAgain.rebuiltUnits());
    }

    @Test
    void strictAndLegacyProfilesNeverShareTypedOrParsedEntries() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = Map.of("strict.atto", ONE);
        assertEquals(Set.of("strict.atto"), compiler.compile(files).reparsedUnits());
        assertEquals(Set.of("strict.atto"), compiler.compileStrictAtto(files).reparsedUnits());
        assertTrue(compiler.compile(files).reparsedUnits().isEmpty());
        assertTrue(compiler.compileStrictAtto(files).reparsedUnits().isEmpty());
    }

    @Test
    void changedImportedSymbolWithSameProviderInvalidatesDependents() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("provider.atto", """
                pub fnc left(): int { return 1; }
                pub fnc right(): int { return 2; }
                """);
        sources.put("consumer.atto", """
                import fnc {left} from './provider.atto';
                pub fnc main(): void { return; }
                """);
        sources.put("downstream.atto", """
                import fnc {main} from './consumer.atto';
                pub fnc start(): void { return; }
                """);
        sources.put("unrelated.atto", TWO);
        compiler.compile(sources);

        Map<String, String> retargeted = new LinkedHashMap<>(sources);
        retargeted.put("consumer.atto", """
                import fnc {right} from './provider.atto';
                pub fnc main(): void { return; }
                """);
        var rebuilt = compiler.compile(retargeted);
        assertEquals(Set.of("consumer.atto"), rebuilt.reparsedUnits());
        assertEquals(Set.of("provider.atto"),
                rebuilt.units().get("consumer.atto").dependencies(),
                "changing the imported name may leave the target set unchanged");
        assertTrue(rebuilt.rebuilt("consumer.atto"));
        assertTrue(rebuilt.rebuilt("downstream.atto"),
                "the imported declaration identity must invalidate the reverse graph");
        assertTrue(rebuilt.reused("provider.atto"));
        assertTrue(rebuilt.reused("unrelated.atto"));
    }

    @Test
    void publicParameterRenameInvalidatesNamedArgumentImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib.atto", """
                pub fnc identity(int before): int { return before; }
                """);
        files.put("caller.atto", """
                import fnc {identity} from './lib.atto';
                pub fnc main(): void { return; }
                """);
        files.put("other.atto", TWO);
        compiler.compile(files);

        Map<String, String> renamed = new LinkedHashMap<>(files);
        renamed.put("lib.atto", """
                pub fnc identity(int after): int { return after; }
                """);
        var result = compiler.compile(renamed);
        assertEquals(Set.of("lib.atto"), result.reparsedUnits());
        assertTrue(result.rebuilt("lib.atto"));
        assertTrue(result.rebuilt("caller.atto"),
                "public parameter names participate in named-argument call ABI");
        assertTrue(result.reused("other.atto"));
    }

    @Test
    void publicDeclarationReorderIsNotAnApiChange() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib.atto", """
                pub fnc first(): int { return 1; }
                pub fnc second(): int { return 2; }
                """);
        files.put("caller.atto", """
                import fnc {first} from './lib.atto';
                pub fnc main(): void { return; }
                """);
        compiler.compile(files);

        Map<String, String> reordered = new LinkedHashMap<>(files);
        reordered.put("lib.atto", """
                pub fnc second(): int { return 2; }
                pub fnc first(): int { return 1; }
                """);
        var result = compiler.compile(reordered);
        assertEquals(Set.of("lib.atto"), result.reparsedUnits());
        assertEquals(Set.of("lib.atto"), result.rebuiltUnits(),
                "reordering independent public declarations must preserve importer ABI");
        assertTrue(result.reused("caller.atto"));
    }

    @Test
    void addingOrRemovingNamespaceHeaderChangesOnlyOwningFile() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> files = Map.of(
                "src/one.atto", ONE, "src/two.atto", TWO);
        compiler.compile(files);

        var withHeader = compiler.compile(Map.of(
                "src/one.atto", "namespace users;\n" + ONE,
                "src/two.atto", TWO));
        assertEquals(Set.of("src/one.atto"), withHeader.reparsedUnits());
        assertEquals(Set.of("src/one.atto"), withHeader.rebuiltUnits());
        assertTrue(withHeader.reused("src/two.atto"));

        var restored = compiler.compile(files);
        assertEquals(Set.of("src/one.atto"), restored.rebuiltUnits());
        assertTrue(restored.reused("src/two.atto"));
    }
}
