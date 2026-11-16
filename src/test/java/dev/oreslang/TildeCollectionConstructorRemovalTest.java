package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parse-only migration gate. Does not certify factory guest execution. */
final class TildeCollectionConstructorRemovalTest {
    private static final String[] REMOVED = {
            "~map<string,int>(\"a\" => 1)",
            "~entry(\"a\", 1)",
            "~pair(1, true)",
            "~tuple(1, true)",
            "~array(1, 2)",
            "~list(1, 2)",
            "~vector(1, 2)",
            "~set(1, 2)",
            "~record(ready: true)",
            "~slice(1, 2)",
            "~fixed_array(1, 2)",
            "~fixed_list(1, 2)"
    };

    @Test
    void oldCollectionConstructorsAreSyntaxErrors() {
        for (String expression : REMOVED) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): void { const value = "
                            + expression + "; return; }"),
                    expression);
            assertTrue(ex.getMessage().contains("structural typing"), ex::getMessage);
            assertTrue(ex.getMessage().contains(".of("), ex::getMessage);
        }
    }

    @Test
    void oldTypedEmptyConstructorIsRemoved() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): void { const value = ~<Map<string,int>>(); return; }"));
        assertTrue(ex.getMessage().contains("collection constructors are removed"),
                ex::getMessage);
    }

    @Test
    void tildeOnParameterIsStructuralModifierNotNominalCollectionAlias() {
        Ast.Program parsed = Parser.parse("""
                pub fnc accept(~Point point): void { return; }
                """);
        Ast.FunctionDecl fn = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl)
                .map(d -> (Ast.FunctionDecl) d)
                .findFirst().orElseThrow();
        assertEquals(1, fn.parameters().size());
        assertTrue(fn.parameters().get(0).structural());
        assertEquals("Point", fn.parameters().get(0).type().name());
    }

    @Test
    void ordinaryCanonicalFactoryCallsKeepNormalCallParsing() {
        for (String expression : new String[]{
                "Map.of()",
                "Entry.of(\"a\", 1)", "Pair.of(1, true)",
                "Tuple.of(1, true)", "Array.of(1, 2)", "List.of(1, 2)",
                "Vector.of(1, 2)", "Set.of(1, 2)",
                "Record.of()"}) {
            // Parser acceptance is not a promise that any particular factory
            // has been bound, typechecked or executed by the guest runtime.
            assertDoesNotThrow(() -> Parser.parse("pub fnc main(): void { const value = "
                    + expression + "; return; }"), expression);
        }
    }
}
