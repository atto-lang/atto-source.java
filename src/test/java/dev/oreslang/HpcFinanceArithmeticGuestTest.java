package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Independent REAL Oreslang guest arithmetic characterization for the HPC
 * finance scalar verification backlog. This deliberately does not copy any
 * private finance source tree or certify the finance repo's exact PR head.
 */
final class HpcFinanceArithmeticGuestTest {

    @Test
    void strictBinary64ExtremesAreObservableInAnOreslangGuest() throws Exception {
        String output = runOreslang("""
                pub routine main(): void {
                  val float large = 1.0e308;
                  val float overflow = large + large;
                  val float negative = 0.0 - large;
                  val float nan = overflow - overflow;
                  stdio.stdout.write(overflow > large);
                  stdio.stdout.write(":");
                  stdio.stdout.write((large - negative) > large);
                  stdio.stdout.write(":");
                  stdio.stdout.write(nan != nan);
                  stdio.stdout.write(":");
                  stdio.stdout.write((large - large) == 0.0);
                  return;
                }
                """);
        assertEquals("true:true:true:true", output);
    }

    @Test
    void weightedOppositeSignMeanExecutesThroughOreslangRuntime() throws Exception {
        String output = runOreslang("""
                fnc weighted_mean(Array<float> values, int count): float {
                  let result = 0.0;
                  let samples = 0.0;
                  for int i = 0; i < count; i++ {
                    samples = samples + 1.0;
                    val incoming = values[i];
                    if (incoming < 0.0 && result >= 0.0) ||
                       (incoming >= 0.0 && result < 0.0) {
                      result = result * ((samples - 1.0) / samples)
                        + incoming / samples;
                    } else {
                      result = result + (incoming - result) / samples;
                    fi
                  }
                  return result;
                }

                pub routine main(): void {
                  val float big = 1.0e308;
                  val float minus_big = 0.0 - big;
                  stdio.stdout.write(weighted_mean(arr[big, minus_big], 2) == 0.0);
                  stdio.stdout.write(":");
                  stdio.stdout.write(weighted_mean(arr[minus_big, big], 2) == 0.0);
                  stdio.stdout.write(":");
                  stdio.stdout.write(weighted_mean(arr[big, big], 2) == big);
                  stdio.stdout.write(":");
                  stdio.stdout.write(weighted_mean(arr[minus_big, minus_big], 2) == minus_big);
                  stdio.stdout.write(":");
                  stdio.stdout.write(weighted_mean(arr[1.0, 2.0, 3.0], 3) == 2.0);
                  return;
                }
                """);
        assertEquals("true:true:true:true:true", output);
    }

    private static String runOreslang(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, sourceText, "finance-independent.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
