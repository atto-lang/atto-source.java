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
    void realCompilerRejectsDirectAccessToSealedActorState() throws Exception {
        String original = Files.readString(sourcePath());
        String sealedRead = "return self.handle_state(critical-read |s| -> s.count)";
        assertTrue(original.contains(sealedRead), "fixture's sealed-state syntax changed");
        String illegal = original.replace(sealedRead, "return self.count");
        Path source = Files.createTempFile("counter-live-illegal-state-", ".atto");
        try {
            Files.writeString(source, illegal, StandardCharsets.UTF_8);
            assertThrows(RuntimeException.class, () -> LinkedProgramRunner.validate(source),
                    "compiler must reject an actor state read outside critical-read");
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
