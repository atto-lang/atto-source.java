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
 * One physical .atto fixture per JUnit method: the Surefire receipt proves
 * each distinct program executed, rather than one loop test standing in for
 * eight source oracles. These are Java/Graal tests, not five-backend parity.
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

    private void guestExitsFortyTwo(String filename) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                stage(filename), IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), stdout, stderr);
        assertEquals(42, result.exitStatus(), filename + ": actual .atto guest return; stdout="
                + stdout.toString(StandardCharsets.UTF_8)
                + "; stderr=" + stderr.toString(StandardCharsets.UTF_8));
    }

    private void parserPassesButOwnershipRejects(String filename, String expected) throws Exception {
        String source = Files.readString(stage(filename), StandardCharsets.UTF_8);
        Ast.Program parsed = assertDoesNotThrow(
                () -> Parser.parse(source),
                filename + " must parse before semantic ownership denial");
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed),
                filename + " must be rejected by ownership analysis");
        assertTrue(denied.getMessage() != null
                        && denied.getMessage().toLowerCase().contains(expected.toLowerCase()),
                filename + ": unexpected semantic diagnostic: " + denied.getMessage());
    }

    @Test void copyScalarGuestExitsFortyTwo() throws Exception {
        guestExitsFortyTwo("copy-scalar-ok.atto");
    }
    @Test void borrowedScalarCopyGuestExitsFortyTwo() throws Exception {
        guestExitsFortyTwo("borrow-scalar-copy-ok.atto");
    }
    @Test void namedOwnerTakeGuestExitsFortyTwo() throws Exception {
        guestExitsFortyTwo("take-named-owner-ok.atto");
    }
    @Test void shareScalarGuestExitsFortyTwo() throws Exception {
        guestExitsFortyTwo("share-scalar-ok.atto");
    }

    @Test void projectedFieldTakeIsOwnershipRejectedAfterParsing() throws Exception {
        parserPassesButOwnershipRejects("take-projected-field-reject.atto", "rt take");
    }
    @Test void useAfterTakeIsOwnershipRejectedAfterParsing() throws Exception {
        parserPassesButOwnershipRejects("use-after-take-reject.atto", "moved");
    }
    @Test void publishingBorrowIsOwnershipRejectedAfterParsing() throws Exception {
        parserPassesButOwnershipRejects("share-borrow-reject.atto", "rt share");
    }
    @Test void copyOnlyHandleSharingIsOwnershipRejectedAfterParsing() throws Exception {
        parserPassesButOwnershipRejects("copy-only-share-reject.atto", "Share");
    }
}
