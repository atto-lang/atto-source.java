package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** int->float widening must materialize a binary64 carrier at every runtime boundary. */
final class FloatCarrierSemanticsTest {
    @Test
    void declaredFloatNeverFallsBackToCheckedIntegerArithmetic() throws Exception {
        String guest = """
                fnc plus_one(float value): float {
                  return value + 1;
                }

                fnc largest_as_float(): float {
                  return 9223372036854775807;
                }

                pub routine main(): void {
                  val float local = 9223372036854775807;
                  let float mutable = 1;
                  mutable = 9223372036854775807;

                  val float local_sum = local + 1;
                  val float mutable_sum = mutable + 1;
                  val float parameter_sum = plus_one(9223372036854775807);
                  val float returned = largest_as_float();

                  stdio.stdout.write(local_sum == local);
                  stdio.stdout.write("|");
                  stdio.stdout.write(mutable_sum == mutable);
                  stdio.stdout.write("|");
                  stdio.stdout.write(parameter_sum == returned);
                  return;
                }
                """;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "float-carrier.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }

        assertEquals("true|true|true",
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}
