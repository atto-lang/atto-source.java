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
