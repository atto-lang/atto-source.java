package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic independent state-machine oracle for the restricted
 * production Parser -> typed-plan -> private-SoA HOST executor.
 * This is NOT a Truffle guest test or a Java/LLVM parity receipt.
 */
final class DopInvertedScalarStateMachineTest {
    private static final String HEADER = """
            define inverted struct Row as
                let value: int;
                token: int;
            end
            fnc main(): int {
            """;

    private static String program(int capacity, String operations) {
        return HEADER + "let rows = InvertedArray<Row>.with_capacity("
                + capacity + ");\n" + operations + "\n}\n";
    }

    private static long nextValue(Random random, int turn) {
        return switch (turn % 11) {
            case 0 -> Long.MIN_VALUE;
            case 1 -> Long.MAX_VALUE;
            case 2 -> -1L;
            case 3 -> 0L;
            case 4 -> 1L;
            case 5 -> -42L;
            case 6 -> 42L;
            default -> random.nextLong();
        };
    }

    @Test
    void ninetySixSeededProgramsAgreeWithAnIndependentDenseRowModel() {
        long exercised = 0;
        for (int seed = 1; seed <= 96; seed++) {
            Random random = new Random(0x6d5a56daL ^ (seed * 0x9e3779b97f4a7c15L));
            int capacity = 1 + seed % 12;
            List<long[]> model = new ArrayList<>();
            StringBuilder statements = new StringBuilder();
            int mutations = 30 + seed % 40;
            for (int step = 0; step < mutations; step++) {
                int decision = random.nextInt(100);
                if (model.isEmpty() || (decision < 35 && model.size() < capacity)) {
                    long value = nextValue(random, step);
                    long token = nextValue(random, step + 3);
                    statements.append("rows.push(").append(value).append(", ")
                            .append(token).append(");\n");
                    model.add(new long[]{value, token});
                } else if (decision < 77) {
                    int index = random.nextInt(model.size());
                    long value = nextValue(random, step + 7);
                    statements.append("rows.set(").append(index).append(", value, ")
                            .append(value).append(");\n");
                    model.get(index)[0] = value;
                } else {
                    int index = random.nextInt(model.size());
                    statements.append("rows.erase_swap(").append(index).append(");\n");
                    int last = model.size() - 1;
                    if (index != last) model.set(index, model.get(last));
                    model.remove(last);
                }
                exercised++;
            }
            if (model.isEmpty()) {
                statements.append("rows.push(42, 99);\n");
                model.add(new long[]{42, 99});
                exercised++;
            }
            int observedRow = random.nextInt(model.size());
            String field = seed % 2 == 0 ? "value" : "token";
            long expected = model.get(observedRow)[seed % 2 == 0 ? 0 : 1];
            statements.append("return rows.get(").append(observedRow)
                    .append(", ").append(field).append(");");
            var plan = OresCompiler.compileDopInvertedScalarI64(
                    program(capacity, statements.toString()));
            assertEquals(capacity, plan.capacity(), "seed=" + seed);
            assertEquals(expected, plan.executeHostReference(), "first run seed=" + seed);
            assertEquals(expected, plan.executeHostReference(), "re-run seed=" + seed);
            assertEquals(statements.toString().lines().count(),
                    plan.instructions().size(), "typed operations seed=" + seed);
        }
        assertTrue(exercised >= 3000, "insufficient modeled mutation coverage");
    }

    @Test
    void staticRejectionsProtectCapacityAndImmutableColumns() {
        for (int capacity : new int[]{1, 2, 3, 8}) {
            StringBuilder overfill = new StringBuilder();
            for (int i = 0; i < capacity + 1; i++) {
                overfill.append("rows.push(").append(i).append(", 9);\n");
            }
            overfill.append("return rows.get(0, value);");
            final String source = program(capacity, overfill.toString());
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileDopInvertedScalarI64(source),
                    "overfill must fail at compiler admission");
        }
        assertThrows(IllegalArgumentException.class, () ->
            OresCompiler.compileDopInvertedScalarI64(program(3,
                "rows.push(1,2); rows.set(0,token,99); return rows.get(0,value);")));
        assertThrows(IllegalArgumentException.class, () ->
            OresCompiler.compileDopInvertedScalarI64(program(3,
                "rows.push(1,2); rows.erase_swap(1); return rows.get(0,value);")));
    }

    @Test
    void removedDenseIndexIsNotAStaleHandleAndMustTrap() {
        var bad = OresCompiler.compileDopInvertedScalarI64(program(3,
            "rows.push(7,70); rows.push(19,190); "
            + "rows.erase_swap(0); return rows.get(1,value);"));
        assertThrows(IllegalArgumentException.class, bad::executeHostReference);
        assertThrows(IllegalArgumentException.class, bad::executeHostReference);
    }

    @Test
    void unsupportedAliasesOrEffectsNeverEnterTypedPlan() {
        for (String suffix : List.of(
            "let alias = rows; return rows.get(0,value);",
            "return unknown(rows.get(0,value));",
            "rows.push(1,2); return rows.get(0,value + 1);",
            "rows.push(1,2); rows.borrowWrite(value); return rows.get(0,value);",
            "rows.push(1,2); rows.reserve(100); return rows.get(0,value);")) {
            assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileDopInvertedScalarI64(program(3,suffix)),
                "unverified guest expression must be rejected: " + suffix);
        }
    }

    @Test
    void exactBoundarySignedLiteralsRetainTheirBitPattern() {
        for (long expected : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1, 42}) {
            var plan = OresCompiler.compileDopInvertedScalarI64(program(1,
                "rows.push(" + expected + ", 99); return rows.get(0,value);"));
            assertEquals(expected, plan.executeHostReference());
        }
    }
}
