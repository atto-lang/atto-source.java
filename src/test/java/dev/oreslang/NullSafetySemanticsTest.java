package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class NullSafetySemanticsTest {

    @Test
    void nullIsNeitherAValueNorATypeIncludingInsideOption() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  val value = null;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(null value): void {
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): null {
                  return;
                }
                """));

        IllegalArgumentException optionalNull = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(Option<null> value): void {
                          return;
                        }
                        """));
        assertTrue(optionalNull.getMessage().contains("null is not a type"));
    }

    @Test
    void voidCannotBecomeAStoredOrSumPayloadValue() {
        IllegalArgumentException stored = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc side_effect(): void {
                          return;
                        }

                        fnc bad(): void {
                          val captured = side_effect();
                          return;
                        }
                        """)));
        assertTrue(stored.getMessage().contains("void is not a storable value"));

        IllegalArgumentException someVoid = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc side_effect(): void {
                          return;
                        }

                        fnc bad(): void {
                          val maybe = Some(side_effect());
                          return;
                        }
                        """)));
        assertTrue(someVoid.getMessage().contains("non-void payload"));

        IllegalArgumentException resultVoid = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Result<void, String> value): void {
                          return;
                        }
                        """)));
        assertTrue(resultVoid.getMessage().contains("Result payloads"));
    }

    @Test
    void optionAndResultSwitchesAreExhaustiveAndRejectWrongVariants() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc option_value(Option<int> value): int {
                  switch value {
                    Some(val number) -> { return number; }
                    None -> { return 0; }
                  }
                }

                fnc result_value(Result<int, String> value): int {
                  switch value {
                    Ok(val number) -> { return number; }
                    Err(val message) -> { return 0; }
                  }
                }
                """)));

        IllegalArgumentException missingNone = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> value): int {
                          switch value {
                            Some(val number) -> { return number; }
                          }
                          return 0;
                        }
                        """)));
        assertTrue(missingNone.getMessage().contains("non-exhaustive Option"));
        assertTrue(missingNone.getMessage().contains("None"));

        IllegalArgumentException guardedSome = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> value): int {
                          switch value {
                            Some(val number) when number > 0 -> { return number; }
                            None -> { return 0; }
                          }
                          return 0;
                        }
                        """)));
        assertTrue(guardedSome.getMessage().contains("non-exhaustive Option"));

        IllegalArgumentException wrongVariant = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Option<int> value): int {
                          switch value {
                            Ok(val number) -> { return number; }
                            None -> { return 0; }
                          }
                          return 0;
                        }
                        """)));
        assertTrue(wrongVariant.getMessage().contains("Option switch patterns"));
    }

    @Test
    void boolSwitchRequiresBothCasesWithoutCatchAll() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(bool value): int {
                  switch value {
                    true -> { return 1; }
                    false -> { return 0; }
                  }
                }
                """)));

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(bool value): int {
                          switch value {
                            true -> { return 1; }
                          }
                          return 0;
                        }
                        """)));
        assertTrue(missing.getMessage().contains("non-exhaustive bool"));
    }

    @Test
    void runtimeMatchesSomeAndNoneWithoutAHiddenNullPayload() throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): int {
                  val Option<int> present = Some(41);
                  switch present {
                    Some(val value) -> { return value + 1; }
                    None -> { return 0; }
                  }
                }
                """, "nonnull-option-pattern.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            assertEquals(42L, context.eval(source).asLong());
        }
    }

    @Test
    void hostNullReturnsBecomeNoneAndNoneIsTheOnlyWayToPassHostNull() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                Set.of("java.util.HashMap", "java.util.Objects")).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import class HashMap as JHashMap from "java:java.util.HashMap";
                    import class Objects as JObjects from "java:java.util.Objects";

                    pub fnc main(): bool {
                      val values = new JHashMap();
                      val missing = values.get("missing");
                      if !missing.is_none(); do
                        return false;
                      fi
                      return JObjects.isNull(None);
                    }
                    """);
            assertTrue(value.asBoolean());
        }
    }

    @Test
    void voidAtPolyglotBoundaryIsNotNull() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            Value value = context.eval(OresLanguage.ID, """
                    pub fnc main(): void {
                      return;
                    }
                    """);
            assertFalse(value.isNull());
            assertEquals("void", value.toString());
        }
    }
}
