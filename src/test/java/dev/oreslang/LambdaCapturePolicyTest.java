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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LambdaCapturePolicyTest {
    private static Ast.LambdaExpr lambda(String expression) {
        Ast.Program program = Parser.parse("pub routine main(): void { val callback = "
                + expression + "; return; }");
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.BindingStmt local = (Ast.BindingStmt) main.body().getFirst();
        return (Ast.LambdaExpr) local.initializer();
    }

    @Test
    void capturePoliciesHaveDistinctSemanticsAndDoNotRewriteMethodBodies() {
        Ast.LambdaExpr lexical = lambda("lex[self] |int x| -> x");
        assertEquals(Ast.LambdaCapturePolicy.ALLOW, lexical.capturePolicy());
        assertEquals(List.of("self"), lexical.captureNames());
        assertTrue(lexical.permitsOuterBinding("self"));
        assertFalse(lexical.permitsOuterBinding("other"));

        Ast.LambdaExpr denied = lambda("nlex[self] |int x| -> x");
        assertEquals(Ast.LambdaCapturePolicy.DENY, denied.capturePolicy());
        assertEquals(List.of("self"), denied.captureNames());
        assertFalse(denied.permitsOuterBinding("self"));
        assertTrue(denied.permitsOuterBinding("other"));

        Ast.LambdaExpr bare = lambda("nlex |int x| -> x");
        assertFalse(bare.permitsOuterBinding("self"));
        assertFalse(bare.permitsOuterBinding("other"));
        assertTrue(bare.nonLexical());

        Ast.LambdaExpr defaultLambda = lambda("|int x| -> x");
        assertFalse(defaultLambda.permitsOuterBinding("self"));
        assertTrue(defaultLambda.permitsOuterBinding("other"));
    }

    @Test
    void allowlistAndDenylistCaptureOnlyPermittedOuterValuesAtRuntime() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val int base = 4;
                  val int excluded = 100;
                  val Fnc<int, int> allow = lex[base] |int value| -> base + value;
                  val Fnc<int, int> deny = nlex[excluded] |int value| -> base + value;
                  stdio.stdout.write(allow(3));
                  stdio.stdout.write(deny(5));
                }
                """);
        assertEquals("79", output);
    }

    @Test
    void anAllowlistBlocksEveryOtherOuterBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc invalid(): int {
                          val int allowed = 1;
                          val int hidden = 2;
                          val Fnc<int> callback = lex[allowed] || -> hidden;
                          return callback();
                        }
                        """)));
        assertTrue(error.getMessage().contains("hidden"), error::getMessage);
    }

    @Test
    void aDenylistBlocksOnlyNamedOuterBindingsAndSelfByDefault() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc invalid(): int {
                          val int allowed = 1;
                          val int hidden = 2;
                          val Fnc<int> callback = nlex[hidden] || -> allowed + hidden;
                          return callback();
                        }
                        """)));
        assertTrue(error.getMessage().contains("hidden"), error::getMessage);
    }

    @Test
    void unknownOrRepeatedCaptureNamesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): void {
                  val callback = lex[a, a] || -> 0;
                }
                """));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc invalid(): void {
                          val callback = lex[missing] || -> 0;
                        }
                        """)));
        assertTrue(unknown.getMessage().contains("missing"), unknown::getMessage);
    }

    @Test
    void lambdaParametersCannotShadowExplicitCaptureNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): void {
                  val callback = lex[value] |int value| -> value;
                }
                """));
    }

    @Test
    void selfIsNotImplicitlyVisibleButCanBeExplicitlyNamed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Foo as
                          pub method value(): int { return 4; }
                          pub method invalid(): int {
                            val Fnc<int> callback = || -> self.value();
                            return callback();
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("self"), error::getMessage);

        Ast.LambdaExpr allowed = lambda("lex[self] || -> 4");
        assertTrue(allowed.permitsOuterBinding("self"));
    }

    @Test
    void selectiveCaptureDoesNotBypassBorrowedSelfSafety() {
        String program = """
                define actor Foo as
                  receive(int value): void {
                    val callback = lex[self] || -> self.receive(value);
                    return;
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program ast = Parser.parse(program);
            TypeChecker.check(ast);
            OwnershipChecker.check(ast);
        });
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "lambda-capture-policy.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .out(output)
                .allowAllAccess(true)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
