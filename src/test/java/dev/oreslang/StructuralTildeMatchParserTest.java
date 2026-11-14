package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralTildeMatchParserTest {
    private static Ast.StructuralPattern parseStructuralArm(String pattern) {
        Ast.Program program = Parser.parse("""
                fnc identify(): string {
                  match account over
                    on %s -> { return "matched"; }
                    on _ -> { return "unknown"; }
                  end
                }
                """.formatted(pattern));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.MatchStmt match = assertInstanceOf(Ast.MatchStmt.class, fn.body().getFirst());
        return assertInstanceOf(Ast.StructuralPattern.class, match.arms().getFirst().pattern());
    }

    @Test
    void tildeShapeDesugarsToTheSameStructuralPatternAsLongForm() {
        Ast.StructuralPattern shortForm =
                parseStructuralArm("~ {id: string, active: bool} as a");
        Ast.StructuralPattern longForm =
                parseStructuralArm("structural {id: string, active: bool} as a");

        assertEquals("a", shortForm.binding());
        assertEquals(longForm.target(), shortForm.target());
        assertEquals(longForm.binding(), shortForm.binding());
    }

    @Test
    void longFormAlsoSupportsExplicitAsForNamedShapes() {
        Ast.StructuralPattern pattern = parseStructuralArm("structural HasName as named");
        assertEquals("named", pattern.binding());
        assertEquals("HasName", pattern.target().name());
    }

    @Test
    void shapeBindingMustUseAsInNewTildeForm() {
        assertThrows(IllegalArgumentException.class,
                () -> parseStructuralArm("~ {id: string} a"));
        assertThrows(IllegalArgumentException.class,
                () -> parseStructuralArm("~ {id: string} as"));
    }

    @Test
    void existingTildeRecordPatternIsNotReinterpretedAsStructural() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                fnc test(): void {
                  match account over
                    on ~(id: item) -> { return; }
                    on _ -> { return; }
                  end
                }
                """));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.MatchStmt match = assertInstanceOf(Ast.MatchStmt.class, fn.body().getFirst());
        assertInstanceOf(Ast.RecordPattern.class, match.arms().getFirst().pattern());
    }
}
