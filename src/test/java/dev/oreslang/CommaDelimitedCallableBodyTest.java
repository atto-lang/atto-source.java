package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** A comma in an explicit arrow/:: body sequences items, never returns a tuple. */
final class CommaDelimitedCallableBodyTest {
    @Test
    void freeFunctionAndRoutineUseLastExpressionAsReturn() {
        Ast.Program program = Parser.parse("""
                pub fnc add(int foo) -> int ::
                    int x = 5, x + foo
                end

                pub routine multiply(int foo) -> int ::
                    int x = 3,
                    x * foo
                end
                """);
        Ast.ModuleDecl root = program.modules().getFirst();
        assertEquals(2, root.declarations().size());
        for (Ast.Decl declaration : root.declarations()) {
            Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class, declaration);
            assertEquals(2, fn.body().size());
            assertInstanceOf(Ast.BindingStmt.class, fn.body().getFirst());
            assertInstanceOf(Ast.ReturnStmt.class, fn.body().getLast());
        }
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void bareClassMethodMtdAndStaticClassFunctionShareExpressionBodyContract() {
        Ast.Program program = Parser.parse("""
                define class Calculator as
                    pub add(int n) -> int ::
                        int x = 5, x + n
                    end

                    pub mtd with_receiver(mut self, int n) -> int ::
                        int x = 6,
                        x + n
                    end

                    pub static fnc increment(int n) -> int ::
                        n + 1
                    end
                end
                """);
        Ast.ClassDecl calculator = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(3, calculator.methods().size());
        for (Ast.MethodDecl method : calculator.methods()) {
            assertInstanceOf(Ast.ReturnStmt.class, method.body().getLast(),
                    method.name());
        }
        assertFalse(calculator.methods().getFirst().isStatic());
        assertNotNull(calculator.methods().get(1).receiverType());
        assertTrue(calculator.methods().get(2).isStatic());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void actorReceiverMtdAndMultipleActorsRetainDistinctLexicalBodies() {
        Ast.Program program = Parser.parse("""
                define actor Foo1 as
                    pub mtd compute(int n) -> int ::
                        int x = 5, x + n
                    end
                end
                define actor Foo2 as
                    pub mtd compute(int n) -> int ::
                        int x = 7, x * n
                    end
                end
                """);
        assertEquals(2, program.modules().getFirst().declarations().size());
        for (Ast.Decl decl : program.modules().getFirst().declarations()) {
            Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class, decl);
            assertEquals(1, actor.methods().size());
            Ast.MethodDecl method = actor.methods().getFirst();
            assertEquals(2, method.body().size());
            assertInstanceOf(Ast.ReturnStmt.class, method.body().getLast());
        }
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void nestedCallArgumentCommasAreNotSequenceDelimiters() {
        Ast.Program program = Parser.parse("""
                fnc combine(int a, int b): int { return a + b; }
                fnc calculated(int n) -> int ::
                    int x = combine(n, 1),
                    int y = combine(x, 2),
                    y + 1
                end
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().get(1));
        assertEquals(3, fn.body().size());
        assertInstanceOf(Ast.ReturnStmt.class, fn.body().getLast());
    }

    @Test
    void bracedBodiesRetainExplicitReturnAndDoNotAdmitCommaSequence() {
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse("fnc foo(int n): int { int x = 5; x + n; }")
                        .modules().getFirst().declarations().getFirst());
        assertInstanceOf(Ast.ExprStmt.class, fn.body().getLast());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc foo(int n): int { int x = 5, x + n }"));
    }

    @Test
    void malformedEmptyOrTrailingCommaItemsFailClosed() {
        for (String source : new String[] {
                "fnc foo(int n) -> int :: , n end",
                "fnc foo(int n) -> int :: int x = 1,, x + n end",
                "fnc foo(int n) -> int :: int x = 1, end",
                "define class C as mtd f(int n) -> int :: n, end end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void finalExpressionStillPassesTypeAndReadonlyChecking() {
        for (String source : new String[] {
                "fnc bad(int n) -> int :: int x = true, x + n end",
                "fnc bad(int n) -> int :: int x = 5, x = 6, x + n end",
                "fnc bad(int n) -> bool :: int x = 5, x + n end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
        }
    }

    @Test
    void realGraalGuestExecutesFncRoutineClassMethodAndReceiverMtd() throws Exception {
        String code = """
                fnc plus(int a, int b): int { return a + b; }
                fnc add(int n) -> int ::
                    int x = plus(2, 3),
                    x + n
                end
                routine multiply(int n) -> int ::
                    int x = 3,
                    x * n
                end
                define class Calculator as
                    pub add(int n) -> int ::
                        int x = 6,
                        x + n
                    end
                    pub mtd add_receiver(mut self, int n) -> int ::
                        int x = 7,
                        x + n
                    end
                    pub static fnc add_static(int n) -> int ::
                        n + 8
                    end
                end
                pub fnc main(): void {
                    val c = new Calculator();
                    stdio.stdout.write(add(37));
                    stdio.stdout.write(":");
                    stdio.stdout.write(multiply(2));
                    stdio.stdout.write(":");
                    stdio.stdout.write(c.add(3));
                    stdio.stdout.write(":");
                    stdio.stdout.write(c.add_receiver(3));
                    stdio.stdout.write(":");
                    stdio.stdout.write(Calculator.add_static(3));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(code)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "comma-callables.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42:6:9:10:11", output.toString(StandardCharsets.UTF_8));
    }
}
