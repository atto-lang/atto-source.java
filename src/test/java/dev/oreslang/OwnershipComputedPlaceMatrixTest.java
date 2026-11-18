package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fifteen parse-positive ownership-semantic negatives. These deliberately
 * call the production OwnershipChecker, not a source regex or mock checker.
 * E_TAKE_UNTRACKED_PLACE cannot be counted when the parser itself rejects.
 * These are provisional until exact-head JUnit actually executes.
 */
final class OwnershipComputedPlaceMatrixTest {
    private record Case(String name, String expression) {}

    private static final List<Case> CASES = List.of(
            new Case("add literal", "first + 1"),
            new Case("subtract literal", "first - 1"),
            new Case("multiply literal", "first * 2"),
            new Case("divide literal", "first / 2"),
            new Case("add named value", "first + second"),
            new Case("subtract named value", "first - second"),
            new Case("multiply named value", "first * second"),
            new Case("divide named value", "first / second"),
            new Case("negate owned value", "-first"),
            new Case("negate another value", "-second"),
            new Case("negated arithmetic", "-(first + second)"),
            new Case("nested sum", "(first + second) + 1"),
            new Case("nested subtraction", "(first - second) - 1"),
            new Case("arithmetic as left operand", "(first + 1) * second"),
            new Case("arithmetic as right operand", "first * (second + 1)")
    );

    @TestFactory
    Stream<DynamicTest> computedTransferNeverPretendsToInvalidateAnOwner() {
        assertEquals(15, CASES.size());
        return CASES.stream().map(c -> DynamicTest.dynamicTest(
                "computed-place: " + c.name(), () -> {
                    String source = """
                            fnc invalid(): int {
                              val int first = 41;
                              val int second = 2;
                              val int stolen = rt take (%s);
                              return stolen;
                            }
                            """.formatted(c.expression());
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                            "Parser admission required before semantic denial: " + c.name());
                    var failure = assertThrows(IllegalArgumentException.class,
                            () -> OwnershipChecker.check(parsed),
                            "Production ownership checker must reject computed transfer: " + c.name());
                    assertTrue(failure.getMessage().contains("E_TAKE_UNTRACKED_PLACE"),
                            "Expected ownership semantic diagnostic, not unsupported/parser denial: "
                                    + failure.getMessage());
                }));
    }
}
