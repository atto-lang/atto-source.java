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
 * Migration regression: removed ~array/~tuple constructors must never silently
 * reappear. Existing array/tuple literals retain their original guest path.
 * Type.of guest lowering belongs to the separately stacked Java PR #2735.
 * Authored tests are NOT execution evidence until a runner executes them.
 */
final class TildeArrayTupleValueGuestTest {
    @Test
    void legacyLiteralAstRemainsIndependentFromRemovedFactories() {
        Ast.ListExpr explicit = assertInstanceOf(Ast.ListExpr.class, initializer("arr[1, 2, 3]"));
        Ast.ListExpr bare = assertInstanceOf(Ast.ListExpr.class, initializer("[1, 2, 3]"));
        assertEquals(explicit.elements(), bare.elements());
        assertTrue(explicit.explicitArrayConstructor());
        assertFalse(bare.explicitArrayConstructor());

        assertInstanceOf(Ast.TupleExpr.class, initializer("(1, \"hi\")"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("(42)"));
        // Parser admission is not proof that unmerged Type.of guest lowering works.
        assertDoesNotThrow(() -> initializer("Array.of(1, 2, 3)"));
        assertDoesNotThrow(() -> initializer("Tuple.of(1, \"hi\")"));
    }

    @Test
    void allRetiredArrayTupleConstructorsRejectAtParserBoundary() {
        for (String expression : new String[]{
                "~array(1, 2, 3)", "~array()", "~array(42)",
                "~tuple()", "~tuple(42)", "~tuple(1, \"hi\")",
                "~array<int>(1, 2)", "~tuple<int, string>(1, \"hi\")",
                "~ array(1, 2)", "~ tuple(1, 2)",
                "~array (1, 2)", "~tuple (1, 2)"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> initializer(expression), expression);
        }
    }

    @Test
    void compilerAndOwnershipKeepOrdinaryArrayAndTupleLiteralTypes() {
        String program = """
                pub routine main(): void {
                  val first_array: Array[int] = arr[1, 2];
                  val second_array: Array[int] = arr[3, 4];
                  val first_tuple: Tuple[int, string] = (5, "five");
                  val second_tuple: Tuple[int, string] = (6, "six");
                  stdio.stdout.write(first_array[0] + second_array[0]
                      + first_tuple[0] + second_tuple[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void executableGraalGuestPreservesUntouchedLiteralPaths() throws Exception {
        String program = """
                pub routine main(): void {
                  val first_array: Array[int] = arr[1, 2];
                  val second_array: Array[int] = arr[3, 4];
                  val first_tuple: Tuple[int, string] = (5, "five");
                  val second_tuple: Tuple[int, string] = (6, "six");
                  stdio.stdout.write(first_array[0] + second_array[0]
                      + first_tuple[0] + second_tuple[0]);
                  return;
                }
                """;
        assertEquals("15", run(program));
    }

    @Test
    void postfixIndexingOfExistingLiteralsStillWorks() throws Exception {
        String program = """
                pub routine main(): void {
                  stdio.stdout.write(arr[10, 21][1]);
                  stdio.stdout.write("|");
                  stdio.stdout.write((11, "eleven")[0]);
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("21|11", run(program));
    }

    @Test
    void ordinaryCollectionLiteralsEvaluateOperandsOnceInOrder() throws Exception {
        String program = """
                routine emit(int value): int {
                  stdio.stdout.write(value);
                  return value;
                }
                pub routine main(): void {
                  val array: Array[int] = arr[emit(1), emit(2), emit(3)];
                  val tuple: Tuple[int, int] = (emit(4), emit(5));
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
    void tupleCannotBePassedAsArrayEvenWithoutTildeAlias() {
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc takes_array(Array[int] values): void { return; }
                        pub routine main(): void {
                          val tuple: Tuple[int, int] = (1, 2);
                          takes_array(tuple);
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("argument"), mismatch::getMessage);
    }

    @Test
    void allOtherNamedAggregateFactoriesAlsoFailClosed() {
        for (String invalid : new String[]{
                "~map(\"a\" => 1)", "~set(1, 2)", "~vector(1, 2)",
                "~entry(\"key\" => 1)", "~pair(1, 2)",
                "~list(1, 2)", "~record(foo: 42)"}) {
            assertThrows(IllegalArgumentException.class, () -> initializer(invalid), invalid);
        }
    }

    @Test
    void independentUnaryTildeStillParses() {
        Ast.UnaryExpr unary = assertInstanceOf(Ast.UnaryExpr.class, initializer("~1"));
        assertEquals("~", unary.operator());
        assertInstanceOf(Ast.LiteralExpr.class, unary.operand());
        Ast.UnaryExpr parenthesized = assertInstanceOf(Ast.UnaryExpr.class,
                initializer("~(42)"));
        assertEquals("~", parenthesized.operator());
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
        Source source = Source.newBuilder(OresLanguage.ID, program, "tilde-array-tuple-migration.ores")
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
