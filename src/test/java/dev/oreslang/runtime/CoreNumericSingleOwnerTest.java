package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hosted compatibility API must execute the canonical Oreslang core source. */
final class CoreNumericSingleOwnerTest {
    @TempDir Path temp;

    @Test
    void legacyCoreIsOnlyAnOreslangFacadeOverFreestandingNumeric() throws Exception {
        String legacy = CoreLibrarySources.source("std/core");
        String canonical = CoreLibrarySources.oresCoreSource("ores.core/numeric");

        assertTrue(legacy.contains("import module numeric from \"ores.core/numeric\""));
        assertTrue(legacy.contains("return numeric.min_int(left, right);"));
        assertTrue(legacy.contains("return numeric.max_int(left, right);"));
        assertTrue(legacy.contains("return numeric.clamp_int(value, lower, upper);"));
        assertFalse(legacy.contains("if "), "legacy core must not reimplement numeric decisions");
        assertTrue(canonical.contains("define module numeric"));
        // Pinned against oreslang-common-source/contracts/library/v1/canonical-numeric-source.json.
        // Every compiler must consume these exact native Oreslang source bytes.
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertEquals("3d1d7b77068dd02b3d60b30b72f29a4537a933134ca79e1e81df096dc56f5f33",
                digest, "canonical core source drifted; update shared manifest and consumers");
        assertFalse(canonical.contains("java:"));
    }

    @Test
    void linkedLegacyCallsExecuteCanonicalSourceWithExactSigned64Results() throws Exception {
        Path entry = temp.resolve("numeric.ores");
        Files.writeString(entry, """
                import module core from "std/core";
                import module numeric from "ores.core/numeric";

                pub routine main(): void {
                  stdio.println(core.min_int(-9223372036854775808, 9223372036854775807));
                  stdio.println(numeric.min_int(-9223372036854775808, 9223372036854775807));
                  stdio.println(core.max_int(9007199254740993, 9007199254740992));
                  stdio.println(numeric.max_int(9007199254740993, 9007199254740992));
                  stdio.println(core.clamp_int(0, 9223372036854775807, -9223372036854775808));
                  stdio.println(numeric.clamp_int(0, 9223372036854775807, -9223372036854775808));
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);

        assertTrue(build.units().containsKey("@std/core.ores"), "legacy facade was not linked");
        assertTrue(build.units().containsKey("@ores.core/numeric.ores"),
                "canonical Oreslang implementation was not linked");
        assertEquals(
                "-9223372036854775808\n"
                + "-9223372036854775808\n"
                + "9007199254740993\n"
                + "9007199254740993\n"
                + "9223372036854775807\n"
                + "9223372036854775807",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim(),
                () -> "stderr=" + err.toString(StandardCharsets.UTF_8));
    }
}
