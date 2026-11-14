package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoCollectionMemberMethodTest {
    @TempDir Path temp;

    private String run(String source, String file) throws Exception {
        Path entry = temp.resolve(file);
        Files.writeString(entry, source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals("", err.toString(StandardCharsets.UTF_8),
                "native Atto collection guest unexpectedly wrote stderr");
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
    }

    @Test
    void arrayListAndVectorMethodChainsExecuteNativeAttoCore() throws Exception {
        for (String kind : new String[] {"Array", "List", "Vector"}) {
            String allocator = kind; // exercise each real guest constructor
            String source = """
                    pub fnc main(): void {
                      let mut %s<int> input = new %s<int>();
                      input.add(1);
                      input.add(2);
                      input.add(3);
                      val int result = input
                          .map(|int x| -> int { return x * 2; })
                          .filter(|int x| -> bool { return x > 2; })
                          .reduce(0, |int sum, int x| -> int { return sum + x; });
                      stdio.println(result);
                      stdio.println(input.size);
                      stdio.println(input.get(0));
                      return;
                    }
                    """.formatted(kind, allocator);
            assertEquals("10\n3\n1",
                    run(source, "native-methods-" + kind.toLowerCase(Locale.ROOT) + ".atto"),
                    kind);
        }
    }

    @Test
    void literalArrayUsesTheSameNativeGuestMethodPath() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Array<int> items = [1, 2, 3];
                  val int total = items
                      .map(|int n| -> int { return n + 1; })
                      .filter(|int n| -> bool { return n > 2; })
                      .reduce(0, |int sum, int n| -> int { return sum + n; });
                  stdio.println(total);
                  return;
                }
                """;
        assertEquals("7", run(source, "literal-array-methods.atto"));
    }

    @Test
    void memberCallbacksCannotMutateTheirActiveSourceReadLoan() throws Exception {
        String source = """
                pub fnc main(): void {
                  let mut Array<int> items = new Array<int>();
                  items.add(1);
                  val mapped = items.map(|int n| -> int {
                    items.set(0, n + 1);
                    return n;
                  });
                  stdio.println(mapped.size);
                  return;
                }
                """;
        Path entry = temp.resolve("no-mutating-active-read-loan.atto");
        Files.writeString(entry, source);
        assertThrows(RuntimeException.class, () ->
                LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test
    void mappingElementTypeAndReducingDifferentAccumulatorType() throws Exception {
        String source = """
                pub fnc main(): void {
                  let mut Array<int> numbers = new Array<int>();
                  numbers.add(1);
                  numbers.add(2);
                  numbers.add(3);
                  val int count = numbers
                      .map(|int x| -> bool { return x > 1; })
                      .filter(|bool enabled| -> bool { return enabled; })
                      .reduce(0, |int total, bool enabled| -> int {
                        if enabled then
                          return total + 1;
                        fi
                        return total;
                      });
                  val int leftFold = numbers.reduce(100,
                      |int total, int n| -> int { return total - n; });
                  stdio.println(count);
                  stdio.println(leftFold);
                  return;
                }
                """;
        assertEquals("2\n94", run(source, "native-map-bool-and-left-fold.atto"));
    }

    @Test
    void emptyInputDoesNotInvokeCallbacksAndReturnsInitialAccumulator() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Array<int> values = new Array<int>();
                  val int result = values
                      .map(|int n| -> int { return n + 1; })
                      .filter(|int n| -> bool { return n > 0; })
                      .reduce(47, |int sum, int n| -> int { return sum + n; });
                  stdio.println(result);
                  return;
                }
                """;
        assertEquals("47", run(source, "native-empty-methods.atto"));
    }

    private void mustReject(String code) throws Exception {
        Path entry = temp.resolve("invalid-" + Integer.toUnsignedString(code.hashCode()) + ".atto");
        Files.writeString(entry, code);
        RuntimeException denied = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.validate(entry),
                "unsafe/mistyped native collection callback was accepted");
        assertNotNull(denied.getMessage());
    }

    @Test
    void filterRequiresBooleanAndReduceRequiresTwoCallbackParameters() throws Exception {
        mustReject("""
                pub fnc main(): void {
                  val Array<int> items = new Array<int>();
                  val bad = items.filter(|int x| -> int { return x; });
                  return;
                }
                """);
        mustReject("""
                pub fnc main(): void {
                  val Array<int> items = new Array<int>();
                  val bad = items.reduce(0, |int x| -> int { return x; });
                  return;
                }
                """);
    }

    @Test
    void polymorphicMethodExtractionAndExtraArgumentsFailClosed() throws Exception {
        mustReject("""
                pub fnc main(): void {
                  val Array<int> items = new Array<int>();
                  val escaped = items.map;
                  return;
                }
                """);
        mustReject("""
                pub fnc main(): void {
                  val Array<int> items = new Array<int>();
                  val bad = items.map(|int x| -> int { return x; }, 42);
                  return;
                }
                """);
    }
}
