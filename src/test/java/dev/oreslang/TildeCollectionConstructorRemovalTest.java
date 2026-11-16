package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
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
            "~fixed_list(1, 2)",
            "~ tuple(1, true)",
            "~ map(\"a\" => 1)"
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
    void bareTildeAggregateConstructorsAreRejectedButUnaryTildeRemainsSeparate() {
        for (String expression : new String[]{
                "~(ready: true)", "~(\"a\" => 1)", "~(1 => 2)", "~(...items)"
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): void { const value = "
                            + expression + "; return; }"), expression);
            assertTrue(ex.getMessage().contains("removed"), ex::getMessage);
        }
        // Unary '~' is a distinct existing operator: this parser-level test
        // is not authorization for a collection constructor or runtime cast.
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): void { const number = ~(1); return; }"));
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
    void isTildePointPreservesStructuralTypeTestAndReadonlyValBinding() {
        Ast.Program parsed = Parser.parse("""
                define trait Point as
                    x: int;
                end
                fnc classify(Point value): int {
                    match value over
                        on is ~Point as val p -> { return p.x; }
                        on _ -> { return 0; }
                    end
                }
                """);
        Ast.FunctionDecl fn = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl f && f.name().equals("classify"))
                .map(d -> (Ast.FunctionDecl) d)
                .findFirst().orElseThrow();
        Ast.MatchStmt match = fn.body().stream()
                .filter(stmt -> stmt instanceof Ast.MatchStmt)
                .map(stmt -> (Ast.MatchStmt) stmt)
                .findFirst().orElseThrow();
        Ast.StructuralPattern shape = assertInstanceOf(Ast.StructuralPattern.class,
                match.arms().getFirst().pattern());
        assertEquals("Point", shape.target().name());
        assertEquals("p", shape.binding());
        assertInstanceOf(Ast.WildcardPattern.class, match.arms().get(1).pattern());
    }

    @Test
    void structuralTestDoesNotSilentlyAcceptLetOrConstBindings() {
        for (String qualifier : new String[]{"let", "const"}) {
            String source = """
                    define trait Point as x: int; end
                    fnc read(Point value): int {
                        match value over
                            on is ~Point as %s p -> { return 1; }
                            on _ -> { return 0; }
                        end
                    }
                    """.formatted(qualifier);
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), qualifier);
        }
    }

    @Test
    void isTildeTypeCheckPreservesReadonlyStructuralView() {
        String positive = """
                define trait Point as
                    x: int;
                end
                fnc read(Point value): int {
                    match value over
                        on is ~Point as val p -> { return p.x; }
                        on _ -> { return 0; }
                    end
                }
                """;
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(positive)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        String mutableProjection = positive.replace(
                "return p.x;", "p.x = 2; return p.x;");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(mutableProjection)));
        assertTrue(rejected.getMessage().toLowerCase().contains("read")
                        || rejected.getMessage().toLowerCase().contains("mutab"),
                rejected::getMessage);
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
