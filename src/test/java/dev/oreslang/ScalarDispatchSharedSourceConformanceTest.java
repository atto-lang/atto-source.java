package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the exact Git blob bytes in platform-conformance #13 and LLVM #46.
 * No rewritten equivalents: source identity is checked before admission.
 */
final class ScalarDispatchSharedSourceConformanceTest {
    @TempDir Path directory;

    private record Fixture(String name, String gitBlob, boolean accepted) { }

    private static final Fixture[] CASES = {
            new Fixture("ordered-match-hit.ores",
                    "dc799f2791127948853e46c77c8eff35c25c5fb8", true),
            new Fixture("ordered-match-wildcard-fallback.ores",
                    "297fd3c1b17374883b6c1cc4c13dfe31926cb0d0", true),
            new Fixture("switch-default-before-cases.ores",
                    "619918b3a99157c366fe00fc2b803880e871ebaa", true),
            new Fixture("reject-match-wildcard-before-concrete.ores",
                    "e0ea232bbe7849abda362b801d9e6db9a4a1e5c7", false),
            new Fixture("reject-match-bare-default.ores",
                    "d662873b98a0374e697daef214fb177320e9c96c", false),
            new Fixture("reject-match-on-default.ores",
                    "4b3db122c45434728d607073f162a6d1f0c9fb5c", false)
    };

    private byte[] source(Fixture fixture) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/dispatch/v1/" + fixture.name())) {
            assertNotNull(input, "missing conformance source " + fixture.name());
            byte[] bytes = input.readAllBytes();
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            sha1.update(bytes);
            assertEquals(fixture.gitBlob(), HexFormat.of().formatHex(sha1.digest()),
                    "source bytes differ from the pinned LLVM conformance fixture");
            return bytes;
        }
    }

    @Test
    void sharedPositiveFixturesExecuteWithTheSameI64ExitValue() throws Exception {
        for (Fixture fixture : CASES) {
            if (!fixture.accepted()) continue;
            byte[] bytes = source(fixture);
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> {
                var checked = TypeChecker.check(Parser.parse(text));
                OwnershipChecker.check(checked);
            }, fixture.name());
            Path path = directory.resolve(fixture.name());
            Files.write(path, bytes);
            int actual = LinkedProgramRunner.runWithExitStatus(path,
                    IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream()).exitStatus();
            assertEquals(42, actual, fixture.name());
        }
    }

    @Test
    void sharedNegativeFixturesAreRejectedBeforeGuestExecution() throws Exception {
        for (Fixture fixture : CASES) {
            if (fixture.accepted()) continue;
            String source = new String(source(fixture), StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> {
                var checked = TypeChecker.check(Parser.parse(source));
                OwnershipChecker.check(checked);
            }, fixture.name());
        }
    }
}
