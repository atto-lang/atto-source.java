package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Executable guest tests for the unary compiler-owned std.await adapter. */
final class StdAwaitUnaryGuestTest {
    private static final String POSITIVE = """
            async fnc answer(): int { rt cooperate; return 41; }
            fnc transform_result(int value): int { return value + 1; }
            pub fnc main(): void {
                val int result = answer() |> std.await |> transform_result;
                stdio.stdout.write(result);
                return;
            }
            """;

    @Test
    void realGuestExecutesFutureAwaitAndTransformOnce() throws Exception {
        String source = Files.readString(Path.of("tests/fixtures/std-await-v1/forty-two.atto"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
        assertEquals("42", run(POSITIVE));
    }

    @Test
    void parserPreservesIntrinsicProvenanceInsteadOfTreatingAwaitAsCallable() {
        var program = Parser.parse("""
                async fnc answer(): int { return 41; }
                fnc transform_result(int value): int { return value + 1; }
                pub fnc main(): int {
                    return answer() |> std.await |> transform_result;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().get(2);
        Ast.CallExpr transformed = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.ReturnStmt) main.body().getFirst()).value());
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class,
                transformed.arguments().getFirst());
        assertTrue(awaited.coreAdapter());
        assertInstanceOf(Ast.CallExpr.class, awaited.expression());
    }

    @Test
    void wrongOperandShadowingAndFactoryFormsFailClosed() {
        var wrongType = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    pub fnc main(): int { return 41 |> std.await; }
                    """)));
        assertTrue(wrongType.getMessage().contains("await requires Future"),
                wrongType::getMessage);

        var shadowed = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    async fnc answer(): int { return 41; }
                    pub fnc main(): int {
                        val int std = 10;
                        return answer() |> std.await;
                    }
                    """)));
        assertTrue(shadowed.getMessage().contains("shadowed"),
                shadowed::getMessage);

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): int { return 41 |> std.await(); }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): int { return 41 |> std.await2; }"));
    }

    private static String run(String code) throws Exception {
        var out = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, code, "std-await-unary.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
