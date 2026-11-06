package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.OwnershipChecker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical .atto guest execution and checker-semantic denial probes.
 *
 * These tests do not prove LLVM/Forge/Wasm/.NET parity or Native Image AOT.
 * The negative path deliberately runs the ownership checker after successful
 * parsing, distinguishing a semantic ownership denial from a grammar failure.
 */
final class AttoOwnershipWave1GuestTest {
    private static final String ROOT = "/atto/ownership-wave1/";
    @TempDir Path temp;

    private Path stage(String filename) throws IOException {
        try (InputStream in = AttoOwnershipWave1GuestTest.class.getResourceAsStream(ROOT + filename)) {
            assertNotNull(in, "missing source-controlled .atto ownership fixture: " + filename);
            Path staged = temp.resolve(filename);
            Files.copy(in, staged);
            return staged;
        }
    }

    @Test
    void realAttoGuestOwnershipPositiveProgramsExitFortyTwo() throws Exception {
        for (String filename : new String[] {
                "copy-scalar-ok.atto",
                "borrow-scalar-copy-ok.atto",
                "take-named-owner-ok.atto",
                "share-scalar-ok.atto"
        }) {
            Path entry = stage(filename);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var result = LinkedProgramRunner.runWithExitStatus(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals(42, result.exitStatus(),
                    filename + ": actual .atto guest must return 42; stdout="
                            + stdout.toString(StandardCharsets.UTF_8)
                            + "; stderr=" + stderr.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void physicalAttoNegativesMustReachOwnershipCheckingNotFailParsing() throws Exception {
        String[][] cases = {
                {"take-projected-field-reject.atto", "rt take"},
                {"use-after-take-reject.atto", "moved"},
                {"share-borrow-reject.atto", "rt share"},
                {"copy-only-share-reject.atto", "Share"}
        };
        for (String[] entry : cases) {
            String source = Files.readString(stage(entry[0]), StandardCharsets.UTF_8);
            Ast.Program parsed = assertDoesNotThrow(
                    () -> Parser.parse(source),
                    entry[0] + " must parse before semantic ownership denial");
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OwnershipChecker.check(parsed),
                    entry[0] + " must be rejected by the ownership checker");
            assertTrue(denied.getMessage().toLowerCase().contains(entry[1].toLowerCase()),
                    entry[0] + " unexpected semantic denial: " + denied.getMessage());
        }
    }
}
