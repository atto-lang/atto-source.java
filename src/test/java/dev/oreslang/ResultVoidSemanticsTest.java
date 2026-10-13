package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ResultVoidSemanticsTest {
    @Test
    void zeroPayloadOkIsTypedAndMatchedByConstructor() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc accept(Result<void, String> result): int {
                  match result over
                    on Ok() -> { return 7; }
                    on Err(message) -> { return 9; }
                  end
                }

                fnc make(): Result<void, String> {
                  return Ok();
                }
                """)));
    }

    @Test
    void noImplicitOkPayloadConversionOrMissingErrPayload() {
        IllegalArgumentException notInt = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): Result<int, String> {
                          return Ok();
                        }
                        """)));
        assertNotNull(notInt.getMessage());

        IllegalArgumentException notVoid = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): Result<void, String> {
                          return Ok(42);
                        }
                        """)));
        assertNotNull(notVoid.getMessage());

        IllegalArgumentException missingErr = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): Result<void, String> {
                          return Err();
                        }
                        """)));
        assertTrue(missingErr.getMessage().contains("expects exactly one value"));

        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Result<void, String> result): int {
                          match result over
                            on Ok() -> { return 7; }
                          end
                        }
                        """)));
        assertTrue(incomplete.getMessage().contains("non-exhaustive"));
    }

    @Test
    void zeroPayloadOkRuntimeHasSafeSharedChildrenAndMatching() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Result<void, String> success = Ok();
                  stdio.println(success);
                  stdio.println(success.is_ok());
                  match success over
                    on Ok() -> { stdio.println("matched"); }
                    on Err(message) -> { stdio.println("unexpected"); }
                  end
                  return;
                }
                """;
        Source source = Source.newBuilder(OresLanguage.ID, program, "result-void.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        String actual = output.toString(StandardCharsets.UTF_8);
        assertTrue(actual.contains("Ok()"));
        assertTrue(actual.contains("true"));
        assertTrue(actual.contains("matched"));
        assertFalse(actual.contains("unexpected"));
    }
}
