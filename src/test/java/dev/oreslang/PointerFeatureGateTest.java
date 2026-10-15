package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Prevent a partial, unsafe Pointer<T> implementation from entering current main. */
final class PointerFeatureGateTest {

    @Test
    void reservedPointerIntrinsicsHaveExplicitFailClosedDiagnostics() {
        for (String operation : new String[] {"ref", "unref", "deref"}) {
            for (String operand : new String[] {"value", "(value)"}) {
                String source = """
                        pub routine main(): void {
                          val int value = 7;
                          val candidate = rt %s %s;
                          return;
                        }
                        """.formatted(operation, operand);
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source));
                assertTrue(failure.getMessage().contains("rt " + operation),
                        failure::getMessage);
                assertTrue(failure.getMessage().contains("not enabled"),
                        failure::getMessage);
            }
        }
    }

    @Test
    void reservedPointerNameCannotBeForgedByUserClass() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("define class Pointer as end")));
        assertTrue(failure.getMessage().contains("reserved compiler-owned type"),
                failure::getMessage);
    }

    @Test
    void nominalPointerSignatureCannotBypassAdmissionGate() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "fnc leak(Pointer<int> fake): void { return; }")));
        assertTrue(failure.getMessage().contains("Pointer<T>"), failure::getMessage);
    }

    @Test
    void existingCopyAndTakePathsRemainAdmitted() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val int original = 7;
                  val int copied = rt copy original;
                  val int borrowed = 4;
                  val int moved = rt take copied;
                  stdio.stdout.write(borrowed + moved);
                  return;
                }
                """)));
    }
}
