package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class NestedFutureFlattenArityTest {
    @Test
    void flattenMustNotAcceptArguments() {
        String source = """
                async fnc value(): int {
                  return 42;
                }
                pub fnc main(): void {
                  val future = value().flatten(7);
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }
}
