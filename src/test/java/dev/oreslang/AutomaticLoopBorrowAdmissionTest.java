package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-preemption is an implicit suspension boundary: a lexical loan cannot
 * flow through the loop's scheduling backedge even without rt cooperate.
 */
final class AutomaticLoopBorrowAdmissionTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;
    private static final String ERROR = "automatic loop cooperation cannot retain a live ordinary borrow";

    @TestFactory Stream<DynamicTest> rejectsPersistentLoansAcrossAllLoopBackedges() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("classic for", """
                for (let i = 0; i < 80; i = i + 1) do
                  val n = view.value;
                done
                """);
        cases.put("loop", """
                let i = 0;
                loop do
                  i = i + 1;
                  if i == 80; do break; fi
                done
                """);
        cases.put("for of", """
                for const item of arr[1, 2, 3] do
                  val n = view.value + item;
                done
                """);
        cases.put("for of destructure", """
                for const [item] of arr[[1], [2], [3]] do
                  val n = view.value + item;
                done
                """);
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String source = BOX + """
                            fnc bad(): void {
                              val Box box = new Box();
                              val view = rt borrow box;
                            """ + entry.getValue() + """
                              return;
                            }
                            """;
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                            entry.getKey() + " must first parse");
                    var failure = assertThrows(IllegalArgumentException.class,
                            () -> OwnershipChecker.check(parsed), entry.getKey());
                    assertTrue(failure.getMessage().contains(ERROR),
                            () -> entry.getKey() + ": " + failure.getMessage());
                }));
    }

    @org.junit.jupiter.api.Test void nestedBorrowTypeInParameterCannotCrossAutomaticLoopHandoff() {
        String source = BOX + """
                fnc bad(Option<borrow Box> nested): void {
                  for (let i = 0; i < 80; i = i + 1) do
                    val next = i + 1;
                  done
                  return;
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(error.getMessage().contains(ERROR), error.getMessage());
    }

    @TestFactory Stream<DynamicTest> directBorrowedIterableIsNotAnOwnedLoopCapability() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("for-of borrowed input", """
                for const item of rt borrow values do
                  val next = item + 1;
                done
                """);
        cases.put("destructuring for-of borrowed input", """
                for const [item] of rt borrow values do
                  val next = item + 1;
                done
                """);
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String collection = entry.getKey().startsWith("destructuring")
                            ? "arr[[1], [2], [3]]" : "arr[1, 2, 3]";
                    String source = "fnc bad(): void { val values = " + collection + ";\n"
                            + entry.getValue() + " return;\n}";
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source), entry.getKey());
                    var error = assertThrows(IllegalArgumentException.class,
                            () -> OwnershipChecker.check(parsed), entry.getKey());
                    assertTrue(error.getMessage().contains("borrowed iterable operand"),
                            () -> entry.getKey() + ": " + error.getMessage());
                }));
    }

    @TestFactory Stream<DynamicTest> ownedIterableRemainsLegal() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("for-of owned input", """
                for const item of values do
                  val next = item + 1;
                done
                """);
        cases.put("destructuring for-of owned input", """
                for const [item] of values do
                  val next = item + 1;
                done
                """);
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String collection = entry.getKey().startsWith("destructuring")
                            ? "arr[[1], [2], [3]]" : "arr[1, 2, 3]";
                    String source = "fnc good(): void { val values = " + collection + ";\n"
                            + entry.getValue() + " return;\n}";
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source), entry.getKey());
                    assertDoesNotThrow(() -> OwnershipChecker.check(parsed), entry.getKey());
                    assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source), entry.getKey());
                }));
    }

    @TestFactory Stream<DynamicTest> loansRetiredBeforeBackedgeRemainLegal() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("per iteration lexical loan", """
                fnc good(): int {
                  val Box box = new Box();
                  let total = 0;
                  for (let i = 0; i < 64; i = i + 1) do
                    val view = rt borrow box;
                    total = total + view.value;
                  done
                  return total;
                }
                """);
        cases.put("call-scoped borrow", """
                fnc read(borrow Box box): int { return box.value; }
                fnc good(): int {
                  val Box box = new Box();
                  let total = 0;
                  for (let i = 0; i < 64; i = i + 1) do
                    total = total + read(rt borrow box);
                  done
                  return total;
                }
                """);
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    String source = BOX + entry.getValue();
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                            entry.getKey() + " must first parse");
                    assertDoesNotThrow(() -> OwnershipChecker.check(parsed), entry.getKey());
                    assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source), entry.getKey());
                }));
    }
}
