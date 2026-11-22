package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Execute IEEE ordered-comparison corner cases as real Atto guest source. */
final class NumericGuestOrderingV1Test {
    @Test void nanIsUnorderedAndSignedZerosAreEqualInGuestPredicates() {
        String source = """
                pub fnc main(): int {
                  val float nan = 0.0 / 0.0;
                  if nan < 1.0 || nan <= 1.0 || nan > 1.0 || nan >= 1.0 then
                    return 1;
                  fi
                  val float negative_zero = -0.0;
                  if negative_zero < 0.0 || negative_zero > 0.0 then
                    return 2;
                  fi
                  if negative_zero <= 0.0 && negative_zero >= 0.0 then
                    return 42;
                  fi
                  return 3;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, source).asLong());
        }
    }
}
