package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Executes the exact imported rate-limit Oreslang source as an actual guest.
 * The fixture blob must match the source in ores-rate-limit/ores-rl-clients
 * PR #12; host-language reference calculations are not guest proof.
 */
final class RateLimitBackpressureGuestTest {
    private static String importedSource() throws Exception {
        try (InputStream stream = RateLimitBackpressureGuestTest.class
                .getResourceAsStream("/rate-limit/backpressure.ores")) {
            assertNotNull(stream, "missing checked-in Oreslang guest fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void importedOreslangModuleCompilesAndExecutesSigned64BoundaryVectors() throws Exception {
        String program = importedSource() + """
                pub routine main(): void {
                  if backpressure.conservative_window_interval_ms(60, 60000, 12, 18000) != 1500; do
                    stdio.stdout.write("fail-1");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(1000, 3600000, 441, 1900000) != 4309; do
                    stdio.stdout.write("fail-2");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(10000, 1000000, 10000, 10000) != 100; do
                    stdio.stdout.write("fail-3");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(100, 1000, 0, 2500) != 2500; do
                    stdio.stdout.write("fail-4");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(2, 2678400000, 2, 2678400000) != 1339200000; do
                    stdio.stdout.write("fail-5");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(1, 2678400000, 1, 2678400000) != 2678400000; do
                    stdio.stdout.write("fail-6");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(0, 1000, 0, 1) != -1; do
                    stdio.stdout.write("fail-7");
                    return;
                  fi
                  if backpressure.conservative_window_interval_ms(100, 1000, 101, 100) != -1; do
                    stdio.stdout.write("fail-8");
                    return;
                  fi
                  if backpressure.required_delay_ms(0, 4309) != 0; do
                    stdio.stdout.write("fail-9");
                    return;
                  fi
                  if backpressure.required_delay_ms(-1, 4309) != 4309; do
                    stdio.stdout.write("fail-10");
                    return;
                  fi
                  if backpressure.next_not_before_ms(5000, 1000, 999999, 0) != 6250; do
                    stdio.stdout.write("fail-11");
                    return;
                  fi
                  if backpressure.next_not_before_ms(9007199254740891, 1000, 0, 0) != 9007199254740991; do
                    stdio.stdout.write("fail-12");
                    return;
                  fi
                  if backpressure.next_not_before_ms(0, 2678400000, 2678400000, 0) != 3348000000; do
                    stdio.stdout.write("fail-13");
                    return;
                  fi
                  if backpressure.next_not_before_ms(1, 2678400000, 0, 9007199254740990) != 9007199254740990; do
                    stdio.stdout.write("fail-14");
                    return;
                  fi
                  if backpressure.tightened_concurrency(8, 2) != 2; do
                    stdio.stdout.write("fail-15");
                    return;
                  fi
                  if backpressure.tightened_concurrency(2, 6) != 2; do
                    stdio.stdout.write("fail-16");
                    return;
                  fi
                  if backpressure.tightened_concurrency(8, 0) != -1; do
                    stdio.stdout.write("fail-17");
                    return;
                  fi
                  stdio.stdout.write("ores-rate-limit-java-guest-ok");
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, "rate-limit-backpressure.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("ores-rate-limit-java-guest-ok", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void integerDivisionCannotSilentlyBecomeAnIntegerQuotient() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(int numerator, int denominator): int {
                  return numerator / denominator;
                }
                """)));
    }
}
