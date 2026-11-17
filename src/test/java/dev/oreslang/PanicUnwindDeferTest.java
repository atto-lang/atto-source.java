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
 * Unwind contract (spec ch. 25 "Unwinding"; atto-spec defer-recover-unwind-v1):
 * a panic bypasses catch and trap but still runs every registered defer
 * exactly once, innermost frame first, on the panicking logical stack.
 * Source raise/panic statements are not yet enabled in the Java parser, so
 * these use runtime-originated panics (Option::unwrap on None).
 */
@Timeout(30)
final class PanicUnwindDeferTest {
    @Test
    void runtimePanicRunsDefersInnermostFirstAndBypassesCatch() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                fnc inner(): void {
                  defer stdio.stdout.write("inner|");
                  None.unwrap();
                  return;
                }
                fnc outer(): void {
                  defer stdio.stdout.write("outer|");
                  try { inner(); } catch (err) { stdio.stdout.write("caught|"); }
                  stdio.stdout.write("after|");
                  return;
                }
                pub fnc main(): void {
                  defer stdio.stdout.write("main");
                  outer();
                  return;
                }
                """, output));
        assertTrue(failure.getMessage().contains("Option::unwrap"), failure::getMessage);
        assertEquals("inner|outer|main", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void panicInsideAnActorRunsTheActorsDefersOnItsOwnStack() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                define actor Panicker as
                  receive(ActorMail<int> mail): void {
                    defer stdio.stdout.write("actor-cleanup|");
                    None.unwrap();
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val a = spawn Panicker();
                  await a.get_ready();
                  a.send(1);
                  await a.get_done();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """, output));
        assertTrue(failure.getMessage().contains("Option::unwrap"), failure::getMessage);
        assertEquals("actor-cleanup|", output.toString(StandardCharsets.UTF_8));
    }

    private static void run(String sourceText, ByteArrayOutputStream output) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .arguments(OresLanguage.ID,
                        new String[] {"--ores-capabilities=ACTOR_SPAWN,SHARED_MEMORY,STDOUT"})
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, sourceText, "panic-unwind-defer.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
    }
}
