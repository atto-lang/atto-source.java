package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class FailureEffectLanguageTest {
    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "failure-effects.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void throwIsCatchableAndTrapPreservesError() throws Exception {
        String source = """
                trap fnc fails(): int {
                  throw new Error("ordinary");
                }
                pub fnc main(): void {
                  try {
                    throw new Error("caught");
                  } catch (err) {
                    stdio.stdout.write(err.message);
                  }
                  val Result<int, Error> result = fails();
                  stdio.stdout.write(":");
                  match result over
                    on Ok(number) -> { stdio.stdout.write("unexpected success"); }
                    on Err(failure) -> { stdio.stdout.write(failure.message); }
                  end
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("caught:ordinary", run(source));
    }

    @Test
    void raiseBypassesCatchButRecoverHandlesOnlyTheRaise() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      raise new Error("raised");
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err.message);
                  }
                  stdio.stdout.write(":after");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("raised:after", run(source));
    }

    @Test
    void nestedPayloadCallsRemainReachableThroughStaticPasses() throws Exception {
        String source = """
                fnc payload(): Error {
                  return new Error("kept");
                }
                pub fnc main(): void {
                  rt recover {
                    raise payload();
                  } on raise (Error err) {
                    stdio.stdout.write(err.message);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("kept", run(source));
    }

    @Test
    void panicCannotBeRecoveredOrCaught() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      panic new Error("actor corrupt");
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write("wrong");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failed = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failed.getMessage().contains("actor corrupt"));
    }

    @Test
    void strongerRaiseSurvivesFinallyAndDeferThrows() throws Exception {
        String source = """
                fnc cleanup(): void {
                  throw new Error("cleanup");
                }
                pub fnc main(): void {
                  rt recover {
                    defer cleanup();
                    try {
                      raise new Error("strong");
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    } finally {
                      throw new Error("weaker");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err.message);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("strong", run(source));
    }

    @Test
    void panicInFinallyOutranksAnEarlierRaise() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      raise new Error("earlier raise");
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    } finally {
                      panic new Error("new panic");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write("wrong");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failed = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failed.getMessage().contains("new panic"));
    }

    @Test
    void raiseAfterCooperativeSuspensionSkipsCatchAndReachesRecover() throws Exception {
        String source = """
                pub async routine main(): void {
                  rt recover {
                    try {
                      rt cooperate;
                      raise new Error("after-suspension");
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err.message);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("after-suspension", run(source));
    }

    @Test
    void deferredCleanupThrowCannotDowngradeSuspendedRaise() throws Exception {
        String source = """
                fnc cleanup(): void {
                  throw new Error("cleanup");
                }
                pub async routine main(): void {
                  rt recover {
                    defer cleanup();
                    rt cooperate;
                    raise new Error("preserved");
                  } on raise (Error err) {
                    stdio.stdout.write(err.message);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("preserved", run(source));
    }

    @Test
    void scalarPayloadsDoNotImplementExceptionalEffectTraits() {
        for (String keyword : new String[] {"throw", "raise", "panic", "throw!", "raise!", "panic!"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    OresCompiler.parseAndTypeCheck("pub fnc main(): void { "
                            + keyword + " \"not an error\"; }"), keyword);
        }
    }

    @Test
    void bareFailuresAreRejectedByParser() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("pub fnc main(): void { throw; }"));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("pub fnc main(): void { panic; }"));
    }
}
