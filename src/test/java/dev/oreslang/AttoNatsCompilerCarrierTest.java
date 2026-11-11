package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Temporary exact-blob AttoNATS compiler carrier.
 * These fixtures are mirrored byte-for-byte from atto-poc; this is not
 * a Rust fixture, parser-only smoke, or a claim that NATS is live.
 */
final class AttoNatsCompilerCarrierTest {
    private static String runActualGuest(String fixture) throws Exception {
        Path file = Path.of("ci/atto-nats-fixtures", fixture);
        String source = Files.readString(file);
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), System.getenv(), stdout, stderr);
        return stdout.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    void ordinaryGuestExecutes() throws Exception {
        assertEquals(
                "ATTO_GUEST_EXECUTED:ordinary-main",
                runActualGuest("ordinary_guest.atto"));
    }

    @Test
    void stateCriticalActorTurnExecutesAndJoins() throws Exception {
        assertEquals(
                "ATTO_GUEST_EXECUTED:state-critical",
                runActualGuest("actor_state_critical.atto"));
    }
}
