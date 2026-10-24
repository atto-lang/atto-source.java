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
 * Narrow execution-refinement test for the shared Rust scalar-v1 corpus.
 *
 * Rust reports three *abstract* compute units for 40+2. This test verifies
 * the observable i64 result on the Java/Graal guest, not that its compiler
 * exports identical effect-IR, uses exactly three machine instructions,
 * or is hard-real-time certified.
 */
final class RustScalarGuestParityTest {

    @Test
    void javaGraalExecutesScalarV1AsFortyTwo() throws Exception {
        String expression = "40 + 2";
        String source = "pub fnc main():void { stdio.stdout.write("
                + expression + "); return; }\n";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        Source guest = Source.newBuilder(OresLanguage.ID, source, "rust-scalar-v1-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void badI64LiteralCannotPassScalarAdmission() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(
                        "pub fnc main():int{return 9223372036854775808;}")));
    }
}
