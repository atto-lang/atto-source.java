package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Java/Graal guest regression targets for ~array/~tuple.
 * This class is NOT verification evidence until an exact-head runner executes it.
 */
final class TildeArrayTupleValueGuestTest {
    @Test
    void tildeValuesUseExactExistingListAndTupleAstNodes() {
        assertEquals(initializer("arr[1, 2, 3]"), initializer("~array(1, 2, 3)"));
        assertEquals(initializer("tuple(1, \"hi\")"), initializer("~tuple(1, \"hi\")"));
        assertInstanceOf(Ast.ListExpr.class, initializer("~array()"));
        assertInstanceOf(Ast.TupleExpr.class, initializer("~tuple()"));
        assertNotEquals(initializer("~array(1, 2)"), initializer("~tuple(1, 2)"));
    }

    @Test
    void compilerAndOwnershipCheckMixedOldAndNewLiterals() {
        String program = """
                pub routine main(): void {
                  val old_array: Array[int] = arr[1, 2];
                  val new_array: ~array<int> = ~array(3, 4);
                  val old_tuple: Tuple[int, string] = tuple(5, "five");
                  val new_tuple: ~tuple<int, string> = ~tuple(6, "six");
                  stdio.stdout.write(old_array[0] + new_array[0]
                      + old_tuple[0] + new_tuple[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void executableGraalGuestPreservesPositionalAndArraySemantics() throws Exception {
        String program = """
                pub routine main(): void {
                  val old_array: Array[int] = arr[1, 2];
                  val new_array: ~array<int> = ~array(3, 4);
                  val old_tuple: Tuple[int, string] = tuple(5, "five");
                  val new_tuple: ~tuple<int, string> = ~tuple(6, "six");
                  stdio.stdout.write(old_array[0] + new_array[0]
                      + old_tuple[0] + new_tuple[0]);
                  return;
                }
                """;
        assertEquals("15", run(program));
    }

    @Test
    void unsupportedAggregateConstructorsFailClosed() {
        for (String invalid : new String[]{
                "~map(\"a\" => 1)", "~set(1, 2)", "~vector(1, 2)",
                "~entry(\"key\" => 1)", "~pair(1, 2)", "~record(name: \"Ada\")",
                "~array<int>(1, 2)", "~tuple<int, string>(1, \"hi\")"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> initializer(invalid), invalid);
            assertTrue(failure.getMessage().contains("requires"), failure::getMessage);
        }
    }

    @Test
    void preexistingUnaryTildeIsStillAnIndependentExpression() {
        Ast.UnaryExpr unary = assertInstanceOf(Ast.UnaryExpr.class, initializer("~1"));
        assertEquals("~", unary.operator());
        assertInstanceOf(Ast.LiteralExpr.class, unary.operand());
    }

    private static Ast.Expr initializer(String expression) {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                  val value = %s;
                  return;
                }
                """.formatted(expression));
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) continue;
                for (Ast.Stmt stmt : fn.body()) {
                    if (stmt instanceof Ast.BindingStmt binding
                            && binding.name().equals("value")) {
                        return binding.initializer();
                    }
                }
            }
        }
        throw new AssertionError("missing value initializer");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tilde-array-tuple-values.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
