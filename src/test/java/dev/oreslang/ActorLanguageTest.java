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
    void statefulActorKeepsPrivateStateAcrossMessages() throws Exception {
        String output = run("""
                fnc counter(str message, int state) => int {
                  stdio.stdout.write(state);
                  return state + 1;
                }

                pub routine main() => void {
                  val counter_ref = actor.spawn(counter, 0);
                  actor.send(counter_ref, "tick");
                  actor.send(counter_ref, "tick");
                  actor.stop(counter_ref);
                  actor.join(counter_ref);
                  return;
                }
                """);

        assertEquals("01", output);
    }

    @Test
    void supervisorCanMonitorChildFailure() throws Exception {
        String output = run("""
                pub interface DownMessage {
                  event: String;
                  reason: String;
                }

                fnc supervisor(@Structural DownMessage message) => void {
                  stdio.stdout.write(message.event);
                  stdio.stdout.write(":");
                  stdio.stdout.write(message.reason);
                  return;
                }

                fnc crashing_worker(str message) => void {
                  actor.join(actor.self);
                  return;
                }

                pub routine main() => void {
                  val supervisor_ref = actor.spawn(supervisor);
                  val child_ref = actor.spawn(crashing_worker);
                  actor.monitor(child_ref, supervisor_ref);
                  actor.send(child_ref, "boom");
                  actor.join(child_ref);
                  actor.stop(supervisor_ref);
                  actor.join(supervisor_ref);
                  return;
                }
                """);

        assertEquals("DOWN:failed", output);
    }

    @Test
    void actorSendRejectsWrongMessageTypeBeforeExecution() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc worker(String message) => void {
                  return;
                }

                pub routine main() => void {
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, 42);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("actor.send") || failure.getMessage().contains("assignable"));
    }

    @Test
    void statefulActorRequiresHandlerToReturnItsStateType() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc bad_worker(String message, int state) => String {
                  return "wrong";
                }

                pub routine main() => void {
                  actor.spawn(bad_worker, 0);
                  return;
                }
                """));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("return state") || failure.getMessage().contains("assignable"));
    }

    @Test
    void readonlySharedValuesAreTypedAndDoNotConsumeTheOriginal() throws Exception {
        String output = run("""
                fnc worker(Array<String> message) => void {
                  stdio.stdout.write(message[0]);
                  return;
                }

                pub routine main() => void {
                  val values = arr["shared"];
                  val shared = process.share_readonly(values);
                  val worker_ref = actor.spawn(worker);
                  actor.send(worker_ref, shared);
                  stdio.stdout.write(":");
                  stdio.stdout.write(values[0]);
                  actor.stop(worker_ref);
                  actor.join(worker_ref);
                  return;
                }
                """);

        assertEquals("shared:shared", output);
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
