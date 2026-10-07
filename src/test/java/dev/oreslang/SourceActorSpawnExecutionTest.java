package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSpawnExecutionTest {

    @Test
    void spawnRunsOnStartReceiveAndDoneAcrossActorKinds() throws Exception {
        String program = """
                define actor SharedWorker as
                  val int started;

                  on_start(): void {
                    self.started = 1;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if self.started != 1; do
                      return;
                    fi
                    if mail.value == "stop"; do
                      self.end();
                    fi
                    return;
                  }
                end

                define isoactor PrivateWorker as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                define untrusted actor SandboxedWorker as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val shared = spawn SharedWorker();
                  val privateWorker = spawn PrivateWorker();
                  val sandboxed = spawn SandboxedWorker();

                  await shared.ready;
                  await privateWorker.ready;
                  await sandboxed.ready;

                  shared.send("stop");
                  privateWorker.send("stop");
                  sandboxed.send("stop");

                  await shared.done;
                  await privateWorker.done;
                  await sandboxed.done;

                  stdio.stdout.write("actors-done");
                  return;
                }
                """;

        assertEquals("actors-done", run(program, "source-actor-spawn.ores"));
    }

    @Test
    void readyFailsWhenOnStartLeavesActorStateUninitialized() throws Exception {
        String program = """
                define actor Worker as
                  val int required;

                  on_start(): void {
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  return;
                }
                """;

        PolyglotException failure =
                assertThrows(
                        PolyglotException.class,
                        () -> run(program, "source-actor-startup-failure.ores"));
        assertTrue(
                failure.getMessage().contains("did not initialize field 'required'"),
                failure.getMessage());
    }

    @Test
    void suspendingReceiveIsRejectedUntilMailboxTurnSuspensionIsSerialized()
            throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    rt cooperate;
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("stop");
                  await worker.done;
                  return;
                }
                """;

        PolyglotException failure =
                assertThrows(
                        PolyglotException.class,
                        () -> run(program, "source-actor-suspending-receive.ores"));
        assertTrue(
                failure.getMessage().contains(
                        "suspending actor receive is not executable yet"),
                failure.getMessage());
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source =
                Source.newBuilder(OresLanguage.ID, sourceText, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build();

        try (Context context =
                Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .out(output)
                        .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
