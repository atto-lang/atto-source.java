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
                      manual.finish();
                      stdio.println(manual.is_ended());
                      manual.finish();
                      stdio.println(manual.is_ended());
                      using telemetry.span("checkout") as span {
                        stdio.println(span.recording());
                      }
                      using telemetry.span() as unnamed {
                        stdio.println(unnamed.recording());
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
    @Test
    void nativeActorOwnedBatchIsBoundedAndDoesNotBlockOnOverflow() throws Exception {
        Path guest = Files.createTempFile("atto-telemetry-batch-", ".atto");
        try {
            Files.writeString(guest, """
                    import module telemetry from "ores.std/telemetry";
                    pub routine main(): void {
                      val batch = telemetry.span_batch(2);
                      val first = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "1111111111111111", "",
                        201, 2, 1000, 1100, 200, false);
                      val second = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "2222222222222222", "1111111111111111",
                        501, 1, 1010, 1090, 0, false);
                      val third = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "3333333333333333", "2222222222222222",
                        200, 3, 1020, 1080, 200, false);
                      stdio.println(batch.offer(first));
                      stdio.println(batch.offer(second));
                      stdio.println(batch.offer(third));
                      stdio.println(batch.count());
                      stdio.println(batch.dropped_count());
                      stdio.println(batch.at(1).parent_span_id);
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals("true\\ntrue\\nfalse\\n2\\n1\\n1111111111111111",
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(guest);
        }
    }

}
