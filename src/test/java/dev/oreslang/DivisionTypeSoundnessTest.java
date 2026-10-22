package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The static type of integer division must agree with the Double-producing guest runtime. */
final class DivisionTypeSoundnessTest {
    @Test
    void intOverIntCannotReturnIntEvenWhenExactlyDivisible() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc quotient(int a, int b): int {
                          return a / b;
                        }
                        """)));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
    }

    @Test
    void divisionCannotInitializeAnIntLocal() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(int a, int b): int {
                  val int quotient = a / b;
                  return quotient;
                }
                """)));
    }

    @Test
    void intOverIntAdmitsFloatReturnAndChainedFloatingArithmetic() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc quotient(int a, int b): float {
                  return a / b;
                }
                fnc add(int a, int b): float {
                  return a / b + 0.5;
                }
                """)));
    }

    @Test
    void actualGuestDivisionUsesFloatingResults() throws Exception {
        String program = """
                fnc quotient(int a, int b): float {
                  return a / b;
                }
                pub routine main(): void {
                  stdio.stdout.write(quotient(9, 2));
                  stdio.stdout.write("|");
                  stdio.stdout.write(quotient(-9, 2));
                  stdio.stdout.write("|");
                  stdio.stdout.write(quotient(8, 2));
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, program, "division-soundness.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(src);
        }
        assertEquals("4.5|-4.5|4.0", stdout.toString(StandardCharsets.UTF_8));
    }
}
