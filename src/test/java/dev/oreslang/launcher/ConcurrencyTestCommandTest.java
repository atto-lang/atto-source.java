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
    void testCommandDoesNotCountOrdinaryProgramAsPassingTests() throws Exception {
        assertEquals(1, ConcurrencyTestCommand.run(new String[]{
                "examples/together.ores", "--seed=11"}));
    }
}
