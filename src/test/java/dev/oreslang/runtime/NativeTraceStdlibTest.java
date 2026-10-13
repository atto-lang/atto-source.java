package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeTraceStdlibTest {
    @Test
    void traceIsPackagedOreslangWithNoHostInteropOrBlockingSink() throws Exception {
        String source = CoreLibrarySources.source("std/trace");
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/trace"),
                CoreLibrarySources.fileName("std/trace"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("stdio."));
        assertFalse(source.contains("network."));
        assertFalse(source.contains("socket."));
        assertFalse(source.contains("await "));
    }

    @Test
    void disabledTraceAndEnabledMonotonicSpanExecuteInGuest() throws Exception {
        Path entry = Files.createTempFile("ores-native-trace-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Trace from "std/trace";
                    pub routine main(): void {
                      val int disabled = Trace.start_ns(false);
                      stdio.println(Trace.duration_ns(disabled));
                      val int started = Trace.start_ns(true);
                      val int duration = Trace.duration_ns(started);
                      stdio.println(duration >= 0);
                      val item = Trace.event(7, 4, duration);
                      stdio.println(item.correlation_id);
                      stdio.println(item.phase_id);
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals("0\ntrue\n7\n4",
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
