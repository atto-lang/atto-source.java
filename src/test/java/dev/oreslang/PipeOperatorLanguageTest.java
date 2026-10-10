package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The forward pipe applies functions; contextual 'pipe' connects stream stages.
 * The actor-graph transport is deliberately fail-closed until it can enforce
 * typed ports, bounded backpressure and lifecycle propagation.
 */
final class PipeOperatorLanguageTest {

    @Test
    void forwardPipeIsNotBitwiseOrLogicalOrOrLambdaDelimiter() {
        List<Token> tokens = new Lexer("a |> f; a | b; a || b; || -> {}; pipe").scan();
        assertEquals(1, tokens.stream()
                .filter(t -> t.type() == Token.Type.PIPE_FORWARD).count());
        assertEquals(5, tokens.stream()
                .filter(t -> t.type() == Token.Type.PIPE).count());
        Token keyword = tokens.get(tokens.size() - 2);
        assertEquals(Token.Type.IDENT, keyword.type());
        assertEquals("pipe", keyword.lexeme());
    }

    @Test
    void forwardPipeLowersToLeftAssociativeCallsWithFirstArgumentInsertion() {
        Ast.Program program = Parser.parse("""
                pub fnc f(int value): int { return value; }
                pub fnc g(int value, int offset): int { return value + offset; }
                pub routine main(): void {
                    const answer = 7 |> f |> g(3);
                    return;
                }
                """);

        Ast.ModuleDecl root = program.modules().getFirst();
        Ast.FunctionDecl main = root.declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst().orElseThrow();
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, main.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, binding.initializer());
        assertEquals("g", assertInstanceOf(Ast.NameExpr.class, call.callee()).name());
        assertEquals(2, call.arguments().size());
        Ast.CallExpr inner = assertInstanceOf(Ast.CallExpr.class, call.arguments().getFirst());
        assertEquals("f", assertInstanceOf(Ast.NameExpr.class, inner.callee()).name());
        assertEquals(1, inner.arguments().size());
        assertEquals(7L,
                assertInstanceOf(Ast.LiteralExpr.class, inner.arguments().getFirst()).value());
        assertEquals(3L,
                assertInstanceOf(Ast.LiteralExpr.class, call.arguments().get(1)).value());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void forwardPipeExecutesAsOrdinaryFunctionApplication() throws Exception {
        assertEquals("9", run("""
                pub fnc increment(int x): int { return x + 1; }
                pub fnc add(int x, int y): int { return x + y; }
                pub routine main(): void {
                    const value = 4 |> increment |> add(3) |> increment;
                    stdio.stdout.write(value);
                    return;
                }
                """));
    }

    @Test
    void pipeWordIsContextualAndActorConnectionsRemainFailClosed() {
        Ast.Program program = Parser.parse("""
                define actor First as
                    receive(ActorMail<int> mail): void { self.end(); return; }
                end
                define actor Second as
                    receive(ActorMail<int> mail): void { self.end(); return; }
                end
                pub routine main(): void {
                    const flow = spawn First() pipe spawn Second();
                    return;
                }
                """);
        Ast.FunctionDecl main = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst().orElseThrow();
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, main.body().getFirst());
        Ast.BinaryExpr graph = assertInstanceOf(Ast.BinaryExpr.class, binding.initializer());
        assertEquals("pipe", graph.operator());
        assertInstanceOf(Ast.RuntimeCallExpr.class, graph.left());
        assertInstanceOf(Ast.RuntimeCallExpr.class, graph.right());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("not executable yet"), failure::getMessage);
    }

    @Test
    void barePipeAsIdentifierAndLegacyOperatorsRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc choose(int pipe): int {
                    return (pipe | 1) + 1;
                }
                pub fnc both(bool left, bool right): bool {
                    return left || right;
                }
                pub routine main(): void {
                    return;
                }
                """)));
    }

    @Test
    void actorPipeNeverOverloadsOrdinaryValueAndFunctionApplication() {
        Ast.Program program = Parser.parse("""
                pub routine main(): void {
                    const bad = 1 pipe 2;
                    return;
                }
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("use '|>' for function application"),
                error::getMessage);
    }

    @Test
    void forwardPipeRejectsNonCallableRightHandSide() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub routine main(): void {
                            const x = 1 |> 2;
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("'|>' expects a callable"),
                failure::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe-operators.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
