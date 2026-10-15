package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The executable select arm delimiter is the slim arrow. Legacy arrowless
 * braced arms remain parse-only compatibility until the formatter migrates
 * them; a record projection must never be mistaken for a block.
 */
final class SelectArmDelimiterCompatibilityTest {
    private static Ast.SelectStmt firstSelect(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        return (Ast.SelectStmt) fn.body().getFirst();
    }

    @Test
    void recordProjectionAndLegacyBodyRemainDistinct() {
        Ast.SelectStmt projected = firstSelect("""
                fnc test(Channel<{x: int, y: int}> ch): void {
                  do select {
                    when readch ch: {x, y} -> {
                      stdio.println(x);
                    }
                  }
                }
                """);
        assertTrue(projected.explicitDo());
        Ast.SelectArm arm = projected.arms().getFirst();
        assertEquals(Ast.DestructureKind.OBJECT, arm.destructureKind());
        assertEquals(2, arm.destructureBindings().size());

        Ast.SelectStmt legacy = firstSelect("""
                fnc test(Channel<int> ch): void {
                  do select {
                    when readch ch: {
                      stdio.println("legacy");
                    }
                  }
                }
                """);
        assertNull(legacy.arms().getFirst().destructureKind());
    }

    @Test
    void missingSlimArrowCannotTerminateTypedReadHeader() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> firstSelect("""
                    fnc test(Channel<[int, int]> ch): void {
                      do select {
                        when readch ch: const [int x, int y] {
                          stdio.println(x);
                        }
                      }
                    }
                    """));
        assertTrue(error.getMessage().contains("->"), error.getMessage());
    }

    @Test
    void fatArrowIsNeverAnExecutableSelectDelimiter() {
        String[] badArms = {
                "when readch ch: int n => { stdio.println(n); }",
                "when writech ch, 7: => {}",
                "default: => {}"
        };
        for (String badArm : badArms) {
            assertThrows(IllegalArgumentException.class, () -> firstSelect(
                    "fnc test(Channel<int> ch): void { do select { "
                            + badArm + " } }"), badArm);
        }
    }

    @Test
    void slimArrowWorksForWriteAndDefaultArms() {
        Ast.SelectStmt select = firstSelect("""
                fnc test(Channel<int> ch): void {
                  do select {
                    when writech ch, 7: -> {}
                    default: -> {}
                  }
                }
                """);
        assertEquals(Ast.ChannelOperation.WRITE, select.arms().getFirst().operation());
        assertEquals(Ast.ChannelOperation.DEFAULT, select.arms().get(1).operation());
    }
}
