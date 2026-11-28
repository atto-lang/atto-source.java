package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NestedFutureFlattenArityTest {
    @Test
    void flattenMustNotAcceptArguments() {
        String source = """
                async fnc value(): int {
                  return 42;
                }
                pub fnc main(): void {
                  val Future<Future<int>> nested = value().map(|int item| -> {
                    return value();
                  });
                  val future = nested.flatten(7);
                  return;
                }
                """;
        String valid = source.replace("nested.flatten(7)", "nested.flatten()");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(valid),
                "positive control: a nested Future must accept zero-argument flatten");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("flatten")
                        && (failure.getMessage().contains("argument")
                            || failure.getMessage().contains("arity")),
                failure.getMessage());
    }
}
