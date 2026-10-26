package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FutureCancellationLanguageTest {

    @Test
    void cancellingBlockedChannelWriteDetachesWaiterAndReusesCapacity() throws Exception {
        String program = """
                pub async fnc main(): void {
                  val Channel<int> queue = Channel.new<int>(1);

                  val Future<void> first = nb writech queue, 1;
                  await first;

                  val Future<void> blocked = nb writech queue, 2;
                  stdio.println(blocked.is_done());
                  stdio.println(blocked.cancel());
                  stdio.println(blocked.is_cancelled());
                  stdio.println(blocked.is_done());

                  stdio.println(readch queue);
                  stdio.println(try writech queue, 3);
                  stdio.println(readch queue);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals(
                "false\ntrue\ntrue\ntrue\n1\ntrue\n3\n",
                run(program));
    }

    @Test
    void settledFutureCannotBeCancelledRetroactively() throws Exception {
        String program = """
                pub async fnc main(): void {
                  val Channel<int> queue = Channel.new<int>(1);
                  val Future<void> sent = nb writech queue, 7;
                  await sent;
                  stdio.println(sent.is_done());
                  stdio.println(sent.is_cancelled());
                  stdio.println(sent.cancel());
                  stdio.println(readch queue);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true\nfalse\nfalse\n7\n", run(program));
    }

    @Test
    void cancellationRequiresOwnedNamedFutureAuthority() {
        IllegalArgumentException borrowed = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Channel<int> queue = Channel.new<int>(1);
                          val Future<void> pending = nb writech queue, 1;
                          val borrowed = rt borrow pending;
                          borrowed.cancel();
                          return;
                        }
                        """));
        assertTrue(borrowed.getMessage().contains("Future.cancel requires owned cancellation authority"));

        IllegalArgumentException temporary = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Channel<int> queue = Channel.new<int>(1);
                          (nb writech queue, 1).cancel();
                          return;
                        }
                        """));
        assertTrue(temporary.getMessage().contains("Future.cancel requires an owned named Future local"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "future-cancel.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
