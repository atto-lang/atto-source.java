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
        Ast.ListExpr oldArray = assertInstanceOf(Ast.ListExpr.class, initializer("arr[1, 2, 3]"));
        Ast.ListExpr tildeArray = assertInstanceOf(Ast.ListExpr.class, initializer("~array(1, 2, 3)"));
        Ast.ListExpr bare = assertInstanceOf(Ast.ListExpr.class, initializer("[1, 2, 3]"));
        assertEquals(oldArray.elements(), tildeArray.elements());
        assertEquals(oldArray.elements(), bare.elements());
        assertTrue(oldArray.explicitArrayConstructor());
        assertTrue(tildeArray.explicitArrayConstructor());
        assertFalse(bare.explicitArrayConstructor());
        assertEquals(initializer("tuple(1, \"hi\")"), initializer("~tuple(1, \"hi\")"));
        assertInstanceOf(Ast.ListExpr.class, initializer("~array()"));
        assertInstanceOf(Ast.TupleExpr.class, initializer("~tuple()"));
        assertNotEquals(initializer("~array(1, 2)"), initializer("~tuple(1, 2)"));
    }

    @Test
    void zeroAndSingletonTupleFormsRemainDistinctFromGroupingAndArray() {
        Ast.TupleExpr emptyTuple = assertInstanceOf(Ast.TupleExpr.class, initializer("~tuple()"));
        assertEquals(0, emptyTuple.elements().size());
        Ast.TupleExpr singleton = assertInstanceOf(Ast.TupleExpr.class, initializer("~tuple(42)"));
        assertEquals(1, singleton.elements().size());
        assertInstanceOf(Ast.LiteralExpr.class, initializer("(42)"));
        assertInstanceOf(Ast.ListExpr.class, initializer("~array(42)"));
        assertNotEquals(initializer("~tuple(42)"), initializer("~array(42)"));
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
    void postfixIndexingOfTildeLiteralsUsesTheOldValuePath() throws Exception {
        String program = """
                pub routine main(): void {
                  stdio.stdout.write(~array(10, 21)[1]);
                  stdio.stdout.write("|");
                  stdio.stdout.write(~tuple(11, "eleven")[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("21|11", run(program));
    }

    @Test
    void tildeConstructorsEvaluateArgumentsOnceInSourceOrder() throws Exception {
        String program = """
                routine emit(int value): int {
                  stdio.stdout.write(value);
                  return value;
                }
                pub routine main(): void {
                  val array: ~array<int> = ~array(emit(1), emit(2), emit(3));
                  val tuple: ~tuple<int, int> = ~tuple(emit(4), emit(5));
                  stdio.stdout.write("|");
                  stdio.stdout.write(array[2]);
                  stdio.stdout.write(tuple[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("12345|34", run(program));
    }

    @Test
    void tildeTupleCannotBeImplicitlyPassedAsAnArray() {
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc takes_array(Array[int] values): void { return; }
                        pub routine main(): void {
                          val tuple: ~tuple<int, int> = ~tuple(1, 2);
                          takes_array(tuple);
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("argument"), mismatch::getMessage);
    }

    @Test
    void unsupportedAggregateConstructorsFailClosed() {
        for (String invalid : new String[]{
                "~map(\"a\" => 1)", "~set(1, 2)", "~vector(1, 2)",
                "~entry(\"key\" => 1)", "~pair(1, 2)", // ~record(...) is canonical on main (parsed inline)
                "~array<int>(1, 2)", "~tuple<int, string>(1, \"hi\")"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> initializer(invalid), invalid);
            assertTrue(failure.getMessage().contains("requires"), failure::getMessage);
        }
    }

    @Test
    void sigilRequiresAdjacencyAndDoesNotStealExistingUnaryCalls() {
        Ast.UnaryExpr spacedArray = assertInstanceOf(
                Ast.UnaryExpr.class, initializer("~ array(1, 2)"));
        assertEquals("~", spacedArray.operator());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, spacedArray.operand());
        assertEquals("array", assertInstanceOf(Ast.NameExpr.class, call.callee()).name());

        Ast.UnaryExpr spacedTuple = assertInstanceOf(
                Ast.UnaryExpr.class, initializer("~ tuple(1, 2)"));
        assertEquals("~", spacedTuple.operator());
        // A spaced sigil stays unary ~; main represents a bare tuple(...) as TupleExpr.
        assertInstanceOf(Ast.TupleExpr.class, spacedTuple.operand());

        // Whitespace before '<' must remain comparison syntax.
        Ast.BinaryExpr comparison = assertInstanceOf(
                Ast.BinaryExpr.class, initializer("~array < 4"));
        assertEquals("<", comparison.operator());
        assertInstanceOf(Ast.UnaryExpr.class, comparison.left());

        assertInstanceOf(Ast.ListExpr.class, initializer("~array (1, 2)"));
        assertInstanceOf(Ast.TupleExpr.class, initializer("~tuple (1, 2)"));
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
