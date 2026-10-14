package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Switch cases must share the same bounded scalar-constant proof as final const.
 * They must not gain compile-time authority from an arbitrary unary/binary AST.
 */
final class SwitchConstantProofHardeningTest {
    private static String switchProgram(String constant) {
        return """
                pub fnc main(): void {
                  val int subject = 3;
                  switch subject
                    case %s -> { }
                    default -> { }
                  end
                  return;
                }
                """.formatted(constant);
    }

    @Test
    void acceptsPureArithmeticSwitchConstants() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(switchProgram("1 + 2"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(switchProgram("(2 * 3) - 3"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(switchProgram("(1 << 1) | 1"))));
    }

    @Test
    void rejectsSemanticallyIdenticalScalarCasesWithDifferentSyntax() {
        for (String[] pair : new String[][]{
                {"1 + 2", "3"},
                {"7 - 4", "3"},
                {"3", "1 | 2"}
        }) {
            String source = """
                    pub fnc main(): void {
                      val int subject = 3;
                      switch subject
                        case %s -> { }
                        case %s -> { }
                        default -> { }
                      end
                      return;
                    }
                    """.formatted(pair[0], pair[1]);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    source);
            assertTrue(failure.getMessage().contains("duplicate switch case"),
                    failure.getMessage());
        }
    }

    @Test
    void mixedIntegerAndFloatCasesOverlapUnderRuntimeValueEquality() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val float value = 1.0;
                          switch value
                            case 1 -> { }
                            case 1.0 -> { }
                            default -> { }
                          end
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("duplicate switch case"),
                failure.getMessage());
    }

    @Test
    void distinctLargeIntegersRemainDistinctDespiteDoubleRounding() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val int value = 9007199254740992;
                  switch value
                    case 9007199254740992 -> { }
                    case 9007199254740993 -> { }
                    default -> { }
                  end
                  return;
                }
                """)));
    }

    @Test
    void negativeAndPositiveZeroFloatCasesOverlap() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val float value = 0.0;
                          switch value
                            case 0.0 -> { }
                            case -0.0 -> { }
                            default -> { }
                          end
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("duplicate switch case"),
                failure.getMessage());
    }

    @Test
    void resumableSwitchUsesOreslangNumericEqualityNotJavaBoxEquality() throws Exception {
        String source = """
                pub fnc main(): void {
                  val float value = 1.0;
                  switch value
                    case 1 -> { stdio.stdout.write("matched"); }
                    default -> { stdio.stdout.write("missed"); }
                  end
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "switch-scalar-equality.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("matched", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsupportedOperationsAndOverflowBeforeRuntime() {
        for (String expression : new String[]{
                "1 / 0",
                "1 % 0",
                "9223372036854775807 + 1",
                "1 << -1",
                "&3",
                "3 + calculate()"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(switchProgram(expression))),
                    expression);
            assertTrue(failure.getMessage().contains("compile-time constant"),
                    expression + ": " + failure.getMessage());
        }
    }
}
