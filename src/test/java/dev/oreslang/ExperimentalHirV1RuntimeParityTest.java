package dev.oreslang;

import dev.oreslang.compiler.ExperimentalHirV1Exporter;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Experimental scalar program executes through the REAL linked GraalVM runtime.
 * Not a replacement for wider runtime parity, AOT native-image verification,
 * effect/actor conformance, or checked-i64 overflow equivalence.
 */
final class ExperimentalHirV1RuntimeParityTest {
    @TempDir Path temp;

    private static final String SOURCE = """
            pub fnc counter_response(int count): int {
                return count;
            }
            pub fnc main(): int {
                return counter_response(43);
            }
            """;

    @Test
    void actualCompilerAdmissionAndLinkedGuestExecutionAgreeOn43() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(SOURCE));
        String hir = ExperimentalHirV1Exporter.fromSource(SOURCE);
        assertTrue(hir.contains("\"ir_family\":\"ores-hir\""));
        assertTrue(hir.contains("\"value\":\"43\""));
        Path path = temp.resolve("same-source.ores");
        Files.writeString(path, SOURCE);
        var result = LinkedProgramRunner.runWithExitStatus(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(43, result.exitStatus(),
                "The real linked GraalVM guest must match the canonical HIR/MIR reference result");
    }
}
