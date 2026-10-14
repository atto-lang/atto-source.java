package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler admission on the exact fixture bytes from
 * ores-truffle-oreslang/oreslang-platform-conformance PR #25,
 * source commit 84c04d974ea5385294d4e8627ca0e12417580c74.
 *
 * Git blob identity proves input integrity, not compiler correctness.
 * This checks Java parser/type/ownership admission only. It does not
 * mark any backend confirmed in the conformance manifest, and is not
 * a replacement for runtime execution or Native Image.
 */
final class PinnedValueMatchConformanceTest {
    private static final Map<String, String> SOURCE_BLOBS = Map.of(
            "value-option-braced.ores", "d06e10a5c50d2794521ca0bdbc2aae11ab4e69ca",
            "value-vs-procedural.ores", "6e533b1204e2ae03098739e82fd6a0e729fb0866",
            "value-guard-over-end.ores", "1f4514dc885c52a77c1f6048d7cce50d232b50ed",
            "reject-nonexhaustive.ores", "a44aec379e32d18f502e098ab92bef3fc3375241",
            "reject-mismatched-types.ores", "5640484a4c302032b208d5c498cd1d816e66ca00",
            "reject-no-value.ores", "cfb364840df4303f40cc4a8898d5cbb2553d3b2b"
    );

    private static String readPinned(String name) throws IOException, NoSuchAlgorithmException {
        String expected = SOURCE_BLOBS.get(name);
        assertNotNull(expected, "unrecognized fixture " + name);
        String path = "/value-match/v1/" + name;
        byte[] bytes;
        try (InputStream stream = PinnedValueMatchConformanceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing pinned source resource " + path);
            bytes = stream.readAllBytes();
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        String gitSha = HexFormat.of().formatHex(sha1.digest(bytes));
        assertEquals(expected, gitSha, "fixture bytes differ from canonical Git blob: " + name);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void admit(String name) throws Exception {
        var typed = TypeChecker.check(Parser.parse(readPinned(name)));
        OwnershipChecker.check(typed);
    }

    @Test
    void allSixFixtureFilesMatchCanonicalGitBlobIdentity() throws Exception {
        for (String name : SOURCE_BLOBS.keySet()) {
            assertFalse(readPinned(name).isEmpty(), name);
        }
    }

    @Test
    void threeCanonicalPositiveFixturesMustPassTheRealCompilerFrontend() {
        for (String name : new String[] {
                "value-option-braced.ores",
                "value-vs-procedural.ores",
                "value-guard-over-end.ores"
        }) {
            assertDoesNotThrow(() -> admit(name), "compiler rejected canonical accept fixture: " + name);
        }
    }

    @Test
    void noValueMustFailWithTheSpecificValueMatchParserDiagnostic() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> admit("reject-no-value.ores"));
        assertTrue(failure.getMessage().contains("value match arms require exactly one"),
                "wrong rejection reason: " + failure.getMessage());
    }

    @Test
    void nonExhaustiveMustFailWithTheSpecificCoverageDiagnostic() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> admit("reject-nonexhaustive.ores"));
        assertTrue(failure.getMessage().contains("non-exhaustive match"),
                "wrong rejection reason: " + failure.getMessage());
    }

    @Test
    void mixedArmTypesMustFailAtTheValueMatchTypeBoundary() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> admit("reject-mismatched-types.ores"));
        assertTrue(failure.getMessage().contains("value match arm"),
                "wrong rejection reason: " + failure.getMessage());
    }
}
