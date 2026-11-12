package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Genuine .atto guest acceptance; no OTLP or auto-probe parity claim. */
final class NativeTelemetryScopeGuestTest {
    @Test
    void nativeAttoModuleHasCanonicalAndLegacyImport() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("telemetry.atto"));
        assertEquals("telemetry.atto", CoreLibrarySources.fileName("ores.std/telemetry"));
        String source = CoreLibrarySources.source("ores.std/telemetry");
        assertEquals(source, CoreLibrarySources.source("std/telemetry"));
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("ores.std/telemetry"),
                CoreLibrarySources.fileName("ores.std/telemetry"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("stdio."));
        assertFalse(source.contains("await "));
    }

    @Test
    void disabledSpanAndUsingScopeRunInNativeAttoGuest() throws Exception {
        Path guest = Files.createTempFile("atto-telemetry-scope-", ".atto");
        try {
            Files.writeString(guest, """
                    import module telemetry from "ores.std/telemetry";
                    pub routine main(): void {
                      val manual = telemetry.span("manual");
                      stdio.println(manual.is_recording());
                      stdio.println(manual.is_ended());
                      manual.end();
                      stdio.println(manual.is_ended());
                      manual.end();
                      stdio.println(manual.is_ended());
                      using telemetry.span("checkout") as span {
                        stdio.println(span.is_recording());
                        stdio.println(span.is_ended());
                      }
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals("false\nfalse\ntrue\ntrue\nfalse\nfalse",
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(guest);
        }
    }
}
