package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class KeywordTryStatementTest {
    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "keyword-try.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void keywordFinallyAndNestedBracedTryPreserveCleanupOrder() throws Exception {
        assertEquals("body|inner|outer|after", run("""
                pub fnc main(): void {
                  try
                    try {
                      stdio.stdout.write("body");
                    } finally {
                      stdio.stdout.write("|inner");
                    }
                  finally
                    stdio.stdout.write("|outer");
                  end
                  stdio.stdout.write("|after");
                  return;
                }
                """));
    }

    @Test
    void nestedKeywordTryConsumesOnlyItsOwnEnd() throws Exception {
        assertEquals("body|inner|outer|after", run("""
                pub fnc main(): void {
                  try
                    try
                      stdio.stdout.write("body");
                    finally
                      stdio.stdout.write("|inner");
                    end
                  finally
                    stdio.stdout.write("|outer");
                  end
                  stdio.stdout.write("|after");
                  return;
                }
                """));
    }

    @Test
    void bracedInnerCatchCannotStealOuterKeywordCatchAndFinally() throws Exception {
        assertEquals("body|outer-finally", run("""
                pub fnc main(): void {
                  try
                    try {
                      stdio.stdout.write("body");
                    } catch() {
                      stdio.stdout.write("inner-catch-unreached");
                    }
                  catch()
                    stdio.stdout.write("outer-catch-unreached");
                  finally
                    stdio.stdout.write("|outer-finally");
                  end
                  return;
                }
                """));
    }

    @Test
    void catchOnlyAndCatchWithoutBindingAreValid() throws Exception {
        assertEquals("after", run("""
                pub fnc main(): void {
                  try
                    stdio.stdout.write("after");
                  catch()
                    stdio.stdout.write("unreachable");
                  end
                  return;
                }
                """));
        assertEquals("after", run("""
                pub fnc main(): void {
                  try {
                    stdio.stdout.write("after");
                  } catch() {
                    stdio.stdout.write("unreachable");
                  }
                  return;
                }
                """));
    }

    @Test
    void catchAndFinallyTogetherUseExactlyOneEnd() throws Exception {
        assertEquals("body|finally", run("""
                pub fnc main(): void {
                  try
                    stdio.stdout.write("body");
                  catch()
                    stdio.stdout.write("never");
                  finally
                    stdio.stdout.write("|finally");
                  end
                  return;
                }
                """));
    }

    @Test
    void typedErrorCatchIsParsedInBothStyles() throws Exception {
        assertEquals("ok", run("""
                pub fnc main(): void {
                  try
                    stdio.stdout.write("ok");
                  catch (Error e)
                    stdio.stdout.write("never");
                  end
                  return;
                }
                """));
    }

    @Test
    void malformedTryBoundariesFailClosed() {
        for (String invalid : new String[] {
                "try\n stdio.stdout.write(\"x\");\nend",
                "try\n stdio.stdout.write(\"x\");\nfinally\n stdio.stdout.write(\"y\");",
                "try\ncatch()\ncatch()\nend",
                "try\nfinally\ncatch()\nend",
                "try\ncatch() {\n}\nend",
                "try { } catch()\n end"
        }) {
            String code = "pub fnc main(): void {\n" + invalid + "\nreturn;\n}\n";
            assertThrows(PolyglotException.class, () -> run(code), invalid);
        }
    }
}
