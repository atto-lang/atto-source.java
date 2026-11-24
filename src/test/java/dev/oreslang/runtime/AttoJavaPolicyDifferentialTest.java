package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic differential tests: the oracle is the existing HOST Java
 * implementation, while actual values must come from a compiled .atto guest.
 * No source-shape inspection or host-only simulation can satisfy these tests.
 */
final class AttoJavaPolicyDifferentialTest {
    @TempDir Path temp;

    private void assertGuestMatrix(String module, List<String> expressions,
                                   List<String> expected) throws Exception {
        assertEquals(expressions.size(), expected.size(), "matrix output cardinality");
        StringBuilder source = new StringBuilder();
        source.append("import module ").append(module)
                .append(" from \"ores.std/").append(module).append(".atto\";\n");
        source.append("pub routine main(): void {\n");
        for (String expression : expressions) {
            source.append("  stdio.println(").append(expression).append(");\n");
        }
        source.append("  return;\n}\n");
        Path entry = temp.resolve(module + "-differential.atto");
        Files.writeString(entry, source.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/" + module + ".atto"),
                "real compiler-owned .atto source must be linked");
        String actual = output.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
        assertEquals(String.join("\n", expected), actual,
                () -> "Atto guest did not match Java oracle for " + module
                        + ": " + errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void seededLayoutLengthsAndOffsetsEqualJavaDataOrientedLayout() throws Exception {
        Random random = new Random(0x4A77011L);
        List<String> expressions = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        var kinds = DataOrientedLayout.Kind.values();
        for (int i = 0; i < 72; i++) {
            var kind = kinds[i % kinds.length];
            int kindId = kind.ordinal() + 1;
            int rows = random.nextInt(2001);
            int fields = 1 + random.nextInt(48);
            int scalarBytes = 1 << random.nextInt(9);
            int alignment = scalarBytes << random.nextInt(4);
            int lanes = kind == DataOrientedLayout.Kind.AOSOA
                    ? 1 << random.nextInt(7) : 1;
            var host = new DataOrientedLayout.Plan(
                    kind, rows, fields, scalarBytes, alignment, lanes);
            String args = kindId + ", " + rows + ", " + fields
                    + ", " + scalarBytes + ", " + alignment + ", " + lanes;
            expressions.add("layout_policy.byte_length(" + args + ").unwrap()");
            expected.add(Long.toString(host.byteLength()));
            if (rows > 0) {
                int row = random.nextInt(rows);
                int field = random.nextInt(fields);
                expressions.add("layout_policy.byte_offset(" + args + ", "
                        + row + ", " + field + ").unwrap()");
                expected.add(Long.toString(host.byteOffset(row, field)));
            }
        }
        assertThrows(IllegalArgumentException.class, () ->
                new DataOrientedLayout.Plan(
                        DataOrientedLayout.Kind.AOS, -1, 2, 4, 8, 1));
        expressions.add("layout_policy.byte_length(1, -1, 2, 4, 8, 1).is_err()");
        expected.add("true");
        expressions.add("layout_policy.byte_length(1, 1000001, 2, 4, 8, 1).is_err()");
        expected.add("true");
        expressions.add("layout_policy.byte_offset(1, 2, 2, 4, 8, 1, 2, 1).is_err()");
        expected.add("true");
        assertGuestMatrix("layout_policy", expressions, expected);
    }

    @Test
    void seededRegionConflictsEqualJavaTrustedRootModel() throws Exception {
        Random random = new Random(0xC0FFEE2L);
        List<String> expressions = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        var modes = DopRegionSchedule.Mode.values();
        for (int i = 0; i < 90; i++) {
            int rows = 1 + random.nextInt(100);
            int fields = 1 + random.nextInt(16);
            var leftRoot = DopRegionSchedule.Root.trustedCreate(rows, fields);
            boolean sameRoot = random.nextBoolean();
            var rightRoot = sameRoot ? leftRoot
                    : DopRegionSchedule.Root.trustedCreate(rows, fields);
            int aField = random.nextInt(fields), bField = random.nextInt(fields);
            int aBegin = random.nextInt(rows + 1);
            int aEnd = aBegin + random.nextInt(rows - aBegin + 1);
            int bBegin = random.nextInt(rows + 1);
            int bEnd = bBegin + random.nextInt(rows - bBegin + 1);
            int aMode = random.nextInt(modes.length);
            int bMode = random.nextInt(modes.length);
            // Trusted Java host requires a named operator for REDUCE;
            // guest metadata may compare modes without minting that authority.
            String aReduction = modes[aMode] == DopRegionSchedule.Mode.REDUCE
                    ? "sum" : null;
            String bReduction = modes[bMode] == DopRegionSchedule.Mode.REDUCE
                    ? "sum" : null;
            var a = new DopRegionSchedule.Access(leftRoot, aField,
                    aBegin, aEnd, modes[aMode], aReduction);
            var b = new DopRegionSchedule.Access(rightRoot, bField,
                    bBegin, bEnd, modes[bMode], bReduction);
            expressions.add("region_policy.independent_metadata("
                    + rows + ", " + fields + ", " + sameRoot + ", "
                    + aField + ", " + aBegin + ", " + aEnd + ", " + (aMode+1)
                    + ", " + bField + ", " + bBegin + ", " + bEnd
                    + ", " + (bMode+1) + ")");
            expected.add(Boolean.toString(DopRegionSchedule.independent(a, b)));
        }
        expressions.add("region_policy.independent_metadata(5, 1, false, 0, 0, 6, 2, 0, 0, 3, 2)");
        expected.add("false"); // Invalid range fails closed even if root differs.
        assertGuestMatrix("region_policy", expressions, expected);
    }

    @Test
    void dense32UnsignedWordsEqualJavaHostCodecOnBoundaryVectors() throws Exception {
        List<String> expressions = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        long[] indices = {0L, 1L, 2L, 2147483646L, 2147483647L,
                2147483648L, 4294967293L, 4294967294L};
        for (long index : indices) {
            long wireWord = Integer.toUnsignedLong(
                    ManagedReferencePolicy.encodeDenseIndex32(index));
            expressions.add("dense_index.encode32(" + index + ").unwrap()");
            expected.add(Long.toString(wireWord));
            expressions.add("dense_index.decode32(" + wireWord + ").unwrap()");
            expected.add(Long.toString(index));
        }
        expressions.add("dense_index.decode32(0).unwrap()");
        expected.add("-1"); // Guest-only absence sentinel; Java host uses OptionalLong.empty().
        expressions.add("dense_index.encode32(4294967295).is_err()");
        expected.add("true");
        expressions.add("dense_index.decode32(-1).is_err()");
        expected.add("true");
        assertGuestMatrix("dense_index", expressions, expected);
    }

    @Test
    void taskAdmissionBudgetNeverExceedsUnretiredSlotsOrTurnQuota() throws Exception {
        Random random = new Random(0x5EED11L);
        List<String> expressions = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            int inputs = random.nextInt(100);
            int concurrency = 1 + random.nextInt(100);
            int admitted = inputs == 0 ? 0 : random.nextInt(inputs + 1);
            int active = random.nextInt(Math.min(admitted, concurrency) + 1);
            int thisTurn = random.nextInt(33);
            int budget = Math.min(inputs - admitted,
                    Math.min(concurrency - active, 32 - thisTurn));
            assertTrue(budget >= 0 && budget <= 32);
            expressions.add("task_parallel_policy.next_admission_budget("
                    + inputs + ", " + concurrency + ", " + admitted
                    + ", " + active + ", " + thisTurn + ")");
            expected.add(Integer.toString(budget));
        }
        expressions.add("task_parallel_policy.next_admission_budget(4, 2, 1, 3, 0)");
        expected.add("-1");
        // Pure contract test only: Java TaskMapParallel does not expose a
        // callable budget oracle; only its scheduler owns real slot admission.
        assertGuestMatrix("task_parallel_policy", expressions, expected);
    }
}
