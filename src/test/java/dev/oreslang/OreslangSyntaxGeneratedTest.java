package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Deterministic syntax combinations: operators, nesting, tuples and branch spellings. */
final class OreslangSyntaxGeneratedTest {
    @Test
    void parsesAllSupportedArithmeticAndEqualityOperatorsInIsolation() {
        String[] operators = {
                "+", "-", "*", "/", "==", "!=", "eq", "neq",
                "<", "<=", ">", ">=", "&&", "||", "|"
        };
        for (String operator : operators) {
            String program = "fnc expression(int a, int b): void {"
                    + "const result = (a " + operator + " b); return; }";
            assertDoesNotThrow(() -> Parser.parse(program), program);
        }
    }

    @Test
    void parsesParenthesizedExpressionsToDepthThirtyTwo() {
        for (int depth = 0; depth <= 32; depth++) {
            String left = "(".repeat(depth);
            String right = ")".repeat(depth);
            String program = "fnc evaluate(): int { return " + left + "42" + right + "; }";
            assertDoesNotThrow(() -> Parser.parse(program), program);
        }
    }

    @Test
    void checksTupleArityAndLastSlotForOneToSixteenElements() {
        for (int arity = 1; arity <= 16; arity++) {
            StringBuilder elements = new StringBuilder();
            for (int slot = 0; slot < arity; slot++) {
                if (slot > 0) elements.append(", ");
                elements.append(slot);
            }
            String program = "fnc last(): int { const T values = infer<T> tuple ("
                    + elements + "); return values[" + (arity - 1) + "]; }";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
        }
    }

    @Test
    void checksEveryPermutationOfThreeClosedRecordFields() {
        String[] names = { "id", "active", "count" };
        String[] values = { "\"acct_42\"", "true", "7" };
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 3; second++) {
                if (first == second) continue;
                int third = 3 - first - second;
                String program = "fnc count(): int { const T record = infer<T> struct { "
                        + names[first] + ": " + values[first] + ", "
                        + names[second] + ": " + values[second] + ", "
                        + names[third] + ": " + values[third] + " }; "
                        + "const T copy = record; return copy.count; }";
                assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
            }
        }
    }

    @Test
    void parsesEveryElifSpellingWithBothThenAndLegacyDo() {
        String[] branchWords = { "elif", "elseif", "else if" };
        String[] clauses = { "then", "do" };
        for (String branch : branchWords) {
            for (String clause : clauses) {
                String program = "fnc choose(bool a, bool b): int { "
                        + "if a; " + clause + " return 1; "
                        + branch + " b; " + clause + " return 2; "
                        + "else return 0; fi }";
                assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
            }
        }
    }
}
