package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Distinguishes parser-only, individual checker and complete compiler admission.
 * A parser error cannot satisfy any negative ownership/effect test.
 */
final class IntegratedOwnershipAdmissionGateTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    @TestFactory Stream<DynamicTest> completeCompilerRejectsCrossFeatureEscapes() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("direct actor-state read", """
                define actor Worker as
                  state { count: int = 0; }
                  pub probe(): int { return self.state.count; }
                end
                """);
        cases.put("state loan with cooperation", """
                define actor Worker as
                  state { count: int = 0; }
                  pub probe(): void using state.read, suspend {
                    self.handle_state(critical |s| -> {
                      rt cooperate;
                      val n = s.count;
                    });
                    return;
                  }
                end
                """);
        cases.put("state loan with blocking channel read", """
                define actor Worker as
                  state { count: int = 0; }
                  pub probe(Channel<int> ch): void using state.read, suspend, block {
                    self.handle_state(critical |s| -> {
                      val n = readch ch;
                      val m = s.count;
                    });
                    return;
                  }
                end
                """);
        cases.put("borrowing becomes apparent owned cast", BOX + """
                fnc bad(): void {
                  let Box box = new Box();
                  val Box fake = (rt borrow box) as Box;
                  return;
                }
                """);
        cases.put("owned/borrowed branch merge", BOX + """
                fnc bad(): void {
                  let Box box = new Box();
                  val fake = true ? box : rt borrow box;
                  return;
                }
                """);
        cases.put("blocked channel under independent no_block", """
                fnc bad(Channel<int> channel): void requires no_block {
                  val item = readch channel;
                  return;
                }
                """);
        cases.put("borrowed yield-star payload", BOX + """
                fnc source(): Iterator<borrow Box> { return null; }
                gen* fnc bad(): Iterator<borrow Box> {
                  yield* source();
                  return;
                }
                """);

        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    var parsed = assertDoesNotThrow(
                            () -> Parser.parse(entry.getValue()), entry.getKey() + " must parse");
                    assertThrows(IllegalArgumentException.class,
                            () -> OresCompiler.analyze(parsed), entry.getKey());
                    assertThrows(IllegalArgumentException.class,
                            () -> OresCompiler.parseAndTypeCheck(entry.getValue()), entry.getKey());
                }));
    }

    @TestFactory Stream<DynamicTest> completeCompilerAcceptsOwnedAndScopedStateControls() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("scalar no_alloc and no_block", """
                fnc good(): int requires no_alloc, no_block {
                  return 42;
                }
                """);
        cases.put("sealed state read-modify-write", """
                define actor Worker as
                  state { count: int = 0; }
                  pub increment(): int using state.read, state.write {
                    return self.handle_state(critical |s| -> {
                      s.count = s.count + 1;
                      return s.count;
                    });
                  }
                end
                """);
        cases.put("owned cast and copy/share primitives", BOX + """
                fnc good(): void {
                  val Box box = new Box();
                  val Box casted = box as Box;
                  val int a = 7;
                  val b = rt copy a;
                  val c = rt share a;
                  return;
                }
                """);
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    var parsed = assertDoesNotThrow(
                            () -> Parser.parse(entry.getValue()), entry.getKey() + " must parse");
                    assertDoesNotThrow(() -> TypeChecker.check(parsed), entry.getKey());
                    assertDoesNotThrow(
                            () -> OresCompiler.parseAndTypeCheck(entry.getValue()), entry.getKey());
                }));
    }
}
