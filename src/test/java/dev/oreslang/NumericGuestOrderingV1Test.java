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
    @Test void signedIntegerAboveTwoToThe53rdOrdersExactlyAgainstBinaryFloat() {
        String source = """
                pub fnc main(): int {
                  val int exact = 9007199254740993;
                  val float rounded = 9007199254740992.0;
                  if exact > rounded then
                    return 42;
                  fi
                  return 1;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, source).asLong());
        }
    }

    @Test void infinityRemainsOrderedWithoutNaNComparability() {
        String source = """
                pub fnc main(): int {
                  val float infinity = 1.0 / 0.0;
                  val float nan = infinity / infinity;
                  if infinity > 1.0 && infinity >= 1.0 then
                    if nan < infinity || nan >= infinity then
                      return 1;
                    fi
                    return 42;
                  fi
                  return 2;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, source).asLong());
        }
    }

    @Test void numericEqualityMatchesExactOrderingAtThe53BitBoundary() {
        String source = """
                pub fnc main(): int {
                  val int exact = 9007199254740993;
                  val float rounded = 9007199254740992.0;
                  if exact == rounded || rounded == exact then
                    return 1;
                  fi
                  if exact != rounded && rounded != exact then
                    return 42;
                  fi
                  return 2;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, source).asLong());
        }
    }

    @Test void signedZeroAndNanEqualityFollowIeeeGuestSemantics() {
        String source = """
                pub fnc main(): int {
                  val float zero = 0.0;
                  val float negative_zero = -0.0;
                  val float nan = 0.0 / 0.0;
                  if zero != negative_zero || nan == nan then
                    return 1;
                  fi
                  if zero == negative_zero && nan != nan then
                    return 42;
                  fi
                  return 2;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, source).asLong());
        }
    }

}
