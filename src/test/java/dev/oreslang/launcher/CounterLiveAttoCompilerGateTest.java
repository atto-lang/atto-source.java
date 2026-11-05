package dev.oreslang.launcher;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the *real* Java compiler validation path on the immutable Phoenix
 * CounterLive actor source. It is NOT a WebSocket or guest-execution proof.
 * The test must fail if the current compiler cannot admit this .atto syntax.
 */
final class CounterLiveAttoCompilerGateTest {
    private static final String EXPECTED_GIT_BLOB = "242168d28b84d7380eec5a4fcf9aa98695de3b74";
    private static final String SOURCE_RESOURCE = "/atto/liveview/counter_live.atto";

    private static Path sourcePath() throws Exception {
        var url = CounterLiveAttoCompilerGateTest.class.getResource(SOURCE_RESOURCE);
        assertTrue(url != null, "immutable CounterLive .atto fixture missing");
        return Path.of(url.toURI());
    }

    @Test
    void immutablePhoenixGuestFixtureMatchesExactGitBlob() throws Exception {
        byte[] source = Files.readAllBytes(sourcePath());
        byte[] prefix = ("blob " + source.length + "\0").getBytes(StandardCharsets.US_ASCII);
        MessageDigest sha = MessageDigest.getInstance("SHA-1"); // Git object identity; not a security MAC.
        sha.update(prefix);
        sha.update(source);
        assertEquals(EXPECTED_GIT_BLOB, HexFormat.of().formatHex(sha.digest()),
                "AttoLang guest source drifted from Phoenix PR #17");
    }

    @Test
    void realCompilerAdmitsPhoenixCounterLiveActorSource() throws Exception {
        Path source = sourcePath();
        IncrementalCompiler.BuildResult result = LinkedProgramRunner.validate(source);
        assertFalse(result.units().isEmpty(), "compiler must produce an actual source unit");
        assertTrue(result.units().containsKey(
                source.toAbsolutePath().normalize().toString().replace('\\', '/')),
                "compiler must validate the exact .atto guest, not a host substitute");
    }

    @Test
    void realCliCheckAdmitsSamePhoenixCounterLiveFixture() throws Exception {
        assertEquals(0, OresMain.exitStatusForArgs(
                new String[]{"--check", sourcePath().toString()}),
                "real launcher --check must admit the exact .atto actor source");
    }

    /**
     * A negative assertion is meaningful only if the SAME temporary source path
     * admits the unmodified guest first. This prevents a missing project config,
     * an unsupported .atto dialect, or a broken source path from masquerading
     * as successful enforcement of the sealed actor-state rule.
     */
    private static void admitUnmodifiedCompilerControl(Path path, String original) throws Exception {
        Files.writeString(path, original, StandardCharsets.UTF_8);
        IncrementalCompiler.BuildResult result = LinkedProgramRunner.validate(path);
        assertTrue(result.units().containsKey(
                path.toAbsolutePath().normalize().toString().replace('\\', '/')),
                "unmodified .atto control must pass compiler validation at the same source path");
    }

    private static void admitUnmodifiedCliControl(Path path, String original) throws Exception {
        Files.writeString(path, original, StandardCharsets.UTF_8);
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{"--check", path.toString()}),
                "unmodified .atto control must pass CLI checking at the same source path");
    }

    private static String mutateOnce(String original, String before, String after) {
        int first = original.indexOf(before);
        assertTrue(first >= 0 && original.indexOf(before, first + before.length()) == -1,
                "mutation anchor must occur exactly once in the immutable guest fixture");
        return original.substring(0, first) + after + original.substring(first + before.length());
    }

    @Test
    void realCliCheckRejectsDirectSealedStateEscape() throws Exception {
        String original = Files.readString(sourcePath());
        String invalid = mutateOnce(original,
                "return self.handle_state(critical-read |s| -> s.count)", "return self.count");
        Path source = Files.createTempFile("counter-live-cli-state-", ".atto");
        try {
            admitUnmodifiedCliControl(source, original);
            Files.writeString(source, invalid, StandardCharsets.UTF_8);
            assertEquals(1, OresMain.exitStatusForArgs(new String[]{"--check", source.toString()}),
                    "invalid direct sealed-state read must fail semantically, not via CLI usage error");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void realCompilerRejectsDirectAccessToSealedActorState() throws Exception {
        String original = Files.readString(sourcePath());
        String invalid = mutateOnce(original,
                "return self.handle_state(critical-read |s| -> s.count)", "return self.count");
        Path source = Files.createTempFile("counter-live-illegal-state-", ".atto");
        try {
            admitUnmodifiedCompilerControl(source, original);
            Files.writeString(source, invalid, StandardCharsets.UTF_8);
            assertThrows(RuntimeException.class, () -> LinkedProgramRunner.validate(source),
                    "compiler must reject direct state access after proving the same path compiles");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void realCliRejectsWriteThroughSelfInsteadOfSealedLoan() throws Exception {
        String original = Files.readString(sourcePath());
        String invalid = mutateOnce(original,
                "s.count = s.count + message.amount", "self.count = self.count + message.amount");
        Path source = Files.createTempFile("counter-live-cli-write-", ".atto");
        try {
            admitUnmodifiedCliControl(source, original);
            Files.writeString(source, invalid, StandardCharsets.UTF_8);
            assertEquals(1, OresMain.exitStatusForArgs(new String[]{"--check", source.toString()}),
                    "write through self must not bypass the sealed-state loan");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void realCompilerRejectsMutationUnderReadOnlyLoan() throws Exception {
        String original = Files.readString(sourcePath());
        String invalid = mutateOnce(original,
                "handle_state(critical-write |s|", "handle_state(critical-read |s|");
        Path source = Files.createTempFile("counter-live-readonly-write-", ".atto");
        try {
            admitUnmodifiedCompilerControl(source, original);
            Files.writeString(source, invalid, StandardCharsets.UTF_8);
            assertThrows(RuntimeException.class, () -> LinkedProgramRunner.validate(source),
                    "critical-read must never authorize writes through a state loan");
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
