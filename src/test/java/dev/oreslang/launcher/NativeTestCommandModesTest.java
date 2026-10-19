package dev.oreslang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class NativeTestCommandModesTest {
    @TempDir Path temporary;

    @Test
    void nativeSeededInputsAreRequiredForFuzzMode() throws Exception {
        assertEquals(0, OresTestCommand.run(new String[] {
                "--fuzz", "--seed=17", "--runs=97",
                "examples/native-fuzz-testing.ores"
        }));
        // This fixture generates exactly 97 guest inputs. A --runs flag
        // cannot silently measure only an unrelated host stress loop.
        assertEquals(2, OresTestCommand.run(new String[] {
                "--fuzz", "--seed=17", "--runs=16",
                "examples/native-fuzz-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--fuzz", "examples/native-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--fuzz", "--seed=18", "examples/native-fuzz-testing.ores"
        }));
    }

    @Test
    void boundedDeadlockAndModelExplorationUseRealCriticalGateTransitions()
            throws Exception {
        assertEquals(0, OresTestCommand.run(new String[] {
                "--deadlock", "--model-check", "examples/native-testing.ores"
        }));
        assertEquals(0, OresTestCommand.run(new String[] {
                "--schedule-fuzz", "--runs=37", "--seed=42",
                "examples/native-testing.ores"
        }));
    }

    @Test
    void combineInputFuzzDeadlockAndRuntimeScheduleStress() throws Exception {
        assertEquals(0, OresTestCommand.run(new String[] {
                "--fuzz", "--deadlock", "--schedule-fuzz",
                "--seed=17", "--runs=97", "examples/native-fuzz-testing.ores"
        }));
    }

    @Test
    void recordAndReplayBoundedCriticalInputsWithSourceHash() throws Exception {
        Path trace = temporary.resolve("case.orestrace");
        assertEquals(0, OresTestCommand.run(new String[] {
                "--schedule-fuzz", "--model-check", "--seed=129",
                "--runs=19", "--trace-out", trace.toString(),
                "examples/native-testing.ores"
        }));
        assertTrue(Files.size(trace) > 0);
        assertEquals(0, OresTestCommand.run(new String[] {
                "--replay", trace.toString(), "examples/native-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--replay", trace.toString(), "examples/native-fuzz-testing.ores"
        }));
    }

    @Test
    void raceInstrumentationCannotBeFakedByFuzzingOrModelChecks() throws Exception {
        assertEquals(2, OresTestCommand.run(new String[] {
                "--race", "--fuzz", "--deadlock", "--schedule-fuzz",
                "examples/native-fuzz-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--race", "examples/native-fuzz-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--runs=0", "examples/native-testing.ores"
        }));
        assertEquals(2, OresTestCommand.run(new String[] {
                "--model-check", "--trace-out", tracePath(),
                "--replay", tracePath(), "examples/native-testing.ores"
        }));
    }

    private String tracePath() {
        return temporary.resolve("no.orestrace").toString();
    }
}
