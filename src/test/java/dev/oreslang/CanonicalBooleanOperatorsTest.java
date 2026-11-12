package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalBooleanOperatorsTest {
    @Test
    void symbolicAndUppercaseAliasesSharePrecedenceAndXor() throws Exception {
        assertEquals("false\ntrue\nfalse\ntrue\ntrue\nfalse\n", run("""
                pub fnc main(): void {
                  stdio.println(false || true && false);
                  stdio.println(true ^^ true && false);
                  stdio.println(true ^^ true);
                  stdio.println(true OR false AND false);
                  stdio.println(true XOR false AND false OR true);
                  stdio.println((true OR false) AND false);
                  return;
                }
                """));
    }

    @Test
    void shortCircuitingAndEagerXorAreObservable() throws Exception {
        assertEquals("1\nfalse\n3\ntrue\n5\n6\ntrue\n7\n8\ntrue\n", run("""
                fnc mark(bool value, int id): bool {
                  stdio.println(id);
                  return value;
                }
                pub fnc main(): void {
                  stdio.println(mark(false, 1) && mark(true, 2));
                  stdio.println(mark(true, 3) OR mark(false, 4));
                  stdio.println(mark(false, 5) ^^ mark(true, 6));
                  stdio.println(mark(true, 7) XOR mark(true, 8));
                  return;
                }
                """));
    }

    @Test
    void logicalOperatorsRejectNonBooleanOperands() {
        for (String expression : new String[] {
                "true && 1", "false OR 2", "true ^^ \"s\"", "false XOR 7"
        }) {
            String source = "pub fnc main(): void { val bad = " + expression + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), expression);
        }
    }

    @Test
    void xorsDoNotRewriteBitwiseCaretOrSingleAmp() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  stdio.println(true ^ false);
                  return;
                }
                """)));
    }

    private static String run(String source) throws Exception {
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "canonical-boolean.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
