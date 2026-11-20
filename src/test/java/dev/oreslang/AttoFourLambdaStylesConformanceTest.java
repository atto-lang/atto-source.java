package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact eight-source fixture mirror of atto-platform-conformance PR #240.
 * Positive tests require real Java/Truffle guest execution, not parser-only
 * acceptance. Native Image AOT and other backends require separate receipts.
 */
final class AttoFourLambdaStylesConformanceTest {
    private static final Path ROOT = Path.of("tests/fixtures/lambda-four-styles-v1");

    // Source Git blob identities from atto-platform-conformance PR #240.
    // Reject fixture drift before claiming cross-repository parity.
    private static final Map<String, String> BLOBS = Map.of(
        "positive/type-first-infer.atto", "571c0fd3c75e922336c3e65e9577a587834ba5f0",
        "positive/name-first-infer.atto", "0fd9e2b5f9f986a6c72cd86acf9f43cc095419e6",
        "positive/name-first-arrow-void.atto", "0edf942651f43ced2f65943d1d16d33b0ecdb66d",
        "positive/name-first-colon-void.atto", "e71f6a506d2f598d136a5a7b0157824298487fda",
        "negative/duplicate-param.atto", "d0e7b3842eb8c12be2ea032e6a1bd371216d9d23",
        "negative/void-return-value.atto", "91e113fa848c4f406dd9aefe38c0cc5b6b158d20",
        "negative/void-parameter.atto", "6f3dd803afc463f918c4b16be970b59da20cfe3d",
        "negative/legacy-result-missing-block.atto", "01323cc7aa918f41170553591defa3f40f0e2d10"
    );

    @Test
    void fixtureBlobsMatchConformancePr240() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        for (var entry : BLOBS.entrySet()) {
            byte[] bytes = Files.readAllBytes(ROOT.resolve(entry.getKey()));
            digest.reset();
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(bytes);
            assertEquals(entry.getValue(), HexFormat.of().formatHex(digest.digest()),
                    entry.getKey() + ": changed source fixture");
        }
    }

    @Test
    void fourLambdaHeadersExecuteAsCheckedAttoGuestsReturning42() throws Exception {
        for (String name : new String[] {
                "type-first-infer", "name-first-infer",
                "name-first-arrow-void", "name-first-colon-void"
        }) {
            Path fixture = ROOT.resolve("positive/" + name + ".atto");
            // Validate the exact source rather than using a synthetic
            // in-memory AST or a host-side callback returning 42.
            LinkedProgramRunner.validate(fixture, Map.of());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var result = LinkedProgramRunner.runWithExitStatus(
                    fixture, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    stdout, stderr);
            assertEquals(42, result.exitStatus(), name + ": guest main exit status");
            assertEquals("", stdout.toString(StandardCharsets.UTF_8), name + ": stdout");
            assertEquals("", stderr.toString(StandardCharsets.UTF_8), name + ": stderr");
        }
    }

    @Test
    void malformedLambdasRejectBeforeGuestExecution() {
        for (String name : new String[] {
                "duplicate-param", "void-return-value",
                "void-parameter", "legacy-result-missing-block"
        }) {
            Path fixture = ROOT.resolve("negative/" + name + ".atto");
            assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.validate(fixture, Map.of()),
                    name + ": invalid lambda must not be linked to a runnable guest");
        }
    }
}
