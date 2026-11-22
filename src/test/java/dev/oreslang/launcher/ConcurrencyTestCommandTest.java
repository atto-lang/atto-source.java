package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ConcurrencyTestCommandTest {
    @TempDir Path tmp;

    @Test
    void explicitlyRefusesUninstrumentedSafetyClaims() throws Exception {
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--race"}));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--model-check"}));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=a.trace", "--fuzz"}));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--iterations=1001"}));
    }

    @Test
    void rejectsAllSkippedOrZeroPassingTests() {
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|SKIP|suite/skipped|\\n"
                + "ORES_TEST|SUMMARY|total=1|passed=0|failed=0|skipped=1|filtered=0\\n"));
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|SUMMARY|total=0|passed=0|failed=0|skipped=0|filtered=0\\n"));
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|PASS|suite/case|\\n"
                + "ORES_TEST|SUMMARY|total=2|passed=2|failed=0|skipped=0|filtered=0\\n"));
        assertTrue(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|PASS|suite/case|\\n"
                + "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\\n"));
    }

    @Test
    void rejectsProtocolEventsAfterSummaryAndUnknownEventKinds() {
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n"
                + "ORES_TEST|PASS|late-case\n"));
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|PASS|case\n"
                + "ORES_TEST|UNKNOWN|forged-protocol\n"
                + "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n"));
        assertFalse(ConcurrencyTestCommand.validNativeSuiteSummary(
                "ORES_TEST|PASS|case\n"
                + "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n"
                + "ORES_TEST|PASS|after-summary\n"));
    }

    @Test
    void rejectsMultiIterationTraceOverwriteAndSourceClobber() throws Exception {
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--iterations=3",
                "--trace-output=" + tmp.resolve("overwritten.trace")}));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores",
                "--trace-output=examples/native-testing.ores"}));
    }

    @Test
    void nativeSuiteRunsThroughNewCliWithTraceReplay() throws Exception {
        Path trace = tmp.resolve("suite.trace");
        assertEquals(0, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--deadlock", "--schedule-fuzz",
                "--seed=72", "--trace-output=" + trace}));
        assertTrue(Files.exists(trace));
        assertEquals(0, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace,
                "--seed=72"}));
    }

    @Test
    void replayRejectsExplicitlyMismatchedSeedAndAdoptsRecordedSeed() throws Exception {
        Path trace = tmp.resolve("seed.trace");
        assertEquals(0, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--seed=72", "--trace-output=" + trace
        }));
        assertEquals(72, dev.oreslang.runtime.ConcurrencyTestProbe.readTraceData(trace).seed());
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace, "--seed=73"
        }));
        assertEquals(0, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace
        }));
    }

    @Test
    void replayRejectsTamperedEntryAndBackendIdentities() throws Exception {
        Path trace = tmp.resolve("provenance.trace");
        assertEquals(0, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--seed=19", "--trace-output=" + trace
        }));
        var evidence = dev.oreslang.runtime.ConcurrencyTestProbe.readTraceData(trace);
        assertEquals(64, evidence.entrySha256().length());
        assertEquals(64, evidence.workspaceSha256().length());
        String original = Files.readString(trace);
        String alteredDigest = evidence.entrySha256().startsWith("0")
                ? "1" + evidence.entrySha256().substring(1)
                : "0" + evidence.entrySha256().substring(1);
        Files.writeString(trace, original.replace(evidence.entrySha256(), alteredDigest));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace
        }));
        Files.writeString(trace, original.replace("|backend=" + evidence.backend(),
                "|backend=foreign-backend"));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace
        }));
        String fakeWorkspace = evidence.workspaceSha256().startsWith("0")
                ? "1" + evidence.workspaceSha256().substring(1)
                : "0" + evidence.workspaceSha256().substring(1);
        Files.writeString(trace,
                original.replace(evidence.workspaceSha256(), fakeWorkspace));
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace
        }));
    }

    @Test
    void replayRejectsLegacyUnboundTrace() throws Exception {
        Path trace = tmp.resolve("legacy.trace");
        Files.writeString(trace, "ORES_CONCURRENCY_TRACE_V1|seed=19" + System.lineSeparator());
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--replay=" + trace
        }));
    }

    @Test
    void refusesOverflowedFuzzSeedRange() throws Exception {
        assertEquals(2, ConcurrencyTestCommand.run(new String[]{
                "examples/native-testing.ores", "--seed=" + Long.MAX_VALUE,
                "--iterations=2"
        }));
    }

    @Test
    void compilerFailureStillWritesTraceReceipt() throws Exception {
        Path invalid = tmp.resolve("invalid.ores");
        Path trace = tmp.resolve("invalid.trace");
        Files.writeString(invalid, "this is not valid Oreslang source !!!");
        assertEquals(1, ConcurrencyTestCommand.run(new String[]{
                invalid.toString(), "--schedule-fuzz", "--trace-output=" + trace}));
        assertTrue(Files.exists(trace));
    }

    @Test
    void testCommandDoesNotCountOrdinaryProgramAsPassingTests() throws Exception {
        assertEquals(1, ConcurrencyTestCommand.run(new String[]{
                "examples/together.ores", "--seed=11",
                "--trace-output=" + tmp.resolve("ordinary.trace")}));
    }
}
