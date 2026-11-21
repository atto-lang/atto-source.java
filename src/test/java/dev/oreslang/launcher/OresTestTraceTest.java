package dev.oreslang.launcher;

import dev.oreslang.runtime.OresTestExploration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class OresTestTraceTest {
    @TempDir Path temporary;

    @Test
    void recordedInputTraceReplaysOnlyMatchingSourceAndFingerprint() throws Exception {
        Path source = temporary.resolve("suite.ores");
        Path trace = temporary.resolve("gate.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var result = new OresTestExploration.Report(21, 4, 19, 0, 0, 1234567);
        OresTestTrace.write(trace, source, 42, 19, true, result);
        var recorded = OresTestTrace.read(trace, source);
        assertEquals(42L, recorded.seed());
        assertEquals(19, recorded.runs());
        assertTrue(recorded.modelCheck());
        assertDoesNotThrow(() -> OresTestTrace.verify(recorded, result));
        assertThrows(IOException.class, () -> OresTestTrace.verify(recorded,
                new OresTestExploration.Report(21, 4, 19, 0, 0, 7654321)));
        assertThrows(IOException.class,
                () -> OresTestTrace.write(trace, source, 42, 19, true, result));
    }

    @Test
    void sourceMutationInvalidatesExistingTrace() throws Exception {
        Path source = temporary.resolve("mutable.ores");
        Path trace = temporary.resolve("mutable.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var result = new OresTestExploration.Report(21, 4, 0, 0, 0, 123);
        OresTestTrace.write(trace, source, 0, 0, true, result);
        Files.writeString(source, "pub routine main(): void { return; } // changed\n");
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
    }

    @Test
    void importedDependencyMutationInvalidatesRecordedTrace() throws Exception {
        Path source = temporary.resolve("imports.ores");
        Path dependency = temporary.resolve("helper.ores");
        Path trace = temporary.resolve("transitive.trace");
        Files.writeString(dependency, "pub fnc helper(): int { return 1; }\n");
        Files.writeString(source,
                "import fnc helper from './helper';\n"
                + "pub routine main(): void { return; }\n");
        var report = new OresTestExploration.Report(21, 4, 0, 0, 0, 123);
        OresTestTrace.write(trace, source, 0, 0, true, report);
        String oldDigest = OresTestTrace.hash(source);
        Files.writeString(dependency, "pub fnc helper(): int { return 2; }\n");
        assertNotEquals(oldDigest, OresTestTrace.hash(source));
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
    }

    @Test
    void changedProjectManifestInvalidatesClosureTrace() throws Exception {
        Path project = temporary.resolve("project-with-manifest");
        Files.createDirectories(project);
        Path source = project.resolve("main.ores");
        Path manifest = project.resolve(".oreslangc.cfg.toml");
        Path trace = project.resolve("project.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(manifest, "schema_version = \"1\"\n"
                + "[project]\nname = \"first\"\n");
        var result = new OresTestExploration.Report(21, 4, 0, 0, 0, 123);
        OresTestTrace.write(trace, source, 42, 0, true, result);
        assertDoesNotThrow(() -> OresTestTrace.read(trace, source));
        Files.writeString(manifest, "schema_version = \"1\"\n"
                + "[project]\nname = \"second\"\n");
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
    }

    @Test
    void legacyEntryOnlyTraceVersionIsRejected() throws Exception {
        Path source = temporary.resolve("legacy.ores");
        Path trace = temporary.resolve("legacy.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var result = new OresTestExploration.Report(0, 0, 1, 0, 0, 33);
        OresTestTrace.write(trace, source, 5, 1, false, result);
        String v2 = Files.readString(trace);
        Files.writeString(trace, v2.replace("oreslang-critical-exploration-v2",
                "oreslang-critical-exploration-v1"));
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
    }

    @Test
    void rejectContradictoryTraceMetadataAtReadAndWrite() throws Exception {
        Path source = temporary.resolve("metadata.ores");
        Path trace = temporary.resolve("metadata.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var valid = new OresTestExploration.Report(21, 4, 7, 0, 0, 456);
        OresTestTrace.write(trace, source, 17, 7, true, valid);
        String original = Files.readString(trace);

        for (String changed : new String[] {
                original.replace("stress_cases=7", "stress_cases=6"),
                original.replace("runs=7", "runs=-1"),
                original.replace("model_check=true", "model_check=false"),
                original.replace("states=21", "states=-1"),
                original.replace("rejected_cycles=4", "rejected_cycles=-1"),
                original.replace("rejected_cycles=4", "rejected_cycles=0"),
                original.replace("states=21", "states=0")
        }) {
            Files.writeString(trace, changed);
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source),
                    changed);
        }

        assertThrows(IOException.class, () -> OresTestTrace.write(
                temporary.resolve("bad-count.trace"), source, 17, 8, true, valid));
        assertThrows(IOException.class, () -> OresTestTrace.write(
                temporary.resolve("bad-mode.trace"), source, 17, 7, false, valid));
        // A zero-run trace with model checking disabled proves nothing.
        assertThrows(IOException.class, () -> OresTestTrace.write(
                temporary.resolve("empty.trace"), source, 17, 0, false,
                new OresTestExploration.Report(0, 0, 0, 0, 0, 123)));
    }

    @Test
    void rejectOversizedAndInvalidUtf8TraceDuringBoundedRead() throws Exception {
        Path source = temporary.resolve("bounded-source.ores");
        Path trace = temporary.resolve("bounded-trace.orestrace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var report = new OresTestExploration.Report(21, 4, 3, 0, 0, 99);
        OresTestTrace.write(trace, source, 7, 3, true, report);
        byte[] valid = Files.readAllBytes(trace);
        Files.write(trace, new byte[4097]);
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
        Files.write(trace, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
        Files.write(trace, valid);
        assertEquals(7L, OresTestTrace.read(trace, source).seed());
    }

    @Test
    void rejectMalformedAndOutOfBudgetTraceRecords() throws Exception {
        Path source = temporary.resolve("suite.ores");
        Path trace = temporary.resolve("gate.trace");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        var result = new OresTestExploration.Report(0, 0, 3, 0, 0, 123);
        OresTestTrace.write(trace, source, 3, 3, false, result);
        String original = Files.readString(trace);
        try {
            Files.writeString(trace, original.replace("runs=3\n", "runs=100001\n"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original + "unexpected=1\n");
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original.replace("model_check=false",
                    "model_check=maybe"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original.replace("seed=3", "seed=bad"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
        } finally {
            Files.writeString(trace, original);
        }
        assertEquals(result.inputFingerprint(),
                OresTestTrace.read(trace, source).inputFingerprint());
    }
}
