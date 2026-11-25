package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real linked Graal/Truffle compilation and guest execution of unchanged
 * OresLLM Models PR #5 bytes in a temporary PRIVATE, review-gated mirror.
 * Not LLVM evidence; do not merge mirrored source without separate approval.
 */
final class OresLlmExactModelGuestTest {
    private static final Path ROOT =
        Path.of("src/test/resources/oresllm-private-mirror").toAbsolutePath().normalize();
    private static final Path MODULE = ROOT.resolve("src/llm_models.ores");
    private static final Path ENTRY = ROOT.resolve("tests/native-contract-smoke.ores");
    private static final String SOURCE_SHA = "02398b2d75f904d53a7104ebc95fe807d00d1306";
    private static final String GUEST_SHA = "2a4fb075a40b249303312fa3cac073a0418c5bf8";

    private static String gitBlob(Path path) throws Exception {
        byte[] content = Files.readAllBytes(path);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha1.update(content);
        return HexFormat.of().formatHex(sha1.digest());
    }

    @Test
    void mirrorIsExactlyTheTwoReviewedGitBlobs() throws Exception {
        assertTrue(Files.isRegularFile(MODULE, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(ENTRY, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(MODULE));
        assertFalse(Files.isSymbolicLink(ENTRY));
        assertEquals(SOURCE_SHA, gitBlob(MODULE), "OresLLM model source bytes changed");
        assertEquals(GUEST_SHA, gitBlob(ENTRY), "OresLLM import/guest bytes changed");
        assertTrue(Files.readString(ENTRY).contains("from '../src/llm_models'"));
        try (var stream = Files.walk(ROOT)) {
            Set<Path> actual = stream
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.toString().endsWith(".ores"))
                .map(ROOT::relativize).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(Path.of("src/llm_models.ores"),
                                Path.of("tests/native-contract-smoke.ores")), actual);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void realLinkedModelGuestCompilesAndExecutes() throws Exception {
        mirrorIsExactlyTheTwoReviewedGitBlobs();
        assertNotNull(LinkedProgramRunner.validate(ENTRY),
            "link/typecheck must resolve the real imported OresLLM Models module");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
            ENTRY, IsolatePolicy.developer(),
            ExecutionProfile.parse("jit", "server"), stdout, stderr);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(0, result.exitStatus(), "guest failed\n" + err + "\n" + out);
        assertEquals("ORES-MODEL-CONTRACT-OK", out.strip(), "unexpected stdout\n" + err);
        assertTrue(err.isBlank(), "guest emitted unexpected stderr: " + err);
    }
}
