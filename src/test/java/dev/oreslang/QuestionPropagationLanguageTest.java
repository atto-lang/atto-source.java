package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class QuestionPropagationLanguageTest {
    @Test
    void resultOptionAndTernaryRemainDistinctInOneProgram() throws Exception {
        String program = """
                fnc result_step(Result<int, String> value): Result<int, String> {
                  val int x = value?;
                  return Ok(x + 2);
                }

                fnc result_chain(Result<int, String> value): Result<int, String> {
                  val int x = result_step(value)?;
                  return Ok(x * 3);
                }

                fnc optional_step(Option<int> value): Option<int> {
                  val int x = value?;
                  return Some(x + 4);
                }

                pub fnc main(): void {
                  val Result<int, String> ok = Ok(3);
                  val Result<int, String> err = Err("no");
                  val Option<int> some = Some(2);
                  val Option<int> none = None;
                  stdio.stdout.write(result_chain(ok).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(result_chain(err).is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(optional_step(some).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(optional_step(none).is_none());
                  stdio.stdout.write(":");
                  val int classic = true ? 7 : 8;
                  stdio.stdout.write(classic);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("15:true:6:true:7", run(program));
    }

    @Test
    void earlyPropagationRunsFinallyWithoutEnteringCatch() throws Exception {
        String program = """
                fnc guarded(Result<int, String> value): Result<int, String> {
                  try {
                    val int x = value?;
                    return Ok(x);
                  } catch (error) {
                    stdio.stdout.write("UNEXPECTED-CATCH");
                    return Err("caught");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                pub fnc main(): void {
                  val Result<int, String> missing = Err("no");
                  stdio.stdout.write(guarded(missing).is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("finally:true", run(program));
    }

    @Test
    void rejectsDifferentErrorKindsOrUnsupportedCallables() {
        reject("""
                fnc wrong(Option<int> v): Result<int, String> {
                  val int n = v?;
                  return Ok(n);
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                fnc wrong(Result<int, String> v): Result<int, int> {
                  val int n = v?;
                  return Ok(n);
                }
                """, "error type must exactly match");
        reject("""
                fnc wrong(Result<int, String> v): int {
                  return v?;
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                async fnc wrong(Option<int> v): Option<int> {
                  return v?;
                }
                """, "synchronous local");
        reject("""
                fnc wrong(Option<int> v): Option<int> {
                  return trap v?;
                }
                """, "trap");
        reject("""
                fnc wrong(Option<int> v): Option<int> {
                  val Option<int> x = v;
                  return x? + 1;
                }
                """, "expected ':'");
    }

    private static void reject(String source, String expected) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(expected),
                () -> "Expected '" + expected + "' in: " + error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "question.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
