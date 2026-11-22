package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Races a parent's cancel() against the child task's first turns. A cancel
 * landing before the child's first turn used to leave a cleanup-aware task
 * TERMINAL with no outcome, so its Future (and get_done) never settled.
 */
final class SourceTaskCancelRaceTest {
    private static final String[] PROGRAMS = {
            // cancel after one parent yield: lands before, during or after the
            // child's first turn of a tight CPU loop
            """
            pub async fnc main(): void {
              let Future<void> child = task {
                defer stdio.stdout.write("cleanup|");
                let mut int n = 0;
                loop { n = n + 1; }
                return;
              };
              rt cooperate;
              child.cancel();
              stdio.stdout.write(await child.get_done());
              return;
            }
            """,
            // cancel immediately: almost always before the child's first turn
            """
            pub async fnc main(): void {
              let Future<void> child = task {
                defer stdio.stdout.write("cleanup|");
                loop { rt cooperate; }
                return;
              };
              child.cancel();
              stdio.stdout.write(await child.get_done());
              return;
            }
            """ };

    @Test
    void cancelRacingTheChildsFirstTurnAlwaysSettles() throws Exception {
        ExecutorService runner = Executors.newCachedThreadPool();
        try {
            for (String program : PROGRAMS) {
                for (int i = 0; i < 100; i++) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Future<?> run = runner.submit(() -> {
                        try (Context context = Context.newBuilder(OresLanguage.ID)
                                .allowAllAccess(false).out(out).build()) {
                            context.eval(Source.newBuilder(OresLanguage.ID, program, "race.ores")
                                    .mimeType(OresLanguage.MIME_TYPE).build());
                        } catch (java.io.IOException impossible) {
                            throw new IllegalStateException(impossible);
                        }
                    });
                    run.get(10, TimeUnit.SECONDS); // a hang fails here with TimeoutException
                    String printed = out.toString(StandardCharsets.UTF_8);
                    // Either the child started (its defer ran) or it was
                    // cancelled before its first turn (no frames to unwind).
                    assertTrue(printed.equals("cleanup|#cancelled") || printed.equals("#cancelled"),
                            "iteration " + i + " printed " + printed);
                }
            }
        } finally {
            runner.shutdownNow();
        }
    }
}
