package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest-execution assertions for lexical ARM_RESULT cleanup precedence. */
final class TypedValueMatchCleanupPrecedenceTest {
    private static String output(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    private static void eval(String code, ByteArrayOutputStream out) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, code, "arm-result-cleanup-precedence.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
    }

    @Test
    void failedDeferAbortsPreparedArmResultAndStillRunsAllCleanups() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(PolyglotException.class, () -> eval("""
                pub fnc main(): void {
                  const result: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("first");
                      defer stdio.println(1 % 0);
                      defer stdio.println("last");
                      return 42;
                    }
                  };
                  stdio.println("result-delivered");
                  stdio.println(result);
                }
                """, out));
        assertEquals("last\nfirst", output(out),
                "failed cleanup must suppress ARM_RESULT, while other defers still run LIFO");
    }

    @Test
    void failedFinallyOverridesArmResultWithoutEnteringExitedTryCatch() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(PolyglotException.class, () -> eval("""
                pub fnc main(): void {
                  const result: int = match<ret=int> 7 over {
                    default {
                      try {
                        return 42;
                      } catch (e) {
                        stdio.println("caught");
                        return 0;
                      } finally {
                        stdio.println("finalizing");
                        stdio.println(1 % 0);
                      }
                    }
                  };
                  stdio.println("result-delivered");
                  stdio.println(result);
                }
                """, out));
        assertEquals("finalizing", output(out));
    }

    @Test
    void originalArmFailureSurvivesFailingCleanupAndRunsOtherDefers() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(PolyglotException.class, () -> eval("""
                pub fnc main(): void {
                  const result: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("remaining");
                      defer stdio.println(2 % 0);
                      const invalid: int = 1 % 0;
                      return invalid;
                    }
                  };
                  stdio.println("result-delivered");
                  stdio.println(result);
                }
                """, out));
        assertEquals("remaining", output(out));
    }

    @Test
    void nestedMatchesRunEachLexicalCleanupOnce() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        eval("""
                pub fnc main(): void {
                  const result: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("outer");
                      const nested: int = match<ret=int> 7 over {
                        default {
                          defer stdio.println("inner");
                          return 40;
                        }
                      };
                      return nested + 2;
                    }
                  };
                  stdio.println(result);
                }
                """, out);
        assertEquals("inner\nouter\n42", output(out));
    }
    @Test
    void originalPanicIdentitySurvivesFailingFinally() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PolyglotException failure = assertThrows(PolyglotException.class, () -> eval("""
                pub fnc main(): void {
                  try {
                    val Option<int> missing = None;
                    val int ignored = missing.unwrap();
                  } catch (e) {
                    stdio.println("panic-caught");
                  } finally {
                    stdio.println("finally-ran");
                    val int cleanupError = 1 % 0;
                  }
                  stdio.println("continued");
                }
                """, out));
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "finally masked the original panic: " + failure.getMessage());
        assertEquals("finally-ran", output(out));
    }

}
