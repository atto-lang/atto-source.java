package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OptionErgonomicsLanguageTest {
    @Test
    void lazilyCallsFallbackOnlyOnAbsentOptionOrResultError() throws Exception {
        String program = """
                fnc fallback(): int {
                  stdio.stdout.write("F");
                  return 42;
                }
                fnc alternative(): Option<int> {
                  stdio.stdout.write("O");
                  return Some(8);
                }
                pub fnc main(): void {
                  val Option<int> present = Some(3);
                  val Option<int> absent = None;
                  stdio.stdout.write(present.unwrap_or_else(() -> fallback()));
                  stdio.stdout.write(":");
                  stdio.stdout.write(absent.unwrap_or_else(() -> fallback()));
                  stdio.stdout.write(":");
                  stdio.stdout.write(present.or_else(() -> alternative()).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(absent.or_else(() -> alternative()).unwrap());
                  stdio.stdout.write(":");
                  val Result<int, String> good = Ok(11);
                  val Result<int, String> bad = Err("bad");
                  stdio.stdout.write(good.unwrap_or_else((String error) -> fallback()));
                  stdio.stdout.write(":");
                  stdio.stdout.write(bad.unwrap_or_else((String error) -> fallback()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("3:F42:3:O8:11:F42", run(program));
    }

    @Test
    void okOrEagerlyEvaluatesErrorAndRetainsCorrectVariant() throws Exception {
        String program = """
                fnc error_value(): String {
                  stdio.stdout.write("E");
                  return "missing";
                }
                pub fnc main(): void {
                  val Option<int> present = Some(9);
                  val Result<int, String> good = present.ok_or(error_value());
                  stdio.stdout.write(good.unwrap());
                  stdio.stdout.write(":");
                  val Option<int> absent = None;
                  val Result<int, String> bad = absent.ok_or(error_value());
                  stdio.stdout.write(bad.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("E9:Etrue", run(program));
    }

    @Test
    void orElseContextuallyTypesLiteralNoneWithoutErasingOptionElementType() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Option<int> absent = None;
                  val Option<int> still_absent = absent.or_else(() -> None);
                  stdio.stdout.write(still_absent.is_none());
                  stdio.stdout.write(":");
                  val Option<int> present = Some(9);
                  val Option<int> unchanged = present.or_else(() -> None);
                  stdio.stdout.write(unchanged.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:9", run(source));
    }

    @Test
    void orElseNeverWidenOptionElementFromWrongSomePayload() {
        assertRejects("""
                fnc bad(Option<int> original): Option<int> {
                  return original.or_else(() -> Some("not-an-int"));
                }
                """, "Option.or_else fallback");
    }

    @Test
    void rejectsIncorrectCallbacksAndAffinelyOwnedFallbacks() {
        assertRejects("""
                fnc bad(Option<int> value): int {
                  return value.unwrap_or_else((int unexpected) -> 5);
                }
                """, "lambda expects 0");
        assertRejects("""
                fnc bad(Option<int> value): Option<int> {
                  return value.or_else(() -> 5);
                }
                """, "Option.or_else fallback");
        assertRejects("""
                define class Box as
                end
                fnc bad(Option<Box> value): Box {
                  return value.unwrap_or_else(() -> new Box());
                }
                """, "requires Copy");
        assertRejects("""
                fnc bad(Option<int> value): Result<int, String> {
                  return value.ok_or();
                }
                """, "one eager error value");
    }

    @Test
    void resultOrElseSkipsOkAndInvokesErrFallbackExactlyOnce() throws Exception {
        String program = """
                fnc recover_value(String reason): Result<int, String> {
                  stdio.stdout.write("R");
                  return Ok(33);
                }
                pub fnc main(): void {
                  val Result<int, String> good = Ok(7);
                  val Result<int, String> bad = Err("missing");
                  val first = good.or_else((String reason) -> recover_value(reason));
                  stdio.stdout.write(first.unwrap());
                  stdio.stdout.write(":");
                  val second = bad.or_else((String reason) -> recover_value(reason));
                  stdio.stdout.write(second.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("7:R33", run(program));
    }

    @Test
    void resultOrElsePreservesAffinePayloadAndChecksItsReceiverOnce() {
        String legal = """
                define class Box as
                end
                fnc recover_value(Result<Box, String> value): Result<Box, String> {
                  return value.or_else((String reason) -> Err(reason));
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(legal));

        assertRejects("""
                define class Box as
                end
                fnc misuse(Result<Box, String> value): void {
                  val first = value.or_else((String reason) -> Err(reason));
                  val second = value.or_else((String reason) -> Err(reason));
                  return;
                }
                """, "moved value");
    }

    private static void assertRejects(String source, String message) {
        Throwable failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage() != null && failure.getMessage().contains(message),
                () -> "Expected '" + message + "' in: " + failure.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "option-ergonomics.ores")
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
