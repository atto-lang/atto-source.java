package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Atto guest with ZERO telemetry calls: the runtime's compiler-known
 * callable dispatcher must emit bounded numeric timing only when operator
 * tracing is explicitly enabled. Does not claim HTTP, actor or OTLP spans.
 */
@Timeout(30)
final class AutomaticCallProbeGuestTest {
    private static final String SOURCE = """
            pub fnc double_value(int value): int {
              return value * 2;
            }
            pub fnc main(): void {
              stdio.stdout.write(double_value(21));
              return;
            }
            """;

    private record Result(String stdout, String stderr) {}

    private static Result run(String source, String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Source atto = Source.newBuilder(OresLanguage.ID, source, "auto-calls.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, args)
                .out(stdout).err(stderr).build()) {
            ctx.eval(atto);
        }
        return new Result(stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unchangedGuestWithDisabledProbesHasNoNumericTelemetry() throws Exception {
        Result result = run(SOURCE);
        assertEquals("42", result.stdout());
        assertFalse(result.stderr().contains("atto-internal-calls.v0"),
                result.stderr());
    }

    @Test
    void sameGuestEmitsNumericCallReceiptsWhenOperatorEnablesCalls() throws Exception {
        Result result = run(SOURCE,
                "--ores-telemetry=calls", "--ores-telemetry-receipt=stderr");
        assertEquals("42", result.stdout());
        assertTrue(result.stderr().contains("\"schema\":\"atto-internal-calls.v0\""),
                result.stderr());
        assertTrue(result.stderr().contains("\"kind\":\"call\""), result.stderr());
        assertTrue(result.stderr().contains("\"phase_id\":100"), result.stderr());
        assertTrue(result.stderr().contains("\"kind\":\"end\""), result.stderr());
    }

    @Test
    void explicitOffCannotClaimAReceipt() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> run(SOURCE, "--ores-telemetry=off",
                        "--ores-telemetry-receipt=stderr"));
        assertTrue(failure.toString().contains("telemetry receipt requires"),
                failure.toString());
    }

    @Test
    void typoedTelemetryModeFailsClosed() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> run(SOURCE, "--ores-telemetry=fulll"));
        assertTrue(failure.toString().contains("unsupported telemetry mode"),
                failure.toString());
    }

    @Test
    void guestExceptionsAreNotSwallowedByProbeExit() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub fnc fail_call(): void {
                  val Array<int> empty = new Array<int>();
                  stdio.stdout.write(empty.get(0));
                  return;
                }
                pub fnc main(): void {
                  fail_call();
                  return;
                }
                """, "auto-calls-failure.atto").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, "--ores-telemetry=calls",
                        "--ores-telemetry-receipt=stderr")
                .out(stdout).err(stderr).build()) {
            assertThrows(PolyglotException.class, () -> ctx.eval(source));
        }
        String receipt = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(receipt.contains("\"kind\":\"end\""), receipt);
        assertTrue(receipt.contains("\"outcome\":1"), receipt);
    }
}
