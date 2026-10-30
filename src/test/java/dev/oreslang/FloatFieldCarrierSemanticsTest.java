package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Declared float class fields must retain the binary64 carrier after int widening. */
final class FloatFieldCarrierSemanticsTest {
    @Test
    void classFieldInitializationAndMutationPreserveBinary64() throws Exception {
        String guest = """
                define class FloatBox as
                  pub let float value = 0;

                  pub constructor(float initial) {
                    self.value = initial;
                  }

                  pub bump(self &mut FloatBox)(): float {
                    self.value = self.value + 1;
                    return self.value;
                  }

                  pub reset_from_int(self &mut FloatBox)(): void {
                    self.value = 9223372036854775807;
                    return;
                  }
                end

                pub routine main(): void {
                  let FloatBox box = new FloatBox(9223372036854775807);
                  val float before = box.value;
                  val float after = box.bump();
                  box.reset_from_int();
                  val float reset = box.value;
                  val float after_reset = box.bump();

                  stdio.stdout.write(after == before);
                  stdio.stdout.write("|");
                  stdio.stdout.write(after_reset == reset);
                  return;
                }
                """;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "float-field-carrier.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }

        assertEquals("true|true",
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}
