package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

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
