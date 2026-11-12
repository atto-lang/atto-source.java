package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine source-level .atto OTLP/JSON codec fixture; authored, NOT_EXECUTED
 * on the JDK25/GraalVM compiler branch yet. No HTTP or collector in this test.
 */
@Timeout(30)
final class NativeTelemetryOtlpCodecGuestTest {
    @Test
    void nativeAttoEncodesProtoJsonSpanIdsQuotedFixed64AndParent() throws Exception {
        Path guest = Files.createTempFile("atto-otlp-json-", ".atto");
        try {
            Files.writeString(guest, """
                    import module telemetry from "ores.std/telemetry";
                    pub routine main(): void {
                      val batch = telemetry.span_batch(2);
                      val server = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "1111111111111111", "",
                        201, 2, 1780000000000000000, 1780000000000000100, 200, false);
                      val actor = telemetry.completed_span(
                        "0123456789abcdef0123456789abcdef",
                        "2222222222222222", "1111111111111111",
                        501, 1, 1780000000000000010, 1780000000000000090, 0, true);
                      batch.offer(server);
                      batch.offer(actor);
                      stdio.println(batch.to_otlp_json());
                      stdio.println(batch.count());
                      batch.clear_acked();
                      stdio.println(batch.count());
                      return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err);
            String[] lines = out.toString(StandardCharsets.UTF_8)
                    .replace("\r", "").trim().split("\n");
            assertEquals(3, lines.length, () -> err.toString(StandardCharsets.UTF_8));
            String json = lines[0];
            assertTrue(json.startsWith("{\"resourceSpans\":[{\"resource\":"),
                    json);
            assertTrue(json.contains("\"traceId\":\"0123456789abcdef0123456789abcdef\""), json);
            assertTrue(json.contains("\"parentSpanId\":\"1111111111111111\""), json);
            assertTrue(json.contains("\"name\":\"HTTP SERVER\""), json);
            assertTrue(json.contains("\"name\":\"Actor.receive\""), json);
            assertTrue(json.contains("\"kind\":2"), json);
            assertTrue(json.contains("\"kind\":1"), json);
            assertTrue(json.contains("\"startTimeUnixNano\":\"1780000000000000000\""), json);
            assertTrue(json.contains("\"endTimeUnixNano\":\"1780000000000000100\""), json);
            assertTrue(json.contains("\"status\":{\"code\":2}"), json);
            assertFalse(json.contains("\"startTimeUnixNano\":1780000000000000000"),
                    "OTLP fixed64 timestamps must be quoted decimal strings");
            assertFalse(json.contains("\"secret\""), "no user-provided arbitrary attributes");
            assertEquals("2", lines[1]);
            assertEquals("0", lines[2]);
        } finally {
            Files.deleteIfExists(guest);
        }
    }
    @Test
    void nativeAttoRejectsMalformedWrongWidthAndAllZeroIds() throws Exception {
        Path guest = Files.createTempFile("atto-otlp-id-guard-", ".atto");
        try {
            Files.writeString(guest, """
                    import module telemetry from "ores.std/telemetry";
                    pub routine main(): void {
                      stdio.println(telemetry.is_exact_lower_hex(
                        "0123456789abcdef0123456789abcdef", 32));
                      stdio.println(telemetry.is_exact_lower_hex(
                        "abcdef0123456789", 16));
                      stdio.println(telemetry.is_exact_lower_hex(
                        "abcdef012345678", 16));
                      stdio.println(telemetry.is_exact_lower_hex(
                        "0000000000000000", 16));
                      stdio.println(telemetry.is_exact_lower_hex(
                        "ABCDEF0123456789", 16));
                      stdio.println(telemetry.is_exact_lower_hex(
                        "abcdefXmalicious", 16));
                      stdio.println(telemetry.is_exact_lower_hex("", 16));
                      return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            LinkedProgramRunner.run(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err);
            assertEquals("true\\ntrue\\nfalse\\nfalse\\nfalse\\nfalse\\nfalse",
                    out.toString(StandardCharsets.UTF_8).trim().replace("\\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(guest);
        }
    }

}
