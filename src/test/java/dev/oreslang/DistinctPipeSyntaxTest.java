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
import static org.junit.jupiter.api.Assertions.*;

final class DistinctPipeSyntaxTest {
    @Test
    void functionalPipelineInjectsItsInputAsFirstArgument() throws Exception {
        assertEquals("21", run("""
                pub fnc add(int input, int offset): int {
                    return input + offset;
                }
                pub fnc triple(int input): int {
                    return input * 3;
                }
                pub routine main(): void {
                    val int outcome = 3 |> add(4) |> triple;
                    stdio.stdout.write(outcome);
                    return;
                }
                """));
    }

    @Test
    void functionalPipelineEvaluatesEveryStageExactlyOnce() throws Exception {
        assertEquals("ABC6", run("""
                pub fnc first(int value): int {
                    stdio.stdout.write("A");
                    return value + 1;
                }
                pub fnc second(int value, int amount): int {
                    stdio.stdout.write("B");
                    return value * amount;
                }
                pub fnc final_step(int value): int {
                    stdio.stdout.write("C");
                    return value;
                }
                pub routine main(): void {
                    val int answer = 2 |> first |> second(2) |> final_step;
                    stdio.stdout.write(answer);
                    return;
                }
                """));
    }

    @Test
    void functionalPipingDoesNotAlterBitwiseOrOrPipeLambdaLexing() {
        var tokens = new Lexer("value |> double; a | b; |x| -> x;").scan();
        assertEquals(1, tokens.stream().filter(t -> t.type() == Token.Type.PIPE_FORWARD).count());
        assertEquals(3, tokens.stream().filter(t -> t.type() == Token.Type.PIPE).count());
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc double(int value): int { return value * 2; }
                pub routine main(): void {
                    val int a = 1 | 2;
                    val int b = 4 |> double;
                    val mapper = |x| -> x;
                    stdio.stdout.write(a + b);
                    return;
                }
                """));
    }

    @Test
    void actorPipeHasDistinctSyntaxButCannotPretendToExecuteWithoutGraphSupport() {
        String code = """
                define actor Source as
                  receive(ActorMail<int> mail): void { return; }
                end
                define actor Sink as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void {
                    val graph = spawn Source()
                        pipe spawn Sink();
                    return;
                }
                """;
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(code));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("must explicitly implement StreamOperator"),
                failure::getMessage);
    }

    @Test
    void pipelineKeywordRemainsLegalAsAnOrdinaryVariableName() throws Exception {
        assertEquals("17", run("""
                pub fnc double(int value): int { return value * 2; }
                pub routine main(): void {
                    val int pipe = 7;
                    val int result = pipe |> double;
                    stdio.stdout.write(result + 3);
                    return;
                }
                """));
    }

    @Test
    void functionalPipeAcceptsParenthesizedTypedLambda() throws Exception {
        assertEquals("17", run("""
                pub routine main(): void {
                    val int outcome = 7 |> (|int x| -> x + 10);
                    stdio.stdout.write(outcome);
                    return;
                }
                """));
    }

    @Test
    void invalidFunctionalPipelineTargetsFailDuringParsing() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                    pub routine main(): void {
                        val result = 3 |> 4;
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("callable name"), failure::getMessage);
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "pipe-syntax.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
