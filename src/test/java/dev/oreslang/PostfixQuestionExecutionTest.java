package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Executable first-stage synchronous, local-carrier Option/Result postfix ?. */
final class PostfixQuestionExecutionTest {
    @Test
    void valueAndErrorEarlyReturnsSkipLaterStatements() throws Exception {
        String source = """
                fnc successful(): Result<int, String> {
                  val Result<int, String> value = Ok(5);
                  val int number = value?;
                  return Ok(number + 1);
                }

                fnc failed(): Result<int, String> {
                  val Result<int, String> value = Err("bad");
                  val int number = value?;
                  stdio.println("invalid: after error");
                  return Ok(number + 1);
                }

                fnc present(): Option<int> {
                  val Option<int> value = Some(7);
                  val int number = value?;
                  return Some(number + 1);
                }

                fnc missing(): Option<int> {
                  val Option<int> value = None;
                  val int number = value?;
                  stdio.println("invalid: after absence");
                  return Some(number + 1);
                }

                pub fnc main(): void {
                  stdio.println(successful().unwrap());
                  stdio.println(failed().is_err());
                  stdio.println(present().unwrap());
                  stdio.println(missing().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("6\ntrue\n8\ntrue\n", run(source));
    }

    @Test
    void earlyReturnRunsFinallyWithoutEnteringCatch() throws Exception {
        String source = """
                fnc try_result(): Result<int, String> {
                  try {
                    val Result<int, String> value = Err("bad");
                    val int number = value?;
                    stdio.println("invalid: after early return");
                    return Ok(number);
                  } catch (err) {
                    stdio.println("invalid: caught Err");
                    return Err("caught");
                  } finally {
                    stdio.println("finally");
                  }
                }

                pub fnc main(): void {
                  stdio.println(try_result().is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("finally\ntrue\n", run(source));
    }

    @Test
    void earlyReturnRunsLifoDefersExactlyOnce() throws Exception {
        String source = """
                fnc failed(): Result<int, String> {
                  defer stdio.println("outer");
                  defer stdio.println("inner");
                  val Result<int, String> value = Err("bad");
                  val int number = value?;
                  stdio.println("invalid: after Err");
                  return Ok(number);
                }

                pub fnc main(): void {
                  stdio.println(failed().is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("inner\nouter\ntrue\n", run(source));
    }

    @Test
    void affineCarrierCannotBeReusedAfterQuestionMovesIt() {
        String source = """
                fnc reuse(): Result<Array<int>, String> {
                  val Result<Array<int>, String> original = Ok([1, 2]);
                  val Array<int> array = original?;
                  return original;
                }
                """;
        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(invalid.getMessage().contains("moved")
                        || invalid.getMessage().contains("consumed"),
                invalid::getMessage);
    }

    @Test
    void copyableCarrierCanBeReusedAfterQuestion() {
        String source = """
                fnc reuse(): Result<int, String> {
                  val Result<int, String> original = Ok(3);
                  val int number = original?;
                  return original;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void directCallChainMustUseOwnedBindingUntilBorrowCheckerIsProven() {
        String source = """
                fnc inner(): Result<int, String> { return Ok(3); }
                fnc outer(): Result<int, String> {
                  val int number = inner()?;
                  return Ok(number + 1);
                }
                """;
        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(invalid.getMessage().contains("bind the chain result"), invalid::getMessage);
    }

    private static String run(String code) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, code, "postfix-question.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
