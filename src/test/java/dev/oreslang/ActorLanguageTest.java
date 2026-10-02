package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLanguageTest {

    @Test
    void namedFunctionCanRunAsActorEntrypoint() throws Exception {
        String output = run("""
                fnc worker(str message) => void {
                  stdio.stdout.write(message);
                  return;
                }

                pub routine main() => void {
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, "hello-actor");
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  return;
                }
                """);

        assertEquals("hello-actor", output);
    }

    @Test
    void singletonActorReusesOneNamedProcess() throws Exception {
        String output = run("""
                fnc worker(str message) => void {
                  stdio.stdout.write(message);
                  return;
                }

                pub routine main() => void {
                  val first = actor.singleton("cache_primary", worker);
                  val second = actor.singleton("cache_primary", worker);
                  stdio.stdout.write(first.id == second.id ? "same:" : "different:");
                  actor.send(second, "ok");
                  actor.stop(first);
                  actor.join(first);
                  return;
                }
                """);

        assertEquals("same:ok", output);
    }

    @Test
    void lexicalLambdaCannotBecomeActorEntrypoint() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                pub routine main() => void {
                  val int outer = 7;
                  val callback = |str message| -> {
                    stdio.stdout.write(message);
                    stdio.stdout.write(outer);
                    return;
                  };
                  actor.spawn(callback);
                  return;
                }
                """));

        String message = failure instanceof PolyglotException polyglot ? polyglot.getMessage() : failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("nlex") || message.contains("actor"));
    }

    @Test
    void processGcIsExplicitAndCallable() throws Exception {
        String previous = System.getProperty("oreslang.gc.host-hint");
        System.setProperty("oreslang.gc.host-hint", "false");
        try {
            assertEquals("ok", run("""
                    pub routine main() => void {
                      process.gc();
                      stdio.stdout.write("ok");
                      return;
                    }
                    """));
        } finally {
            if (previous == null) System.clearProperty("oreslang.gc.host-hint");
            else System.setProperty("oreslang.gc.host-hint", previous);
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actors.ores")
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
