package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.DeclaredEffectVerifier;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Implicit compiler-generated cooperation must have a declared suspend effect. */
final class AutomaticLoopEffectAdmissionTest {
    private static Map<String, String> loops() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("for", """
                for (let i = 0; i < 128; i = i + 1) do
                  val next = i + 1;
                done
                """);
        result.put("loop", """
                let i = 0;
                loop do
                  i = i + 1;
                  if i == 128; do break; fi
                done
                """);
        result.put("for-of", """
                for const value of arr[1, 2, 3] do
                  val next = value + 1;
                done
                """);
        result.put("destructuring for-of", """
                for const [value] of arr[[1], [2], [3]] do
                  val next = value + 1;
                done
                """);
        return result;
    }

    @TestFactory Stream<DynamicTest> missingSuspendEffectRejectedForEveryLoopShape() {
        return loops().entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String source = "fnc invalid(): void using io, alloc {\n"
                            + entry.getValue() + " return;\n}";
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source), entry.getKey());
                    var error = assertThrows(IllegalArgumentException.class,
                            () -> DeclaredEffectVerifier.verify(parsed), entry.getKey());
                    assertTrue(error.getMessage().contains("effect suspend"),
                            () -> entry.getKey() + ": " + error.getMessage());
                }));
    }

    @TestFactory Stream<DynamicTest> explicitSuspendEffectAcceptsPureLoopShapes() {
        return loops().entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String source = "fnc good(): void using suspend, alloc {\n"
                            + entry.getValue() + " return;\n}";
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source), entry.getKey());
                    assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(parsed), entry.getKey());
                    assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source), entry.getKey());
                }));
    }

    @Test void cooperativeLoopsRemainNonBlockingWhenNoBlockIsOnlyRequirement() {
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse("""
                fnc good(): void requires no_block {
                  let n = 0;
                  loop do
                    n = n + 1;
                    if n == 64; do break; fi
                  done
                  return;
                }
                """)));
    }

    @Test void inlineSealedStateCallbackRunsWithoutImplicitYieldEffect() {
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse("""
                define actor Counter as
                  state { value: int = 0; }
                  pub run(): void using state.read, state.write {
                    self.handle_state(critical |state| -> {
                      for (let i = 0; i < 32; i = i + 1) do
                        state.value = state.value + 1;
                      done
                    });
                    return;
                  }
                end
                """)));
    }
}
