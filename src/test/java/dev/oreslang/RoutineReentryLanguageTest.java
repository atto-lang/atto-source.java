package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synchronous callback edges must not bypass the routine recursion contract. */
final class RoutineReentryLanguageTest {
    private static Source source(String text) throws Exception {
        // The Java reference parser still supports legacy .ores source admission.
        return Source.newBuilder(OresLanguage.ID, text, "routine-reentry.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
    }

    @Test
    void indirectTailCallbackCannotReenterRoutine() throws Exception {
        Source recursive = source("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }
                fnc bounce(int value): int {
                  return step(value);
                }
                routine step(int value): int {
                  return apply(bounce, value);
                }
                pub routine main(): void {
                  stdio.stdout.write(step(1));
                  return;
                }
                """);
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
            RuntimeException violation = assertThrows(
                    RuntimeException.class, () -> context.eval(recursive));
            assertTrue(violation.toString().contains("cannot be recursive"),
                    () -> "expected runtime routine-reentry rejection, got: " + violation);
        }
    }

    @Test
    void nestedNonTailCallbackCannotReenterRoutine() throws Exception {
        Source recursive = source("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return 1 + callback(value);
                }
                fnc bounce(int value): int {
                  return step(value);
                }
                routine step(int value): int {
                  return apply(bounce, value);
                }
                pub routine main(): void {
                  stdio.stdout.write(step(1));
                  return;
                }
                """);
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
            RuntimeException violation = assertThrows(
                    RuntimeException.class, () -> context.eval(recursive));
            assertTrue(violation.toString().contains("cannot be recursive"),
                    () -> "expected runtime routine-reentry rejection, got: " + violation);
        }
    }

    @Test
    void separateRoutineCallsAndDeepFncHandoffRemainLegal() throws Exception {
        Source healthy = source("""
                fnc down(int n): int {
                  return n == 0 ? 0 : down(n - 1);
                }
                routine once(int value): int {
                  return value + 1;
                }
                routine handoff(int n): int {
                  return down(n);
                }
                pub routine main(): void {
                  stdio.stdout.write(once(1));
                  stdio.stdout.write("|");
                  stdio.stdout.write(once(2));
                  stdio.stdout.write("|");
                  stdio.stdout.write(handoff(50000));
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(healthy);
        }
        assertEquals("2|3|0", output.toString(StandardCharsets.UTF_8));
    }
}
