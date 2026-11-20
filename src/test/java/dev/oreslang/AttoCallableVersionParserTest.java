package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Production parser grammar-only gate; not yet an opt-in Truffle guest ABI. */
final class AttoCallableVersionParserTest {
    private static Ast.FunctionDecl first(String source, int version) {
        return assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parseVersionedAtto(source, version)
                        .modules().getFirst().declarations().getFirst());
    }

    @Test
    void actualParserBuildsCheckedReturnAstForBothVersions() {
        var v1 = first("fnc answer() -> int 1, 42 end", 1);
        var markedV1 = first("fnc answer() -> int :: 1, 42 end", 1);
        var markedV2 = first("fnc answer() -> int :: 1, 42 end", 2);
        assertEquals(2, v1.body().size());
        assertInstanceOf(Ast.ExprStmt.class, v1.body().getFirst());
        assertInstanceOf(Ast.ReturnStmt.class, v1.body().getLast());
        assertEquals(v1.body(), markedV1.body());
        assertEquals(markedV1.body(), markedV2.body());
        var v2Statement = first("fnc answer() -> int return 42; end", 2);
        var v2Caret = first("fnc answer() -> int ^ 42; end", 2);
        assertEquals(v2Statement.body(), v2Caret.body(),
                "Caret returns from the enclosing callable");
        assertEquals(v2Statement.body(), first("fnc answer() -> int { return 42; }", 2).body());
    }

    @Test
    void methodAndRoutineUseTheSameVersionedBodyContract() {
        var routine = Parser.parseVersionedAtto("routine answer() -> int :: 1, 42 end", 2);
        assertFalse(routine.modules().isEmpty());
        var cls = Parser.parseVersionedAtto(
                "define class C as pub answer() -> int :: 1, 42 end end", 2);
        assertFalse(cls.modules().isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseVersionedAtto(
                        "define class C as pub answer() -> int :: 1; 42 end end", 2));
    }

    @Test
    void rejectsBadVersionsAndAccidentalImplicitTailMigration() {
        var marked = "fnc answer() -> int :: 1, 42 end";
        for (int version : List.of(0, 3, -1, Integer.MAX_VALUE)) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseVersionedAtto(marked, version));
            assertTrue(error.getMessage().contains("E_LANGUAGE_VERSION"));
        }
        for (String source : List.of(
                "fnc answer() -> int 1, 42 end",
                "fnc answer() -> int :: 1; 42 end",
                "fnc answer() -> int :: 1, end",
                "fnc answer() -> int :: , 42 end")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseVersionedAtto(source, 2), source);
        }
        // The prior unversioned entry still accepts statement-style ::.
        var legacy = Parser.parse("fnc answer() -> int :: return 42; end");
        assertFalse(legacy.modules().isEmpty());
    }

    @Test
    void nestedArgumentCommasCannotSplitTopLevelBodyItems() {
        var program = first(
                "fnc answer() -> int :: math.max(1, 2), 42 end", 2);
        assertEquals(2, program.body().size());
        assertInstanceOf(Ast.ReturnStmt.class, program.body().getLast());
    }
}
