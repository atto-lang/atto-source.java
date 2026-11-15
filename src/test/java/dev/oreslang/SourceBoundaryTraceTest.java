package dev.oreslang;

import dev.oreslang.runtime.SourceBoundaryTrace;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

final class SourceBoundaryTraceTest {
    @Test
    void asyncFailureRetainsSourceNamesAcrossResumption() {
        try (var context = Context.newBuilder("ores").allowAllAccess(true).build()) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("ores", """
                    async fnc leaf(): int {
                      rt cooperate;
                      val int zero = 0; // Integer remainder by zero raises during resumed evaluation.
                      return Integer.rem(1, zero);
                    }
                    async fnc middle(): int {
                      val int result = await leaf();
                      return result;
                    }
                    pub routine main(): void {
                      val int result = await middle();
                      stdio.println(result);
                      return;
                    }
                    """));
            var text = new StringWriter();
            error.printStackTrace(new PrintWriter(text));
            assertTrue(text.toString().contains("resumed source task leaf"), text.toString());
            assertTrue(text.toString().contains("source task middle"), text.toString());
            assertTrue(text.toString().contains("source task main"), text.toString());
        }
    }

    @Test
    void actorFailureKeepsReceiveAndAwaitingSupervisorFrames() {
        for (String kind : new String[] {"actor", "isoactor"}) {
            try (var context = Context.newBuilder("ores").allowAllAccess(true).build()) {
                var error = assertThrows(PolyglotException.class, () -> context.eval("ores", """
                        define %s Broken as
                          receive(ActorMail<String> mail): void {
                            rt cooperate;
                            val Option<int> empty = None;
                            stdio.println(empty.unwrap());
                            return;
                          }
                        end
                        pub routine main(): void {
                          val worker = spawn Broken();
                          await worker.get_ready();
                          worker.send("go");
                          await worker.get_done();
                          return;
                        }
                        """.formatted(kind)));
                var text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                assertTrue(text.toString().contains("source task receive"), text.toString());
                assertTrue(text.toString().contains("source task main"), text.toString());
            }
        }
    }

    @Test
    void diagnosticsAreBoundedAndPreserveFailureIdentity() {
        var cause = new IllegalStateException("native failure");
        for (int i = 0; i < 1000; i++) {
            assertSame(cause, SourceBoundaryTrace.record(cause, "test.ores", "caller", "await"));
        }
        assertEquals(64, cause.getSuppressed().length);
        assertEquals("native failure", cause.getMessage());
        assertEquals(0, cause.getSuppressed()[0].getStackTrace().length);
    }

    @Test
    void sourceMetadataIsBoundedAndCannotForgeStackTraceLines() {
        var cause = new IllegalStateException("original cause");
        String malicious = "orders.ores\\nFORGED-PANIC\\u001b[31m\\u202e"
                .replace("\\n", "\n")
                .replace("\\u001b", "\u001b")
                .replace("\\u202e", "\u202e")
                + "x".repeat(5000);
        for (int i = 0; i < 1000; i++) {
            assertSame(cause, SourceBoundaryTrace.record(
                    cause, malicious, "leaf", "resumed source task"));
        }

        assertEquals(64, cause.getSuppressed().length);
        String label = cause.getSuppressed()[0].getMessage();
        assertTrue(label.length() <= 350, label);
        assertFalse(label.contains("\n"), label);
        assertFalse(label.contains("\r"), label);
        assertFalse(label.contains("\u001b"), label);
        assertFalse(label.contains("\u202e"), label);
        assertEquals("original cause", cause.getMessage());
        assertEquals(0, cause.getSuppressed()[0].getStackTrace().length);
        assertNotNull(SourceBoundaryTrace.record(cause, null, null, null));
    }

}
