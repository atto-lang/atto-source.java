package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real compiler/linker/guest execution of the canonical AttoLang test harness.
 * Source-only checks or synthetic receipts cannot satisfy these tests.
 */
final class AttoNativeTestingGuestIntegrationTest {
    private static final String CANONICAL_STD_UNIT = "@std/testing.atto";
    private static final String LEGACY_STD_UNIT = "@std/testing.ores";

    private static Path fixture(String path) {
        Path source = Path.of(path).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "missing checked-in .atto guest: " + path);
        assertFalse(Files.isSymbolicLink(source), "symlinked guest: " + path);
        assertTrue(source.getFileName().toString().endsWith(".atto"));
        return source;
    }

    private static void requireCanonicalCompile(Path source) throws Exception {
        var compiled = LinkedProgramRunner.validate(source, Map.of());
        assertTrue(compiled.units().containsKey(CANONICAL_STD_UNIT),
                "AttoLang guest must compile with canonical packaged testing.atto");
        assertFalse(compiled.units().containsKey(LEGACY_STD_UNIT),
                "legacy testing.ores must not substitute for testing.atto");
    }

    private static void execute(Path source, ByteArrayOutputStream output) throws Exception {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var compiled = LinkedProgramRunner.run(
                source, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, errors);
        assertTrue(compiled.units().containsKey(CANONICAL_STD_UNIT));
        assertFalse(compiled.units().containsKey(LEGACY_STD_UNIT));
        assertEquals("", errors.toString(StandardCharsets.UTF_8),
                "native guest emitted unexpected stderr");
    }

    @Test
    void fortyCanonicalAttoCasesCompileAndEmitAllExactOrderedGuestReceipts() throws Exception {
        Path source = fixture("examples/native-testing-regressions-40.atto");
        requireCanonicalCompile(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        execute(source, output);

        List<String> ids = Files.readAllLines(
                Path.of("tests/native-testing-regressions-40.expected-ids.txt"),
                StandardCharsets.UTF_8);
        assertEquals(40, ids.size(), "expected-case inventory must remain pinned");

        List<String> expected = new ArrayList<>();
        for (String id : ids) {
            assertTrue(id.matches("[A-Za-z0-9][A-Za-z0-9._/-]*"),
                    "invalid trusted test identity: " + id);
            expected.add("ORES_TEST|PASS|stdlib/" + id + "|");
        }
        expected.add("ORES_TEST|SUMMARY|total=40|passed=40|failed=0|skipped=0|filtered=0");
        List<String> actual = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(expected, actual, "guest-generated machine receipts must match exact IDs/order");

        // Separate compiler-backed source; keep the 40-case machine ledger fixed.
        Path summaryRange = fixture("examples/native-testing-summary-range.atto");
        requireCanonicalCompile(summaryRange);
        ByteArrayOutputStream rangeOutput = new ByteArrayOutputStream();
        execute(summaryRange, rangeOutput);
        assertEquals(List.of("ATTO_TEST_SUMMARY_RANGE_OK|checks=19"),
                rangeOutput.toString(StandardCharsets.UTF_8).lines().toList(),
                "range assertions and summary validation must execute in the guest");
    }

    @Test
    void canonicalAttoNegativeGuestsMustCompileThenEmitExpectedSummaryAndFail() throws Exception {
        for (String variant : List.of("empty", "skipped", "filtered", "failed")) {
            Path source = fixture("examples/native-testing-negative-" + variant + ".atto");
            // A compiler error or missing source is not a passing negative test.
            requireCanonicalCompile(source);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.run(
                            source, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                            Set.of(), Map.of(), output, errors),
                    "expected runtime rejection for " + variant);
            String summary = switch (variant) {
                case "empty" -> "total=0|passed=0|failed=0|skipped=0|filtered=0";
                case "skipped" -> "total=1|passed=0|failed=0|skipped=1|filtered=0";
                case "filtered" -> "total=0|passed=0|failed=0|skipped=0|filtered=1";
                case "failed" -> "total=1|passed=0|failed=1|skipped=0|filtered=0";
                default -> throw new IllegalStateException("unlisted negative fixture");
            };
            List<String> events = output.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("ORES_TEST|")).toList();
            assertFalse(events.isEmpty(), "guest emitted no machine events: " + variant);
            assertEquals("ORES_TEST|SUMMARY|" + summary,
                    events.get(events.size() - 1),
                    "runtime rejection must occur AFTER the correct guest summary: " + variant);
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("UNEXPECTED_SUCCESS"),
                    "guest continued beyond require_success: " + variant);
        }

        // This negative is not a missing compilation, compiler error, or
        // synthetic receipt: the actual compiled guest must reach the gate.
        Path forged = fixture("examples/native-testing-negative-forged-summary.atto");
        requireCanonicalCompile(forged);
        ByteArrayOutputStream forgedOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream forgedErrors = new ByteArrayOutputStream();
        assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        forged, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        Set.of(), Map.of(), forgedOutput, forgedErrors),
                "inconsistent RunSummary must be rejected at runtime");
        assertFalse(forgedOutput.toString(StandardCharsets.UTF_8).contains("UNEXPECTED_SUCCESS"),
                "forged guest continued after require_success");
    }
}
