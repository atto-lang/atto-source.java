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

final class LambdaParameterOrderTest {
    @Test
    void bothOrdersParseWithIdenticalTypeInformation() {
        for (String args : new String[] { "string bar, string star", "bar: string, star: string" }) {
            Ast.Program ast = Parser.parse("""
                    pub routine main(): void {
                      const fnc foo = |%s| -> { return bar + star; };
                      return;
                    }
                    """.formatted(args));
            Ast.FunctionDecl main = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
            Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
            Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
            assertEquals("foo", binding.name());
            assertEquals("bar", lambda.parameters().getFirst().name());
            assertEquals("string", lambda.parameters().getFirst().type().name());
            assertEquals("star", lambda.parameters().get(1).name());
            assertEquals("string", lambda.parameters().get(1).type().name());
            assertDoesNotThrow(() -> TypeChecker.check(ast));
        }
    }

    @Test
    void bothOrdersEvaluateWithSameClosureSemantics() throws Exception {
        String code = """
                pub routine main(): void {
                  const fnc first = |string bar, string star| -> { return bar + star; };
                  const fnc second = |bar: string, star: string| -> { return bar + star; };
                  const third = |bar: string, star: string| -> bar + star;
                  stdio.stdout.write(first("a", "b"));
                  stdio.stdout.write(":");
                  stdio.stdout.write(second("c", "d"));
                  stdio.stdout.write(":");
                  stdio.stdout.write(third("e", "f"));
                  return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, code, "lambda-order.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
            ctx.eval(src);
        }
        assertEquals("ab:cd:ef", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void namedFunctionDeclarationAndModuleBindingsAcceptBothOrders() {
        for (String args : new String[] {"string bar, string star", "bar: string, star: string"}) {
            Ast.Program named = Parser.parse("""
                    fnc concat = |%s| -> string { return bar + star; }
                    """.formatted(args));
            assertDoesNotThrow(() -> TypeChecker.check(named));
            Ast.Program binding = Parser.parse("""
                    const fnc concat = |%s| -> bar + star;
                    """.formatted(args));
            assertDoesNotThrow(() -> TypeChecker.check(binding));
        }
    }

    @Test
    void singleNameFirstParameterLeavesClosingPipeForTheLambda() {
        Ast.Program ast = Parser.parse("""
                pub routine main(): void {
                  const fnc foo = |bar: string| -> { return bar; };
                  return;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(ast));
    }

    @Test
    void mixedTypedOrdersInSameLambdaAreAccepted() {
        Ast.Program ast = Parser.parse("""
                pub routine main(): void {
                  const fnc foo = |string bar, star: string| -> { return bar + star; };
                  return;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(ast));
    }

    @Test
    void rejectedMalformedAnnotationsDoNotBecomeInferredParameters() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void {
                  const fnc bad = |bar:| -> { return bar; };
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void {
                  const fnc bad = |bar: string, star:| -> { return bar; };
                  return;
                }
                """));
    }

    @Test
    void unionTypePipesAreDistinctFromTheClosingLambdaPipe() {
        for (String params : new String[] {
                "candidate: string | int",
                "string | int candidate"
        }) {
            Ast.Program ast = assertDoesNotThrow(() -> Parser.parse("""
                    pub routine main(): void {
                      const fnc typed = |%s| -> { return candidate; };
                      return;
                    }
                    """.formatted(params)));
            Ast.FunctionDecl main = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
            Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
            Ast.LambdaExpr lambda = (Ast.LambdaExpr) binding.initializer();
            assertEquals("candidate", lambda.parameters().getFirst().name());
            assertTrue(lambda.parameters().getFirst().type().isUnion());
            assertEquals(2, lambda.parameters().getFirst().type().arguments().size());
        }
    }

    @Test
    void nameFirstNonLexicalLambdaCannotCaptureAnOuterLocal() {
        Ast.Program ast = Parser.parse("""
                pub routine main(): void {
                  const string secret = "hidden";
                  const fnc bad = nlex |value: string| -> {
                    return value + secret;
                  };
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(ast));
    }

    @Test
    void nameFirstDuplicateLambdaParametersAreRejectedByTypeChecker() {
        Ast.Program ast = Parser.parse("""
                pub routine main(): void {
                  const fnc bad = |value: string, value: string| -> value;
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(ast));
    }

    @Test
    void shorthandRequiresLambdaAndImmutableBinding() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void { const fnc bad = 42; return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void { let fnc bad = || -> 42; return; }
                """));
    }

    @Test
    void fourRequestedVoidLambdaStylesHaveSameTypeAndRuntimeContract() throws Exception {
        String[] forms = {
                "|int foo, string x| -> { return; };",
                "|foo: int, x: string| -> { return; }",
                "|foo: int, x: string| -> void { return; }",
                "|foo: int, x: string| : void -> { return; }"
        };
        for (String form : forms) {
            String code = """
                    pub routine main(): void {
                      const v = %s
                      v(5, "ok");
                      stdio.stdout.write("pass");
                      return;
                    }
                    """.formatted(form);
            Ast.Program ast = Parser.parse(code);
            Ast.FunctionDecl main = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
            Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
            Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
            assertEquals(2, lambda.parameters().size(), form);
            assertEquals("foo", lambda.parameters().getFirst().name(), form);
            assertEquals("int", lambda.parameters().getFirst().type().name(), form);
            assertEquals("x", lambda.parameters().get(1).name(), form);
            assertEquals("string", lambda.parameters().get(1).type().name(), form);
            assertEquals(form.contains("void"), lambda.declaredReturnType() != null, form);
            assertDoesNotThrow(() -> TypeChecker.check(ast), form);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Source src = Source.newBuilder(OresLanguage.ID, code, "four-void-lambdas.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                ctx.eval(src);
            }
            assertEquals("pass", out.toString(StandardCharsets.UTF_8), form);
        }
    }

    @Test
    void malformedColonReturnAndConflictingVoidPathsReject() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc main(): void {
                    const v = |foo: int| : void { return; };
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc main(): void {
                    const v = |foo: int| : void -> return;
                    return;
                }
                """));
        Ast.Program bad = Parser.parse("""
                fnc main(): void {
                    const v = |foo: int| : void -> { return 7; };
                    return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(bad));
    }


    @Test
    void versionedAttoLambdasRequirePipeDelimitersWithoutBreakingLegacyParser() {
        String[] invalid = {
                "pub fnc main(): int { val v = fnc(int x) -> x + 1; return 0; }",
                "pub fnc main(): int { val v = (int x) -> x + 1; return 0; }",
                "pub fnc main(): int { val v = nlex fnc(int x) -> x + 1; return 0; }"
        };
        for (int version : new int[] {1, 2}) {
            for (String source : invalid) {
                IllegalArgumentException rejected = assertThrows(
                        IllegalArgumentException.class,
                        () -> Parser.parseVersionedAtto(source, version),
                        "Atto v" + version + " must require |...| for function expressions");
                assertTrue(rejected.getMessage().contains("E_LAMBDA_PIPES"),
                        "unexpected rejection phase: " + rejected.getMessage());
            }
            assertDoesNotThrow(() -> Parser.parseVersionedAtto(
                    "pub fnc main(): int { val v = |int x| -> x + 1; return 0; }",
                    version), "pipe-delimited lambda must remain accepted");
        }

        // Parser.parse is the historical Oreslang grammar, not the versioned
        // AttoLang entrypoint. Do not silently remove its legacy compatibility.
        assertDoesNotThrow(() -> Parser.parse(invalid[0]));
        assertDoesNotThrow(() -> Parser.parse(invalid[1]));
    }
}
