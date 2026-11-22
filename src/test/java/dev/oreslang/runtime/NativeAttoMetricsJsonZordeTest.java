package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real guest execution is required before claiming these source modules work.
 * These fixtures deliberately exercise the compiler-owned Atto import path.
 */
final class NativeAttoMetricsJsonZordeTest {
    private static final String DIR =
            "src/test/resources/stdlib-parity/metrics-json-zorde/";

    @Test
    void modulesAreClosedSourceOnlyImports() throws Exception {
        for (String name : Set.of("metrics", "json", "zorde")) {
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(name + ".atto"));
            assertEquals("@std/" + name + ".atto",
                    CoreLibrarySources.unitId("std/" + name));
            assertEquals(CoreLibrarySources.unitId("std/" + name),
                    CoreLibrarySources.unitId("ores.std/" + name));
            String source = CoreLibrarySources.source("std/" + name);
            var parsed = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId("std/" + name),
                    CoreLibrarySources.fileName("std/" + name), source);
            assertTrue(parsed.hasOresSource());
            assertFalse(parsed.hasJavaSource());
            assertFalse(source.contains("java:"));
            assertFalse(source.contains("import java"));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + name + ".ores"));
        }
    }

    private static void assertGuest(String name, String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var compiled = LinkedProgramRunner.run(Path.of(DIR + name + ".atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                out, err);
        assertTrue(compiled.units().containsKey("@std/" + name + ".atto"),
                "missing compiler-owned Atto module " + name);
        assertEquals(String.join("\n", lines),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void metricsGuestRejectsOverflowAndBucketsSamples() throws Exception {
        assertGuest("metrics",
                "true", "false", "true", "false", "1", "false", "true",
                "-1", "true", "true", "true", "false", "3", "23",
                "1", "1", "1", "1", "1", "2", "3",
                "23", "1", "5", "true", "true", "3", "1",
                "4", "2", "-1", "-1", "3", "true", "-1",
                "2");
    }

    @Test
    void jsonGuestRejectsAmbiguousAndMalformedText() throws Exception {
        assertGuest("json", "true", "6", "true", "true", "true", "true", "true",
                "true", "true", "true", "true", "true", "true", "true", "2147483647",
                "true", "true", "true", "true", "true", "true", "true", "true",
                "true", "true", "true", "true", "true", "true", "true", "true",
                "true", "true", "true", "true", "true");
    }

    @Test
    void zordeGuestRoundtripsDeterministicFrame() throws Exception {
        assertGuest("zorde",
                "true", "10", "7", "1", "2", "true", "7",
                "1", "2", "255", "true", "90", "79", "true",
                "2", "300", "0", "true", "1", "true", "true",
                "true", "-1073741824", "1073741823", "true", "true", "true", "3",
                "20", "0", "false", "0", "true", "true", "true",
                "true", "true", "true", "true", "true", "true", "true",
                "true", "true", "true", "true", "5", "false", "5",
                "false", "5", "true", "true", "true", "true");
    }
    @Test
    void coreBorrowAdmissionGuestIsFreestandingAndExact() throws Exception {
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("borrow_admission.atto"));
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("collections.atto"),
                "main's freestanding collections module must survive integration");
        for (String hosted : Set.of("telemetry.atto", "metrics.atto", "json.atto",
                "zorde.atto", "task_parallel_policy.atto", "source_budget_policy.atto")) {
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(hosted),
                    "existing hosted module was lost during integration: " + hosted);
        }
        assertEquals("@ores.core/borrow_admission.atto",
                CoreLibrarySources.oresCoreUnitId("ores.core/borrow_admission"));
        String source = CoreLibrarySources.oresCoreSource("ores.core/borrow_admission");
        assertTrue(source.contains("define module borrow_admission as"));
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("import java"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var program = LinkedProgramRunner.run(
                Path.of(DIR + "core-borrow-admission.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(program.units().containsKey("@ores.core/borrow_admission.atto"),
                "core module must be linked as real guest source");
        assertEquals(String.join("\n", "true", "false", "false", "true", "false", "true", "false", "true", "false", "true", "true", "false", "false", "false", "false", "false"),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void coreBorrowCompilerRejectsInvalidLoans() {
        // Target profile is canonical Atto ownership. No parse-only
        // exception can satisfy a typed-borrow diagnostic expectation.
        String[] examples = {
                "fail/read-loan.atto",
                "fail/write-loan.atto",
                "fail/take.atto",
                "fail/suspension.atto"
        };
        String[] expected = {
                "E_BORROW", "E_BORROW", "E_MOVED", "E_LOAN_SUSPEND"
        };
        for (int i = 0; i < examples.length; i++) {
            final String fixture = examples[i];
            final String diagnostic = expected[i];
            ByteArrayOutputStream guestOut = new ByteArrayOutputStream();
            ByteArrayOutputStream diagnosticOut = new ByteArrayOutputStream();
            Throwable failure = assertThrows(Throwable.class, () ->
                    LinkedProgramRunner.run(
                            Path.of("tests/fixtures/core-borrow-v1/" + fixture),
                            IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                            guestOut, diagnosticOut),
                    "compiler must reject invalid ownership: " + fixture);
            assertEquals(0, guestOut.size(),
                    "rejected source must not execute guest stdout: " + fixture);
            StringBuilder details = new StringBuilder();
            for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
                details.append(cursor).append("\n");
            }
            assertTrue(details.toString().contains(diagnostic),
                    () -> "expected typed " + diagnostic
                            + " rather than parser/runtime fallback: " + details);
        }
    }

}
