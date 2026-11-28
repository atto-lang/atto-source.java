package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is a source-compiled Atto guest test, not a Java host model.
 * The runner must resolve four byte-preserved source units per entry, then
 * execute 37 smoke or 109 boundary checks before emitting a guest marker.
 * CI results must be from a stepful run at the exact current PR head.
 */
@Timeout(180)
final class AttoStdWebsocketQuicComputeGuestTest {
    private static final Path ENTRY =
            Path.of("tests/stdlib/websocket_quic_compute_smoke.atto");
    private static final String[] SOURCES = {
            "stdlib/std/websocket.atto",
            "stdlib/std/quic.atto",
            "stdlib/std/compute.atto",
            "tests/stdlib/websocket_quic_compute_smoke.atto",
            "tests/stdlib/protocol_boundaries.atto"
    };

    @TempDir Path temp;
    private Path entry;

    @BeforeEach
    void copyExactAttoSourceUnits() throws Exception {
        for (String relative : SOURCES) {
            Path source = Path.of(relative);
            assertTrue(Files.isRegularFile(source), "Atto source absent: " + relative);
            assertFalse(Files.isSymbolicLink(source), "symlinked Atto source: " + relative);
            Path destination = temp.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
            assertEquals(-1L, Files.mismatch(source, destination),
                    "guest imports must preserve exact source bytes: " + relative);
        }
        entry = temp.resolve(ENTRY.toString());
    }

    @Test
    void linkedAttoSourcesExecuteAll37ChecksBeforeEmittingSuccess() throws Exception {
        var validated = LinkedProgramRunner.validate(entry, Map.of());
        assertEquals(4, validated.units().size(),
                "one Atto guest and exactly three imported Atto libraries required");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals(4, result.build().units().size());
        assertEquals(0, result.exitStatus(), "Atto guest must exit successfully");
        assertEquals("ATTO_STD_PROTOCOL_37_OK", out.toString(StandardCharsets.UTF_8),
                "an early return, a skipped assertion, or a host-only simulation cannot pass");
    }

    @Test
    void boundaryAttoGuestExecutes109CasesBeforeEmittingSuccess() throws Exception {
        Path boundary = temp.resolve("tests/stdlib/protocol_boundaries.atto");
        var validated = LinkedProgramRunner.validate(boundary, Map.of());
        assertEquals(4, validated.units().size(),
                "109-case fixture must compile with three imported .atto libraries");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                boundary, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals(4, result.build().units().size());
        assertEquals(0, result.exitStatus(), "109 native Atto boundary checks must all pass");
        assertEquals("ATTO_STD_PROTOCOL_109_OK", out.toString(StandardCharsets.UTF_8),
                "only a completed Atto guest may emit the boundary success marker");
    }

    @Test
    void corruptedBoundaryAttoAssertionReturnsItsOwnNonzeroFailureCode() throws Exception {
        Path boundary = temp.resolve("tests/stdlib/protocol_boundaries.atto");
        String original = Files.readString(boundary, StandardCharsets.UTF_8);
        String target = "compute_upload_admit(0, 1, 1, false, true)";
        assertTrue(original.contains(target), "negative test must modify case 109");
        assertEquals(original.indexOf(target), original.lastIndexOf(target),
                "test must modify exactly one case");
        Files.writeString(boundary, original.replace(target,
                "compute_upload_admit(0, 1, 1, true, true)"), StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                boundary, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertEquals(109, result.exitStatus(),
                "fault injection must fail at the final native Atto boundary assertion");
        assertEquals("", out.toString(StandardCharsets.UTF_8),
                "faulted guest must not forge the success marker");
    }

    @Test
    void missingImportFailsBeforeGuestExecution() throws Exception {
        String original = Files.readString(entry, StandardCharsets.UTF_8);
        assertTrue(original.contains("'../../stdlib/std/quic'"),
                "negative test must mutate the real Atto import");
        Files.writeString(entry, original.replace(
                "'../../stdlib/std/quic'", "'../../stdlib/std/missing'"),
                StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> LinkedProgramRunner.validate(entry, Map.of()),
                "missing .atto module cannot become an ambient runtime capability");
    }

    @Test
    void unknownQuicSymbolCannotPassGuestLinking() throws Exception {
        String original = Files.readString(entry, StandardCharsets.UTF_8);
        assertTrue(original.contains("quic_encoding_min_width("));
        Files.writeString(entry,
                original.replace("quic_encoding_min_width(", "quic_not_exported_min_width("),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThrows(Exception.class, () -> LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err));
        assertEquals("", out.toString(StandardCharsets.UTF_8),
                "rejected guest must not emit the 37-case success marker");
    }
}
