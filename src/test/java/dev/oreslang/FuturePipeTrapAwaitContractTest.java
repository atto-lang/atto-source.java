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
 * Current executable trap/await precedence, plus a fail-closed guard until
 * Future-aware |> stage lifting is implemented (see docs/FUTURE_PIPE_TRAP_AWAIT.md).
 */
final class FuturePipeTrapAwaitContractTest {
    @Test
    void trapAwaitWrapsCompletedAndSuspendedFutureAsResultOfValue() throws Exception {
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
                  val Result<int, Error> some = trap await (answer());
                  val Result<int, Error> none = trap await (invalid());
                  stdio.stdout.write(some.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(none.is_err());
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
                  val Result<Future<int>, Error> value = await trap (answer());
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
    void trapWithoutAwaitReturnsResultOfPendingFutureNotResultOfItsValue() {
        String source = """
                async fnc answer(): int { return 21; }
                pub fnc main(): void {
                  val Result<Future<int>, Error> pending = trap (answer());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        String wrongType = source.replace("Result<Future<int>, Error>", "Result<int, Error>");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongType)));
    }

    @Test
    void futurePipelineCannotSilentlyPassFutureAsSynchronousStageInput() {
        String source = """
                async fnc answer(): int { return 21; }
                async fnc save(int value): int { return value + 1; }
                pub fnc main(): void {
                  val Result<int, Error> result = trap await (answer() |> save());
                  return;
                }
                """;
        // The existing eager |> desugaring passes Future<int> into save(int).
        // Until future-aware lifting is implemented, compiling this as though
        // it handled the resolved int would be unsound. This assertion MUST
        // be replaced with a positive end-to-end test when lifting lands.
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
