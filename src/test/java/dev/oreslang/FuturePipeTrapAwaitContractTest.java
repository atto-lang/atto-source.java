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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable trap/await precedence, plus a fail-closed guard for asynchronous
 * Future-returning stages outside the first pure-int lowering slice.
 */
final class FuturePipeTrapAwaitContractTest {
    @Test
    void trapAwaitWrapsCompletedAndSuspendedFutureAsOptionOfValue() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                async fnc answer(): int {
                  rt cooperate;
                  return 21;
                }

                async fnc invalid(): int {
                  rt cooperate;
                  val Animal animal = new Cat();
                  val Dog dog = animal as Dog;
                  return 0;
                }

                pub fnc main(): void {
                  val Option<int> some = trap await (answer());
                  val Option<int> none = trap await (invalid());
                  stdio.stdout.write(some.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(none.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("21:true", run(source));
    }

    @Test
    void awaitTrapIsNotEquivalentToTrapAwait() {
        String source = """
                async fnc answer(): int { return 21; }
                pub fnc main(): void {
                  val Option<int> value = await trap (answer());
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("await requires Future"),
                error::getMessage);
    }

    @Test
    void trapWithoutAwaitReturnsOptionOfPendingFutureNotOptionOfItsResult() {
        String source = """
                async fnc answer(): int { return 21; }
                pub fnc main(): void {
                  val Option<Future<int>> pending = trap (answer());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        String wrongType = source.replace("Option<Future<int>>", "Option<int>");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongType)));
    }

    @Test
    void futurePipelineCannotSilentlyPassFutureAsSynchronousStageInput() {
        String source = """
                async fnc answer(): int { return 21; }
                async fnc save(int value): int { return value + 1; }
                pub fnc main(): void {
                  val Option<int> result = trap await (answer() |> save());
                  return;
                }
                """;
        // A Future-returning stage requires a checked ownership and effect plan.
        // The first source lowering only admits synchronous, pure int -> int
        // stages; this async stage must remain rejected.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, code, "future-pipe-trap-await.ores")
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
