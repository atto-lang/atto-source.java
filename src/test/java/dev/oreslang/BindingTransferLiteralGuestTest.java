package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Literal-only pilot. This is not evidence of named-source ownership or Native Image. */
final class BindingTransferLiteralGuestTest {
    private static String program(String body) {
        return "pub fnc main(): int { " + body + " }";
    }

    @Test
    void literalTransferRunsActualGuest() throws Exception {
        for (String body : new String[]{
                "let a <- 42; return a;",
                "let int a <- 42; return a;",
                "let a = 0; set a <- 42; return a;",
                "let a <- true; return if a then 42 else 0 fi;",
                "let a = 0; set a = 42; return a;"}) {
            String source = program(body);
            assertNotNull(OresCompiler.parseAndTypeCheck(source), body);
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(Source.newBuilder(OresLanguage.ID, source,
                        "binding-transfer-literal.atto").build()).asLong(), body);
            }
        }
    }

    @Test
    void forbiddenAndUnprovedTransfersFailClosed() {
        for (String body : new String[]{
                "const a <- 42; return a;",
                "val a <- 42; return a;",
                "int a <- 42; return a;",
                "let a = 0; set! a <- 42; return a;",
                "set a <- 42; return 0;",
                "const a = 0; set a <- 42; return a;",
                "let a = 0; set a <- true; return a;",
                "let b = 42; let a <- b; return a;",
                "let a <- 21 + 21; return a;",
                "let a • 42; return a;",
                "let a < - 42; return a;"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program(body)), body);
        }
    }
}
