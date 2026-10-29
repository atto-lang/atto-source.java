package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceLinker;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class PackageNamespaceLinkerTest {
    @TempDir Path temp;

    private static Ast.Program member(String namespace, String name) {
        return Parser.parse("namespace " + namespace + ";\n"
                + "pub fnc " + name + "(): int { return 42; }\n");
    }

    private static Ast.Program importing(String path) {
        return Parser.parse("import ns a from '" + path + "';\n"
                + "pub fnc main(): int { return 43; }\n");
    }

    private static PackageNamespaceLinker.Result link(
            Map<String, Ast.Program> programs, Map<String, String> owners,
            Map<String, Map<String, String>> aliases) {
        return PackageNamespaceLinker.link(programs, owners, aliases);
    }

    @Test
    void parsesExactNamespaceImportWithoutReinterpretingModuleOrFileImports() {
        Ast.ImportDecl ns = importing("pkg:foo").imports().getFirst();
        assertEquals(Ast.ImportKind.NAMESPACE, ns.kind());
        assertEquals(List.of("a"), ns.names());
        assertEquals("pkg:foo", ns.path());
        assertFalse(ns.wildcard());
        assertNull(ns.namespace());
        assertDoesNotThrow(() -> ImportRules.validate(ns));

        Ast.ImportDecl module = Parser.parse("import module a from './file';\n"
                + "pub fnc main(): int { return 0; }\n").imports().getFirst();
        assertEquals(Ast.ImportKind.MODULE, module.kind());
        assertEquals(Ast.ImportKind.FUNCTION, Parser.parse("import fnc a from './file';\n"
                + "pub fnc main(): int { return 0; }\n").imports().getFirst().kind());
        assertNotNull(Parser.parse("pub fnc ns(int value): int { return value; }\n"));
    }

    @Test
    void tenFilesAndThreeNamespacesAreOwnedByExactlyOnePackage() {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        String[] groupA = {"compute/math/add", "compute/stats/double", "other/folder/aggregate"};
        String[] groupB = {"data/model", "data/queries", "data/storage", "data/validation"};
        String[] groupC = {"api/routes", "api/handlers", "api/responses"};
        for (String path : groupA) {
            String source = "foo/src/" + path + ".ores";
            units.put(source, member("a", path.substring(path.lastIndexOf('/') + 1)));
            owners.put(source, "foo@sha:1111");
        }
        for (String path : groupB) {
            String source = "foo/src/" + path + ".ores";
            units.put(source, member("b", path.substring(path.lastIndexOf('/') + 1)));
            owners.put(source, "foo@sha:1111");
        }
        for (String path : groupC) {
            String source = "foo/src/" + path + ".ores";
            units.put(source, member("c", path.substring(path.lastIndexOf('/') + 1)));
            owners.put(source, "foo@sha:1111");
        }
        units.put("app/src/main.ores", importing("pkg:foo"));
        owners.put("app/src/main.ores", "app@sha:2222");

        var result = link(units, owners, Map.of(
                "app@sha:2222", Map.of("foo", "foo@sha:1111")));
        assertEquals(3, result.namespaces().size());
        assertEquals(3, result.namespace("foo@sha:1111", "a").sourceUnits().size());
        assertEquals(4, result.namespace("foo@sha:1111", "b").sourceUnits().size());
        assertEquals(3, result.namespace("foo@sha:1111", "c").sourceUnits().size());

        var imported = result.resolved("app/src/main.ores", "a");
        assertNotNull(imported);
        assertEquals(new PackageNamespaceLinker.NamespaceId("foo@sha:1111", "a"),
                imported.target().id());
        assertEquals(Set.of("add", "double", "aggregate"),
                imported.target().exports().stream()
                        .map(PackageNamespaceLinker.Export::name).collect(Collectors.toSet()));
    }

    @Test
    void sameSpellingInOtherPackageOrVersionNeverMerges() {
        Map<String, Ast.Program> units = Map.of(
                "foo/v1/a.ores", member("a", "first"),
                "foo/v2/a.ores", member("a", "second"),
                "other/a.ores", member("a", "third"),
                "app/main.ores", importing("pkg:foo"));
        Map<String, String> owners = Map.of(
                "foo/v1/a.ores", "foo@v1",
                "foo/v2/a.ores", "foo@v2",
                "other/a.ores", "other@v1",
                "app/main.ores", "app@v1");
        var result = link(units, owners, Map.of("app@v1", Map.of("foo", "foo@v1")));
        assertEquals(3, result.namespaces().size());
        assertEquals(List.of("first"),
                result.resolved("app/main.ores", "a").target().exports().stream()
                        .map(PackageNamespaceLinker.Export::name).toList());
        assertEquals(List.of("second"),
                result.namespace("foo@v2", "a").exports().stream()
                        .map(PackageNamespaceLinker.Export::name).toList());
        assertEquals(List.of("third"),
                result.namespace("other@v1", "a").exports().stream()
                        .map(PackageNamespaceLinker.Export::name).toList());
    }

    @Test
    void publicExportCollisionAcrossFilesFailsWithinOnePackage() {
        Map<String, Ast.Program> units = Map.of(
                "one.ores", member("a", "same"),
                "two.ores", member("a", "same"));
        Map<String, String> owners = Map.of("one.ores", "foo@1", "two.ores", "foo@1");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> link(units, owners, Map.of()));
        assertTrue(failure.getMessage().contains("duplicate public namespace declaration"),
                failure.getMessage());
        // Equal names in DIFFERENT packages are NOT collisions.
        assertEquals(2, link(units,
                Map.of("one.ores", "foo@1", "two.ores", "foo@2"),
                Map.of()).namespaces().size());
    }

    @Test
    void privateDeclarationsDoNotBecomePublicNamespaceExports() {
        Ast.Program source = Parser.parse("""
                namespace a;
                fnc hidden(): int { return 1; }
                pub fnc visible(): int { return 2; }
                """);
        var result = link(Map.of("foo/src/a.ores", source),
                Map.of("foo/src/a.ores", "foo@1"), Map.of());
        assertEquals(List.of("visible"),
                result.namespace("foo@1", "a").exports().stream()
                        .map(PackageNamespaceLinker.Export::name).toList());
    }

    @Test
    void namedModulesNeverContributeToSourceHeaderNamespace() {
        Ast.Program source = Parser.parse("""
                namespace a;
                define module ModuleA as
                  pub fnc from_module(): int { return 1; }
                end
                pub fnc at_root(): int { return 2; }
                """);
        var result = link(Map.of("foo/a.ores", source),
                Map.of("foo/a.ores", "foo@1"), Map.of());
        assertEquals(List.of("at_root"),
                result.namespace("foo@1", "a").exports().stream()
                        .map(PackageNamespaceLinker.Export::name).toList());
    }

    @Test
    void undeclaredOrMissingTargetPackageCannotImportNamespace() {
        Map<String, Ast.Program> units = Map.of("foo/a.ores", member("a", "ok"),
                "app/main.ores", importing("pkg:foo"));
        Map<String, String> owners = Map.of("foo/a.ores", "foo@1",
                "app/main.ores", "app@1");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> link(units, owners, Map.of())).getMessage().contains("undeclared"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> link(units, owners, Map.of("app@1", Map.of("foo", "foo@missing"))))
                .getMessage().contains("verified package source inventory"));
    }

    @Test
    void namespaceMustBelongToSelectedPinnedInstance() {
        Map<String, Ast.Program> units = Map.of("other/a.ores", member("a", "ok"),
                "foo/b.ores", member("b", "not_a"),
                "app/main.ores", importing("pkg:foo"));
        Map<String, String> owners = Map.of("other/a.ores", "other@1",
                "foo/b.ores", "foo@1", "app/main.ores", "app@1");
        String message = assertThrows(IllegalArgumentException.class,
                () -> link(units, owners, Map.of("app@1", Map.of("foo", "foo@1")))).getMessage();
        assertTrue(message.contains("no cross-package fallback"), message);
    }

    @Test
    void sourceOwnershipMustBeCompleteAndExact() {
        Map<String, Ast.Program> units = Map.of("foo/a.ores", member("a", "ok"),
                "app/main.ores", importing("pkg:foo"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> link(units, Map.of("foo/a.ores", "foo@1"), Map.of()))
                .getMessage().contains("exact verified source ownership"));
    }

    @Test
    void duplicateNormalizedSourceIdentityFails() {
        Map<String, Ast.Program> units = Map.of(
                "foo/./x.ores", member("a", "first"),
                "foo/x.ores", member("a", "second"));
        Map<String, String> owners = Map.of(
                "foo/./x.ores", "foo@1", "foo/x.ores", "foo@1");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> link(units, owners, Map.of())).getMessage().contains("normalized"));
    }

    @Test
    void rejectsRelativeJavaOrUnpinnedNamespaceImportPaths() {
        for (String path : List.of("./foo", "foo", "pkg:../foo", "pkg:foo/bar",
                "pkg:foo@2", "java:java.lang.Runtime", "pkg:", "pkg:foo bar")) {
            var imported = importing(path).imports().getFirst();
            assertThrows(IllegalArgumentException.class, () -> ImportRules.validate(imported),
                    "unexpectedly accepted " + path);
        }
    }

    @Test
    void packageOwnerIdentityChangeInvalidatesCachedCheckedUnit() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = Map.of(
                "src/a.ores", "namespace a; pub fnc result(): int { return 42; }");
        var first = compiler.compilePinned(
                sources, Map.of(), Map.of("src/a.ores", "foo@v1"), Map.of());
        assertEquals("foo@v1", first.build().units().get("src/a.ores").packageId());
        var changedOwner = compiler.compilePinned(
                sources, Map.of(), Map.of("src/a.ores", "foo@v2"), Map.of());
        assertTrue(changedOwner.build().rebuilt("src/a.ores"),
                "source owner identity changes must recheck and rebuild");
        assertEquals("foo@v2", changedOwner.build().units().get("src/a.ores").packageId());
    }

    @Test
    void legacyIncrementalCompilerMustNeverFakeNamespaceExecution() {
        String code = "import ns a from 'pkg:foo';\n"
                + "pub fnc main(): int { return a.foo(); }\n";
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of("app/main.ores", code)));
        assertTrue(failure.getMessage().contains("runtime namespace lowering"), failure.getMessage());
    }

    @Test
    void runnerRejectsNamespaceImportBeforeUnsafeFilesystemPackageFallback() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, "import ns a from 'pkg:foo';\n"
                + "pub fnc main(): int { return a.foo(); }\n");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(failure.getMessage().contains("pinned package graph"), failure.getMessage());
    }
}
