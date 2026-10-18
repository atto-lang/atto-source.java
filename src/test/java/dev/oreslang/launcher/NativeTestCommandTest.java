package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeTestCommandTest {
    @Test
    void nativeSuiteRunsThroughOreslangTestSubcommand() throws Exception {
        assertEquals(0, OresTestCommand.run(
                new String[] { "examples/native-testing.ores" }));
    }

    @Test
    void raceModeCannotSilentlyFallBackToUninstrumentedTests() throws Exception {
        assertEquals(2, OresTestCommand.run(
                new String[] { "--race", "examples/native-testing.ores" }));
    }

    @Test
    void fuzzModeRequiresWorkingScheduleInstrumentationAndReplay() throws Exception {
        assertEquals(2, OresTestCommand.run(
                new String[] { "--fuzz", "--seed=42", "examples/native-testing.ores" }));
        assertEquals(2, OresTestCommand.run(
                new String[] { "--replay=failures/case-001.trace" }));
    }

    @Test
    void missingNativeSuiteOrUnknownOptionIsNotSuccessful() throws Exception {
        assertEquals(2, OresTestCommand.run(new String[0]));
        assertEquals(2, OresTestCommand.run(
                new String[] { "--unrecognized", "examples/native-testing.ores" }));
    }
}
