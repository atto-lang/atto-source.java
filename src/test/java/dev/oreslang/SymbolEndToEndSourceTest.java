package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest language regression: these require a real Oreslang evaluator. */
final class SymbolEndToEndSourceTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "symbol-key-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void matchOnHashSymbolAndClosedRecordValue() throws Exception {
        String source = """
                pub routine main(): void {
                  const r = infer struct{status: #ready};
                  do match r.status over
                    on #ready -> { stdio.stdout.write("matched"); }
                    on _ -> { stdio.stdout.write("fallback"); }
                  end
                  return;
                }
                """;
        assertEquals("matched", execute(source));
    }

    @Test void stringAndSymbolDoNotCompareEqualAtRuntime() throws Exception {
        String source = """
                pub routine main(): void {
                  if #ready eq "ready" then
                    stdio.stdout.write("aliased");
                  else
                    stdio.stdout.write("distinct");
                  fi
                  return;
                }
                """;
        assertEquals("distinct", execute(source));
    }

    @Test void incompatibleStringLiteralCannotMatchSymbolSubject() {
        String source = """
                pub routine main(): void {
                  const Symbol tag = #ready;
                  do match tag over
                    on "ready" -> { return; }
                    on _ -> { return; }
                  end
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
