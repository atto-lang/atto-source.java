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

    @Test
    void boundedNativeBufferAccountsForOverflowWithoutIo() throws Exception {
        Path entry = Files.createTempFile("ores-trace-buffer-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Trace from "std/trace";
                    pub routine main(): void {
                      val buffer = Trace.buffer(2);
                      stdio.println(buffer.record(4, 3, 123));
                      stdio.println(buffer.record(4, 4, 8));
                      stdio.println(buffer.record(4, 5, 9));
                      stdio.println(buffer.count());
                      stdio.println(buffer.dropped_count());
                      stdio.println(buffer.at(1).phase_id);
                      stdio.println(buffer.at(0).elapsed_ns);
                      stdio.println(buffer.record(-1, 6, 12));
                      stdio.println(buffer.dropped_count());
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals("true\ntrue\nfalse\n2\n1\n4\n123\nfalse\n1",
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
    @Test
    void independentlyPackagedTelemetryPolicyAndBatchExecuteInGuest() throws Exception {
        String source = CoreLibrarySources.source("std/telemetry");
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/telemetry"),
                CoreLibrarySources.fileName("std/telemetry"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("stdio."));
        assertFalse(source.contains("await "));

        Path entry = Files.createTempFile("atto-telemetry-guest-", ".atto");
        try {
            Files.writeString(entry, """
                    import module Telemetry from "std/telemetry";
                    pub routine main(): void {
                      stdio.println(Telemetry.traceparent_v00_fields_valid(0, 1, 0, 7, 1));
                      stdio.println(Telemetry.traceparent_v00_fields_valid(0, 0, 0, 7, 1));
                      stdio.println(Telemetry.sampled_flag(3));
                      stdio.println(Telemetry.sampling_decision(1, false));
                      stdio.println(Telemetry.decision_is_recording(1));
                      stdio.println(Telemetry.decision_is_sampled(1));
                      stdio.println(Telemetry.dormant_probe_admissible(3, 2, true));
                      stdio.println(Telemetry.dormant_probe_admissible(0, 1, true));
                      val batch = Telemetry.batch(1);
                      stdio.println(batch.record(1, 0, 7, 0, 1, 0, 1, 0, 100, 120, 0));
                      stdio.println(batch.record(1, 0, 8, 0, 2, 0, 1, 0, 100, 120, 0));
                      stdio.println(batch.count());
                      stdio.println(batch.dropped_count());
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            assertEquals(String.join("\n", "true", "false", "true", "1",
                            "true", "false", "true", "false", "true", "false", "1", "1"),
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

}
