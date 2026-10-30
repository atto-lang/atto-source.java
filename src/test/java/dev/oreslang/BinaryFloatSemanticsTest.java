package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Characterize Oreslang float as binary floating-point, not decimal arithmetic. */
final class BinaryFloatSemanticsTest {
    @Test
    void guestPreservesBinaryPrecisionSignedZeroAndNanRules() throws Exception {
        String guest = """
                pub routine main(): void {
                  val float binary_sum = 0.1 + 0.2;
                  val float negative_zero = -0.0;
                  val float negative_infinity = 1.0 / negative_zero;
                  val float nan = 0.0 / 0.0;

                  stdio.stdout.write(binary_sum == 0.3);
                  stdio.stdout.write("|");
                  stdio.stdout.write(negative_zero == 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(negative_infinity < 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(nan == nan);
                  return;
                }
                """;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "binary-float-semantics.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }

        assertEquals("false|true|true|false",
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}
