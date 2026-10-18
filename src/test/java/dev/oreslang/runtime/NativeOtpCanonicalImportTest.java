package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpCanonicalImportTest {
    private static final List<String> NAMES = List.of(
            "gen_server", "supervisor", "gen_statem", "application",
            "event_manager", "registry");

    @Test void exactlySixNamesShareStableIdentityAndPackagedSource() throws Exception {
        for (String name : NAMES) {
            String modern = "otp/" + name;
            String legacy = "std/otp/" + name;
            assertTrue(CoreLibrarySources.isCoreImport(modern));
            assertTrue(CoreLibrarySources.isOtpImport(modern));
            assertTrue(CoreLibrarySources.isOtpImport(legacy));
            assertEquals("@std/otp/" + name + ".ores", CoreLibrarySources.unitId(modern));
            assertEquals(CoreLibrarySources.unitId(modern),
                    CoreLibrarySources.unitId(legacy));
            assertEquals(CoreLibrarySources.source(modern),
                    CoreLibrarySources.source(legacy));
        }
        assertFalse(CoreLibrarySources.isOtpImport("std/core"));
        assertFalse(CoreLibrarySources.isCoreImport("otpx/gen_server"));
    }

    @Test void unknownTraversalAndShadowingOtpImportsFailClosed() throws Exception {
        for (String path : List.of("otp/../core", "otp/./gen_server", "otp//gen_server",
                "otp/not_real", "otp/release", "otp/ets", "otp/a/../../core",
                "otp/gen_server?query", "otp/%2e%2e/core", "otp/",
                "otp/gen_server/", "std/otp/gen_server/", "otp/gen_server/.")) {
            assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source(path), path);
        }
        Path root = Files.createTempDirectory("ores-otp-import-shadow-");
        try {
            Path otp = Files.createDirectories(root.resolve("otp"));
            Files.writeString(otp.resolve("gen_server.ores"), """
                    define module Forged as
                      pub fnc forged(): int { return 999; }
                    end
                    """);
            Path entry = root.resolve("main.ores");
            Files.writeString(entry, """
                    import module Modern from "otp/gen_server";
                    import module Legacy from "std/otp/gen_server";
                    pub routine main(): void {
                      stdio.println(Modern.pending_capacity_allows(0, 1).unwrap());
                      stdio.println(Legacy.pending_capacity_allows(1, 1).unwrap());
                      return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err);
            assertTrue(build.units().containsKey("@std/otp/gen_server.ores"));
            assertEquals(2, build.units().size(), "both aliases must share one module");
            assertEquals("true\nfalse", out.toString(StandardCharsets.UTF_8)
                    .trim().replace("\r", ""), () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(root.resolve("otp/gen_server.ores"));
            Files.deleteIfExists(root.resolve("main.ores"));
            Files.deleteIfExists(root.resolve("otp"));
            Files.deleteIfExists(root);
        }
    }
}
