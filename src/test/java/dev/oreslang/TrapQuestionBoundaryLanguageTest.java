package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TrapQuestionBoundaryLanguageTest {
    @Test
    void trapPreservesErrorValuesAndAddsOneOptionLayer() throws Exception {
        String source = """
                fnc typed_error(): Result<int, String> {
                  return Err("not-found");
                }

                fnc optional_absence(): Option<int> {
                  return None;
                }

                pub fnc main(): void {
                  val Option<Result<int, String>> trapped_error = trap typed_error();
                  val Option<Option<int>> trapped_none = trap optional_absence();
                  val Option<int> trapped_success = trap (20 + 2);
                  stdio.stdout.write(trapped_error.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(trapped_error.unwrap().is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(trapped_none.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(trapped_none.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(trapped_success.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:true:true:22", run(source));
    }

    @Test
    void trapAndQuestionComposeWithoutConvertingErrToException() throws Exception {
        String source = """
                fnc trapped_value(): Option<int> {
                  val int item = (trap (20 + 2))?;
                  return Some(item + 1);
                }

                fnc propagate(Result<int, String> value): Result<int, String> {
                  val int item = value?;
                  return Ok(item + 1);
                }

                pub fnc main(): void {
                  val Result<int, String> failure = Err("typed");
                  stdio.stdout.write(trapped_value().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(propagate(failure).is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("23:true", run(source));
    }

    @Test
    void trapAwaitReturnsSomeErrButQuestionReturnsErr() throws Exception {
        String source = """
                async fnc fetch_value(): Result<int, String> {
                  return Err("not-found");
                }

                async fnc trapped(): Option<Result<int, String>> {
                  val Option<Result<int, String>> value = trap (await fetch_value());
                  return value;
                }

                async fnc propagated(): Result<int, String> {
                  val int value = (await fetch_value())?;
                  return Ok(value);
                }

                pub fnc main(): void {
                  val Option<Result<int, String>> trapped_value = await trapped();
                  val Result<int, String> propagated_value = await propagated();
                  stdio.stdout.write(trapped_value.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(trapped_value.unwrap().is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(propagated_value.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:true", run(source));
    }

    @Test
    void trapAwaitOrdinaryFailureTurnsNoneAndQuestionReturnsEarly() throws Exception {
        String source = """
                async fnc fail_after_task_start(): int {
                  val Array<int> values = new Array<int>();
                  return values.get(0);
                }

                async fnc caught(): Option<int> {
                  val int item = (trap (await fail_after_task_start()))?;
                  stdio.stdout.write("UNEXPECTED:");
                  return Some(item);
                }

                pub fnc main(): void {
                  val Option<int> result = await caught();
                  stdio.stdout.write(result.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true", run(source));
    }

    @Test
    void expressionTrapDoesNotSuppressPanic() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Option<int> absent = None;
                  val Option<int> trapped = trap absent.unwrap();
                  stdio.stdout.write(trapped.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(thrown.getMessage().contains("Option::unwrap"));
    }

    @Test
    void assigningTrapAwaitToIntIsRejected() {
        String source = """
                async fnc fetch_value(): Result<int, String> {
                  return Ok(4);
                }
                async fnc invalid(): Result<int, String> {
                  val int value = trap (await fetch_value());
                  return Ok(value);
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "trap-question.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
