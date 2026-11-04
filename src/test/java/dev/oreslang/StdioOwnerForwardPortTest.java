package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compatibility floor while the PR #538 single-owner stdio redesign is reconciled. */
final class StdioOwnerForwardPortTest {
    @Test
    void ordinaryGuestStdoutMaintainsWriteOrderWithHostAccessDenied() throws Exception {
        var out = new ByteArrayOutputStream();
        String code = """
                pub routine main(): void {
                  stdio.stdout.write("first");
                  stdio.stdout.write("|second");
                  return;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code,
                    "stdio-single-writer-reconciliation.ores").build());
        }
        assertEquals("first|second", out.toString(StandardCharsets.UTF_8));
    }
}
