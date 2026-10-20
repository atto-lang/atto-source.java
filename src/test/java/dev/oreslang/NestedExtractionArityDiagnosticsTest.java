package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Distinguish intrinsic argument errors from invalid receiver types. */
final class NestedExtractionArityDiagnosticsTest {

    @Test
    void optionIntrinsicsAcceptZeroArgumentsAndRejectExtraValues() {
        String prelude = """
                pub fnc main(): void {
                  val Option<Option<int>> value = Some(Some(42));
                """;
        String ending = """
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                prelude + """
                  val Option<int> joined = value.flatten();
                  val int extracted = value.unwrap_all();
                """ + ending));

        requireDiagnostic(prelude + """
                  val answer = value.flatten(7);
                """ + ending, "Option.flatten expects no arguments");

        requireDiagnostic(prelude + """
                  val answer = value.unwrap_all(7);
                """ + ending, "Option.unwrap_all expects no arguments");
    }

    private static void requireDiagnostic(String source, String expected) {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(rejected.getMessage().contains(expected),
                () -> "expected intrinsic diagnostic: " + expected
                        + "; actual: " + rejected.getMessage());
    }
}
