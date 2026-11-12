package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GuestCallDepthLanguageTest {
    @Test
    void nonTailRecursionFailsAtGuestBudgetAndFreshExecutionStillRuns() throws Exception {
        // The Java reference compiler still consumes legacy .ores on main.
        // The canonical .atto version belongs in backend conformance once
        // parser/import admission is independently executable.
        Source recursive = Source.newBuilder(OresLanguage.ID, """
                fnc descend(int n): int {
                  if n == 0; do
                    return 0;
                  else
                    return 1 + descend(n - 1);
                  fi
                }

                pub routine main(): void {
                  stdio.stdout.write(descend(10000));
                  return;
                }
                """, "guest-depth-budget.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context ctx = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
            RuntimeException failed = assertThrows(
                    RuntimeException.class, () -> ctx.eval(recursive));
            assertTrue(failed.toString().contains("guest call depth exceeded"),
                    () -> "expected controlled budget failure, got: " + failed);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source healthy = Source.newBuilder(OresLanguage.ID, """
                pub routine main(): void {
                  stdio.stdout.write(42);
                  return;
                }
                """, "healthy-after-budget.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context ctx = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            ctx.eval(healthy);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }
}
