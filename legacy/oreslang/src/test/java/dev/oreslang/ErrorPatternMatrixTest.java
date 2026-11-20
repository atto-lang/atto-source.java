package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exactly 100 deterministic, independently reported source/compiler checks.
 *
 * These tests intentionally use local, open nominal Error-like declarations:
 * draft std/error integration is tracked separately by StdErrorCatalogTest
 * on Java PR #811. No compiler backend parity is inferred from these checks.
 */
final class ErrorPatternMatrixTest {
    private record Scenario(
            String name, String source, boolean shouldAccept, String expectedDiagnostic) { }

    private static final String ERRORS = """
            define class Error as
            end
            define class IOError extends Error as
            end
            define class FileNotFoundError extends IOError as
            end
            define class NetworkError extends IOError as
            end
            define class ArgumentError extends Error as
            end
            define class UserError extends Error as
            end
            """;

    // Shape-only lookalikes intentionally do NOT implement CodedError.
    private static final String INTERFACES = """
            define interface CodedError as
              fnc code() => int;
            end
            define class Error implements CodedError as
              pub code(): int { return 0; }
            end
            define class ShapeA as
              pub code(): int { return 1; }
            end
            define class ShapeB as
              pub code(): int { return 2; }
            end
            define class ShapeC as
              pub code(): int { return 3; }
            end
            define class ShapeD as
              pub code(): int { return 4; }
            end
            define class ShapeE as
              pub code(): int { return 5; }
            end
            """;

    private static final String[] DESCENDANTS = {
            "FileNotFoundError", "NetworkError", "IOError",
            "ArgumentError", "UserError"
    };
    private static final String[] ANCESTORS = {
            "IOError", "IOError", "Error", "Error", "Error"
    };
    private static final String[][] SIBLINGS = {
            {"NetworkError", "ArgumentError"},
            {"FileNotFoundError", "UserError"},
            {"ArgumentError", "UserError"},
            {"IOError", "UserError"},
            {"IOError", "ArgumentError"}
    };
    private static final String[] IMPOSTORS = {
            "ShapeA", "ShapeB", "ShapeC", "ShapeD", "ShapeE"
    };

    private static String source(String declarations, String subjectType, String... patterns) {
        StringBuilder result = new StringBuilder(declarations);
        result.append("\nfnc classify(").append(subjectType).append(" value): int {\n");
        result.append("  match value over\n");
        for (int arm = 0; arm < patterns.length; arm++) {
            result.append("    on ").append(patterns[arm])
                    .append(" -> { return ").append(arm + 1).append("; }\n");
        }
        return result.append("  end\n}\n").toString();
    }

    private static void add(List<Scenario> cases, String id, boolean accept,
                            String diagnostic, String declarations,
                            String subjectType, String... patterns) {
        cases.add(new Scenario(id, source(declarations, subjectType, patterns),
                accept, diagnostic));
    }

    private static List<Scenario> buildCases() {
        List<Scenario> cases = new ArrayList<>(100);

        // 20: child-before-parent is legal; unguarded ancestor-before-child
        // must be rejected, including when that ancestor is an intermediate class.
        for (int i = 0; i < DESCENDANTS.length; i++) {
            String child = DESCENDANTS[i];
            String parent = ANCESTORS[i];
            add(cases, "order/child-before-root/" + child, true, null, ERRORS, "Error",
                    child + " specific", "Error other");
            add(cases, "order/child-before-wildcard/" + child, true, null, ERRORS, "Error",
                    child + " specific", "_");
            add(cases, "order/root-shadows-child/" + child, false, "unreachable match arm",
                    ERRORS, "Error", "Error other", child + " specific");
            add(cases, "order/parent-shadows-child/" + child, false, "unreachable match arm",
                    ERRORS, "Error", parent + " ancestor", child + " specific", "_");
        }

        // 10: guards cannot establish unconditional coverage. Even a guard
        // that happens to be true is not an unguarded shadowing proof.
        for (int i = 0; i < DESCENDANTS.length; i++) {
            String child = DESCENDANTS[i];
            String parent = ANCESTORS[i];
            for (String guard : new String[]{"true", "false"}) {
                add(cases, "guards/ancestor-" + guard + "/" + child, true, null,
                        ERRORS, "Error", parent + " conditional when " + guard,
                        child + " specific", "Error rest");
            }
        }

        // 15: a nominal Error family is open, not a closed enumeration of
        // currently declared leaf types. The concrete base also remains possible.
        for (int i = 0; i < DESCENDANTS.length; i++) {
            String child = DESCENDANTS[i];
            String first = SIBLINGS[i][0];
            String second = SIBLINGS[i][1];
            add(cases, "open/one-leaf/" + child, false, "non-exhaustive match",
                    ERRORS, "Error", child + " specific");
            add(cases, "open/two-branches/" + child, false, "non-exhaustive match",
                    ERRORS, "Error", child + " specific", first + " sibling");
            add(cases, "open/three-branches/" + child, false, "non-exhaustive match",
                    ERRORS, "Error", child + " specific",
                    first + " sibling1", second + " sibling2");
        }

        // 15: three independent forms of irrefutable coverage.
        for (String child : DESCENDANTS) {
            add(cases, "fallback/root/" + child, true, null,
                    ERRORS, "Error", child + " specific", "Error remaining");
            add(cases, "fallback/wildcard/" + child, true, null,
                    ERRORS, "Error", child + " specific when false", "_");
            add(cases, "fallback/binding/" + child, true, null,
                    ERRORS, "Error", child + " specific", "remaining");
        }

        // 20: recursively prove both Result constructors, including the
        // entire open Err(Error) payload. Handling Ok never covers Err.
        for (String child : DESCENDANTS) {
            add(cases, "result/ok-leaf-and-root/" + child, true, null,
                    ERRORS, "Result<int, Error>",
                    "Ok(success)", "Err(" + child + " specific)", "Err(Error remaining)");
            add(cases, "result/ok-and-root/" + child, true, null,
                    ERRORS, "Result<int, Error>", "Ok(success)", "Err(" + child + " specific) when false", "Err(Error remaining)");
            add(cases, "result/missing-other-errors/" + child, false, "non-exhaustive match",
                    ERRORS, "Result<int, Error>", "Ok(success)", "Err(" + child + " specific)");
            add(cases, "result/missing-ok/" + child, false, "non-exhaustive match",
                    ERRORS, "Result<int, Error>", "Err(" + child + " specific)",
                    "Err(Error remaining)");
        }

        // 10: only explicit nominal interface membership counts. A class
        // with a method named code() has no interface membership by shape.
        for (String impostor : IMPOSTORS) {
            add(cases, "interface/reject-shape-forgery/" + impostor,
                    false, "non-exhaustive match", INTERFACES, "Error | " + impostor,
                    "CodedError nominal");
            add(cases, "interface/explicit-impostor-case/" + impostor,
                    true, null, INTERFACES, "Error | " + impostor,
                    "CodedError nominal", impostor + " shapeOnly");
        }

        // 10: a positive runtime type predicate and its exact complement
        // form a total partition; a negative pattern by itself does not.
        for (String target : DESCENDANTS) {
            add(cases, "complement/positive-and-negative/" + target,
                    true, null, ERRORS, "Error",
                    target + " selected", "!" + target);
            add(cases, "complement/negative-only/" + target,
                    false, "non-exhaustive match", ERRORS, "Error",
                    "!" + target);
        }

        if (cases.size() != 100) {
            throw new IllegalStateException("Expected exactly 100 cases, got " + cases.size());
        }
        if (cases.stream().map(Scenario::name).distinct().count() != 100) {
            throw new IllegalStateException("Duplicate named pattern-matching scenario");
        }
        if (cases.stream().map(Scenario::source).distinct().count() != 100) {
            throw new IllegalStateException("Duplicate Oreslang program in the 100-case corpus");
        }
        if (cases.stream().filter(Scenario::shouldAccept).count() != 55) {
            throw new IllegalStateException("Expected 55 accepts and 45 rejects");
        }
        return List.copyOf(cases);
    }

    @TestFactory
    Stream<DynamicTest> compilerErrorPatternContractMatrix() {
        return buildCases().stream().map(scenario -> DynamicTest.dynamicTest(
                scenario.name(), () -> {
                    if (scenario.shouldAccept()) {
                        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(scenario.source())));
                    } else {
                        IllegalArgumentException failure = assertThrows(
                                IllegalArgumentException.class,
                                () -> TypeChecker.check(Parser.parse(scenario.source())));
                        assertTrue(failure.getMessage().contains(scenario.expectedDiagnostic()),
                                () -> scenario.name() + ": wrong rejection: " + failure.getMessage());
                    }
                }));
    }
}
