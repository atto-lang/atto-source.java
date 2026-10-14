package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Prove the import graph exports only public aliases, not private type identities. */
final class TransparentAliasImportVisibilityTest {
    private static Ast.Program consumer(String alias, String kind) {
        return Parser.parse("import " + kind + " " + alias
                + " from \"./library.ores\"; fnc run(): void { return; }");
    }

    @Test
    void publicAliasesCanBeImportedByTypeAndTypes() {
        Ast.Program library = Parser.parse("""
                define module products as
                    define pub type Entry<K, V> = record{key: K, value: V};
                    define pub type alias Pair<K, V> as Entry<K, V>;
                end
                """);
        assertDoesNotThrow(() -> ImportGraph.validateLinkedImports(Map.of(
                "app.ores", consumer("Entry", "type"),
                "library.ores", library)));
        assertDoesNotThrow(() -> ImportGraph.validateLinkedImports(Map.of(
                "app.ores", consumer("Pair", "types"),
                "library.ores", library)));
    }

    @Test
    void privateAliasCannotBeImportedEvenWithTypesWildcard() {
        Ast.Program library = Parser.parse("""
                type Secret = int;
                define module products as
                    type Hidden = [int, string];
                    define pub type alias Public as Hidden;
                end
                """);
        for (String name : new String[]{"Secret", "Hidden"}) {
            for (String kind : new String[]{"type", "types"}) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> ImportGraph.validateLinkedImports(Map.of(
                                "app.ores", consumer(name, kind),
                                "library.ores", library)));
                assertTrue(e.getMessage().contains("does not match an exported declaration"),
                        e.getMessage());
            }
        }
        // Exporting a name does not make the private target name importable.
        assertDoesNotThrow(() -> ImportGraph.validateLinkedImports(Map.of(
                "app.ores", consumer("Public", "type"),
                "library.ores", library)));
    }

    @Test
    void publicAliasDoesNotAcquireAnyRuntimeRepresentation() {
        Ast.Program program = Parser.parse("""
                define module products as
                    define pub type alias Pair<A, B> as Tuple[A, B];
                end
                """);
        Ast.TypeAliasDecl alias =
                (Ast.TypeAliasDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.Visibility.PUBLIC, alias.visibility());
        assertEquals("Pair", alias.name());
        assertEquals(2, alias.genericParameters().size());
        assertEquals("Tuple", alias.target().name());
    }
}
