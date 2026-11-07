package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed Truffle guest acceptance; unlike host store unit tests,
 * these must run through source parsing, type checking, linking and execution.
 */
final class AttoSymbolLiteralImageGuestTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "symbol-image.atto")
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

    @Test void compiledHashLiteralsShareLogicalIdentityThroughImage() throws Exception {
        String program = """
                pub routine main(): void {
                  const Symbol a = #ready;
                  const Symbol b = #"ready";
                  if a eq b then
                    stdio.stdout.write("equal");
                  else
                    stdio.stdout.write("different");
                  fi
                  return;
                }
                """;
        assertEquals("equal", execute(program));
    }

    @Test void compiledSymbolsAreNotStringValues() throws Exception {
        assertEquals("distinct", execute("""
                pub routine main(): void {
                  if #ready eq "ready" then
                    stdio.stdout.write("aliased");
                  else
                    stdio.stdout.write("distinct");
                  fi
                  return;
                }
                """));
    }

    @Test void malformedHashSyntaxDoesNotReachRuntimeImagePublication() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void {
                  const Symbol malformed = # ready;
                  return;
                }
                """));
    }

    @Test void assigningSymbolToIntegerFailsBeforeGuestExecution() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  const int wrong = #ready;
                  return;
                }
                """)));
    }
}
