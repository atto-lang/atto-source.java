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
 * Return types on anonymous functions are callable contracts, not ARM_RESULT.
 * These checks do not imply typed switch/select support.
 */
final class ExplicitLambdaReturnContractTest {
    private static Ast.LambdaExpr firstLambda(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
        return assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
    }

    @Test
    void explicitAndInferredReturnTypesRemainDistinctInAst() {
        Ast.LambdaExpr typed = firstLambda("""
                fnc f(): void {
                    const fnc callback = || -> int { return 42; };
                }
                """);
        assertNotNull(typed.declaredReturnType());
        assertEquals("int", typed.declaredReturnType().name());
        assertNull(typed.expressionBody());
        assertEquals(1, typed.blockBody().size());

        Ast.LambdaExpr inferred = firstLambda("""
                fnc f(): void {
                    const fnc callback = || -> { return 42; };
                }
                """);
        assertNull(inferred.declaredReturnType());
        assertNotNull(inferred.blockBody());

        Ast.LambdaExpr expression = firstLambda("""
                fnc f(): void {
                    const fnc callback = |x: int| -> x + 1;
                }
                """);
        assertNull(expression.declaredReturnType());
        assertNotNull(expression.expressionBody());
    }

    @Test
    void explicitScalarAndVoidLambdaExecuteWithDeferCleanup() throws Exception {
        String guest = """
                fnc compute(): int {
                    const fnc add_one = |x: int| -> int { return x + 1; };
                    defer || -> void {
                        stdio.println("cleanup");
                    };
                    return add_one(41);
                }
                pub fnc main(): void {
                    stdio.println(compute());
                    return;
                }
                """;
        Ast.Program program = Parser.parse(guest);
        var typed = TypeChecker.check(program);
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "lambda-return-contract.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("cleanup\n42",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void nestedValueMatchResultReturnsToLambdaBeforeOuterCallable() throws Exception {
        String guest = """
                fnc pick(bool selected): int {
                    const fnc nested = || -> int {
                        val int value = match<int> selected over {
                            on true { return 41; }
                            on false { return 1; }
                        };
                        return value + 1;
                    };
                    return nested() + 100;
                }
                pub fnc main(): void {
                    stdio.println(pick(true));
                    stdio.println(pick(false));
                    return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "lambda-value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("142\n102",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void proceduralDoMatchReturnExitsNearestTypedLambdaOnly() throws Exception {
        String guest = """
                fnc pick(bool selected): int {
                    const fnc nested = || -> int {
                        do match selected over {
                            on true { return 41; }
                            on false { return 1; }
                        }
                        return -99;
                    };
                    return nested() + 100;
                }
                pub fnc main(): void {
                    stdio.println(pick(true));
                    stdio.println(pick(false));
                    return;
                }
                """;
        TypeChecker.check(Parser.parse(guest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "lambda-procedural-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("141\n101",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void explicitScalarReturnMustAgreeWithEveryReachableResult() {
        for (String body : new String[] {
                "return \"wrong\";",
                "return;",
                "stdio.println(1);",
                // A single returning branch does not satisfy an int callable.
                "if true { return 42; } fi",
                "if true { return \"wrong\"; } fi"
        }) {
            String guest = "fnc f(): void { const fnc bad = || -> int { "
                    + body + " }; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), body);
        }
    }

    @Test
    void explicitVoidRejectsReturningAValue() {
        for (String body : new String[] {
                "return 42;",
                // Nested returns cannot escape the void result contract.
                "if true { return 42; } fi"
        }) {
            String guest = "fnc f(): void { const fnc bad = || -> void { "
                    + body + " }; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), body);
        }
    }

    @Test
    void deferRejectsAnOtherwiseValidNonVoidCallback() {
        for (String cleanup : new String[] {
                "|| -> int { return 42; }",
                // Even a void callback is invalid when it needs an argument.
                "|item: int| -> void { stdio.println(item); }"
        }) {
            String guest = "fnc f(): void { defer " + cleanup + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), cleanup);
        }
    }

    @Test
    void inferredMultiStatementDeferMustNotHideValueReturningCleanup() {
        for (String body : new String[] {
                "stdio.println(1); return 42;",
                "if true { return 42; } fi"
        }) {
            String guest = "fnc f(): void { defer || -> { " + body
                    + " }; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), body);
        }
    }

    @Test
    void malformedReturnTypeHeadersCannotBecomeExpressions() {
        for (String head : new String[] {
                "|| -> void",
                "|| -> int",
                "|| -> int, string { return 1; }"
        }) {
            String guest = "fnc f(): void { const fnc bad = " + head + "; return; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(guest), head);
        }
    }

    @Test
    void bareScalarReturnAnnotationMustHaveBracedBody() {
        for (String type : new String[] {
                "int", "uint", "i32", "i64", "bool", "boolean",
                "string", "float", "f64", "decimal", "bigint"
        }) {
            String guest = "fnc f(): void { const bad = || -> "
                    + type + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(guest), type);
        }
    }

    @Test
    void bareScalarTypeGuardDoesNotConsumeExpressionBodies() {
        Ast.LambdaExpr expression = firstLambda("""
                fnc f(): void {
                    const fnc callback = |int: int| -> int + 1;
                    return;
                }
                """);
        assertNull(expression.declaredReturnType());
        assertNotNull(expression.expressionBody());
    }

    @Test
    void namedCallableLambdaAcceptsExplicitReturnType() {
        Ast.Program parsed = Parser.parse("""
                fnc increment = |x: int| -> int {
                    return x + 1;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }

    @Test
    void anonymousFncExpressionsUseTheSameTypedLambdaContract() throws Exception {
        String guest = """
                fnc compute(): int {
                    const add = fnc(a: int, b: int) -> int {
                        return a + b;
                    };
                    return add(19, 23);
                }
                pub fnc main(): void {
                    stdio.println(compute());
                    return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "anonymous-fnc.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void nonLexicalAnonymousFncCannotCaptureOuterLocal() {
        String guest = """
                fnc f(): void {
                    const int secret = 7;
                    const fnc bad = nlex fnc(x: int) -> int {
                        return x + secret;
                    };
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void futureTypedDispatchArmsMustNotBeMisparsedAsProceduralReturns() {
        String[] unsupported = {
                """
                fnc f(bool selected): int {
                    return match<int> selected over
                        on true -> int { return 42; }
                        on false -> int { return 0; }
                    end;
                }
                """,
                """
                fnc f(int selected): int {
                    return switch<int> selected over
                        case 7 -> int { return 42; }
                        default -> int { return 0; }
                    end;
                }
                """,
                """
                fnc f(Channel<int> values): int {
                    return select<int> over
                        when readch values: int item -> int { return item; }
                    end;
                }
                """,
                """
                fnc f(bool selected): int {
                    return do match selected over
                        on true -> { return 42; }
                        on false -> { return 0; }
                    end;
                }
                """
        };
        for (String guest : unsupported) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), guest);
        }
    }

    @Test
    void constructorReturnTypeDoesNotHideExpressionLambda() {
        Ast.LambdaExpr expression = firstLambda("""
                fnc f(): void {
                    const fnc callback = |x: int| -> x + 1;
                    return;
                }
                """);
        assertNull(expression.declaredReturnType());
        assertNotNull(expression.expressionBody());
    }
}
