package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ArithmeticOverflowGuestErrorTest {
    private static String fixture(String name) throws Exception {
        return Files.readString(
                Path.of("tests", "fixtures", "numeric-v1", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void overflowEntersOrdinaryGuestCatch() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(
                    OresLanguage.ID, fixture("i64-overflow-catch.ores")).asLong());
        }
    }

    @Test
    void caughtOverflowHasNominalGuestIdentity() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(
                    OresLanguage.ID, fixture("i64-overflow-typed-catch.ores")).asLong());
        }
    }

    @Test
    void caughtOverflowIsAlsoBuiltinError() {
        String guest = """
                pub fnc main():int{
                  try {
                    return 9223372036854775807 + 1;
                  } catch (err) {
                    if err is type Error {
                      return 42;
                    } else {
                      return 1;
                    } fi
                  } finally {
                  }
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void everyCheckedI64SurfaceUsesTheSameGuestErrorHierarchy() {
        String[] expressions = {
                "9223372036854775807 + 1",
                "(-9223372036854775807 - 1) - 1",
                "3037000500 * 3037000500",
                "-(-9223372036854775807 - 1)",
                "Math.abs((-9223372036854775807 - 1))",
                "Math.neg((-9223372036854775807 - 1))"
        };

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            for (String expression : expressions) {
                String guest = """
                        pub fnc main():int{
                          try {
                            return %s;
                          } catch (err) {
                            if err is type ArithmeticOverflowError {
                              if err is type Error {
                                return 42;
                              } else {
                                return 2;
                              } fi
                            } else {
                              return 1;
                            } fi
                          } finally {
                          }
                        }
                        """.formatted(expression);
                assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong(),
                        "guest error identity drift for " + expression);
            }
        }
    }

    @Test
    void trapCanConsumeOrdinaryOverflowWithoutSeeingHostArithmeticException() {
        String guest = """
                pub fnc main():int{
                  val result = trap Math.abs(-9223372036854775808);
                  return result.is_none() ? 42 : 1;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void localOperandOverflowRetainsGuestIdentityAcrossRuntimeEvaluation() {
        // Do not qualify overflow only through literal constant folding.
        // Named local operands must still enter ordinary typed guest catch.
        String guest = """
                pub fnc main():int{
                  val int maximum = 9223372036854775807;
                  val int increment = 1;
                  try {
                    return maximum + increment;
                  } catch (err) {
                    if err is type ArithmeticOverflowError {
                      return 42;
                    } else {
                      return 1;
                    } fi
                  } finally {
                  }
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void uncaughtOverflowReportsGuestErrorIdentity() {
        String guest = "pub fnc main():int{return Math.neg(-9223372036854775808);}";
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, guest));
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains("ArithmeticOverflowError"),
                    failure::getMessage);
            assertFalse(failure.getMessage().contains("ORES_I64_OVERFLOW"),
                    failure::getMessage);
        }
    }
}
