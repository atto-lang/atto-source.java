package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mainline-only compatibility gate for canonical slim-arrow select syntax.
 * Typed/destructured read binding support remains in the separate draft PR.
 */
final class MainlineSelectArrowBoundaryTest {
    private static Ast.SelectStmt firstSelect(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        return (Ast.SelectStmt) fn.body().getFirst();
    }

    @Test
    void canonicalReadWriteAndDefaultArrowsParse() {
        Ast.SelectStmt select = firstSelect("""
                fnc route(Channel<int> input, Channel<int> output): void {
                  do select {
                    when readch input: const value -> {
                      stdio.println(value);
                    }
                    when writech output, 7: -> {}
                    default: -> {}
                  }
                }
                """);
        assertTrue(select.explicitDo());
        assertEquals(3, select.arms().size());
        assertEquals(Ast.ChannelOperation.READ, select.arms().get(0).operation());
        assertEquals("value", select.arms().get(0).bindingName());
        assertEquals(Ast.ChannelOperation.WRITE, select.arms().get(1).operation());
        assertEquals(Ast.ChannelOperation.DEFAULT, select.arms().get(2).operation());
    }

    @Test
    void legacyArrowlessSelectArmsRemainParseable() {
        Ast.SelectStmt select = firstSelect("""
                fnc route(Channel<int> input): void {
                  do select {
                    case readch input: val value {
                      stdio.println(value);
                    }
                    default: {}
                  }
                }
                """);
        assertEquals(2, select.arms().size());
    }

    @Test
    void fatArrowIsNeverAnExecutableArmBoundary() {
        for (String bad : new String[] {
                "when readch channel: val value => {}",
                "when writech channel, 1: => {}",
                "default: => {}"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> firstSelect(
                            "fnc route(Channel<int> channel): void { do select { "
                                    + bad + " } }"));
            assertTrue(failure.getMessage().contains("->"), failure.getMessage());
        }
    }

    @Test
    void malformedSlimArrowBodyIsRejected() {
        for (String bad : new String[] {
                "when readch channel: val value -> return;",
                "when writech channel, 1: -> return;",
                "default: -> return;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> firstSelect(
                    "fnc route(Channel<int> channel): void { do select { "
                            + bad + " } }"), bad);
        }
    }
}
