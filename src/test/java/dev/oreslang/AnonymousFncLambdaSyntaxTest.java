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

/** Anonymous fnc(...) { ... } is an alternate spelling of a lexical pipe block lambda. */
final class AnonymousFncLambdaSyntaxTest {
    @Test
    void parserUsesTheExistingLambdaAstForInferredAndTypedParameters() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    val Fnc<int, int> a = fnc(chunk) { return chunk + 1; };
                    val Fnc<int, int> b = |chunk| -> { return chunk + 1; };
                    val Fnc<int, int> c = fnc(int chunk) { return chunk + 1; };
                    val Fnc<int> d = fnc() { return 7; };
                    val Fnc<int, int> e = nlex fnc(int chunk) { return chunk; };
                    return;
                }
                """);
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        for (int i = 0; i < 5; i++) {
            Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, main.body().get(i));
            Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
            assertNull(lambda.expressionBody());
            assertNotNull(lambda.blockBody());
            assertEquals(i == 3 ? 0 : 1, lambda.parameters().size());
            assertEquals(i == 4, lambda.nonLexical());
        }
    }

    @Test
    void inlineListsOfSymbolsPreserveExistingTokenAndAstShapes() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    val symbols = [#auto, #numa_aware, #aosoa];
                    return;
                }
                """);
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, main.body().getFirst());
        Ast.ListExpr list = assertInstanceOf(Ast.ListExpr.class, binding.initializer());
        assertEquals(3, list.elements().size());
        String[] expected = {"auto", "numa_aware", "aosoa"};
        for (int i = 0; i < expected.length; i++) {
            Ast.LiteralExpr element = assertInstanceOf(Ast.LiteralExpr.class, list.elements().get(i));
            assertEquals(dev.oreslang.runtime.OresSymbol.process(expected[i]), element.value());
        }
    }

    @Test
    void anonymousFncIsAcceptedAsAPlainPositionalCallbackArgument() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc apply(Fnc<int, int> callback): int {
                    return callback(3);
                }
                pub fnc main(): void {
                    val int result = apply(fnc(int chunk) { return chunk + 1; });
                    return;
                }
                """)));
    }

    @Test
    void lexicalCaptureAndRuntimeResultsMatchPipeBlockLambda() throws Exception {
        String output = run("""
                pub routine main(): void {
                    val int offset = 4;
                    val Fnc<int, int> a = fnc(int chunk) {
                        return chunk + offset;
                    };
                    val Fnc<int, int> b = |int chunk| -> {
                        return chunk + offset;
                    };
                    stdio.stdout.write(a(3));
                    stdio.stdout.write(":");
                    stdio.stdout.write(b(3));
                    return;
                }
                """);
        assertEquals("7:7", output);
    }

    @Test
    void nlexAnonymousFncDoesNotCaptureLocals() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            val int offset = 4;
                            val Fnc<int, int> a = nlex fnc(int chunk) {
                                return chunk + offset;
                            };
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("offset")
                || error.getMessage().contains("unknown name"), error::toString);
    }

    @Test
    void rejectsExpressionBodiesAndMissingBlockWithoutUsingNamedCallableParsing() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                    val x = fnc(chunk) -> chunk;
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                    val x = fnc(chunk);
                    return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "anonymous-fnc.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
