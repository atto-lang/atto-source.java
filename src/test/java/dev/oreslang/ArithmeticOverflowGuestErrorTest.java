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
                    }
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
                              }
                            } else {
                              return 1;
                            }
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
                  return result.is_err() ? 42 : 1;
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
                    }
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
    void checkedOverflowCrossesFunctionBoundaryWithNominalGuestType() {
        // A return from another guest function must preserve the guest error
        // effect instead of leaking a Java ArithmeticException or trapping.
        for (String callee : new String[] {
                "return maximum + 1;",
                "return Math.neg(maximum);"
        }) {
            long argument = callee.contains("neg(")
                    ? Long.MIN_VALUE : Long.MAX_VALUE;
            String guest = """
                    pub fnc checked(int maximum): int {
                      %s
                    }
                    pub fnc main(): int {
                      try {
                        return checked(%d);
                      } catch (err) {
                        if err is type ArithmeticOverflowError {
                          return 42;
                        } else {
                          return 1;
                        }
                      } finally {
                      }
                    }
                    """.formatted(callee, argument);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong(),
                        "failed typed guest catch after cross-function " + callee);
            }
        }
    }

    @Test
    void everyCheckedOperationPreservesGuestErrorAcrossFunctionBoundary() {
        // Runtime arguments prevent proving the contract only through literal
        // constant folding in the caller or a single Math intrinsic.
        String[][] cases = {
                {"add", "return lhs + rhs;", "9223372036854775807", "1"},
                {"subtract", "return lhs - rhs;", "-9223372036854775808", "1"},
                {"multiply", "return lhs * rhs;", "3037000500", "3037000500"},
                {"negate", "return -lhs;", "-9223372036854775808", "0"},
                {"Math.abs", "return Math.abs(lhs);", "-9223372036854775808", "0"},
                {"Math.neg", "return Math.neg(lhs);", "-9223372036854775808", "0"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc checked(int lhs, int rhs): int {
                      %s
                    }
                    pub fnc main(): int {
                      try {
                        return checked(%s, %s);
                      } catch (err) {
                        if err is type ArithmeticOverflowError {
                          if err is type Error {
                            return 42;
                          } else {
                            return 2;
                          }
                        } else {
                          return 1;
                        }
                      } finally {
                      }
                    }
                    """.formatted(testCase[1], testCase[2], testCase[3]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong(),
                        "guest overflow ABI lost across " + testCase[0] + " call");
            }
        }
    }

    @Test
    void safeCheckedCallWithRuntimeLocalsSkipsGuestCatch() {
        // The normal edge must remain distinct from checked overflow,
        // including across a guest function boundary with local operands.
        String guest = """
                pub fnc checked(int lhs, int rhs): int {
                  return lhs + rhs;
                }
                pub fnc main(): int {
                  val int lhs = 40;
                  val int rhs = 2;
                  try {
                    return checked(lhs, rhs);
                  } catch (err) {
                    return 1;
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
    void nominalCatchCanContinueWithCheckedArithmeticAfterOverflow() {
        // The caught error must not leave a sticky pending effect that
        // contaminates subsequent checked Math calls in another guest fnc.
        String guest = """
                pub fnc checked_add(int lhs, int rhs): int {
                  return lhs + rhs;
                }
                pub fnc safe_tail(int value): int {
                  return Math.abs(value) + 2;
                }
                pub fnc main(): int {
                  try {
                    return checked_add(9223372036854775807, 1);
                  } catch (err) {
                    if err is type ArithmeticOverflowError {
                      return safe_tail(-40);
                    } else {
                      return 1;
                    }
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
