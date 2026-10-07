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
    void spawnRunsOnStartTypedEndpointsAndDoneAcrossActorKinds() throws Exception {
        String program = """
                define actor SharedWorker as
                  let int started;

                  on_start(): void {
                    self.started = 1;
                    return;
                  }

                  pub stop(): void {
                    if self.started != 1; do
                      return;
                    fi
                    self.end();
                    return;
                  }
                end

                define isoactor PrivateWorker as
                  pub stop(): void {
                    self.end();
                    return;
                  }
                end

                define untrusted actor SandboxedWorker as
                  pub stop(): void {
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

                  shared.send.stop();
                  privateWorker.send.stop();
                  sandboxed.send.stop();

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
    void sharedActorsReuseCodeWithoutSharingMutableActorState() throws Exception {
        String program = """
                define actor Counter as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  pub tick(): void {
                    self.count = self.count + 1;
                    self.send(self.count);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val left = spawn Counter();
                  val right = spawn Counter();

                  await left.ready;
                  await right.ready;

                  left.send.tick();
                  right.send.tick();

                  val leftOut = (await left.outputs.next()).value.unwrap();
                  val rightOut = (await right.outputs.next()).value.unwrap();

                  stdio.stdout.write(leftOut.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(rightOut.value);

                  await left.done;
                  await right.done;
                  return;
                }
                """;

        assertEquals(
                "1|1",
                run(program, "source-shared-code-distinct-state.ores"),
                "actors may share immutable code identity but never writable actor fields");
    }

    @Test
    void typedCallReturnsTransportedResultAndOptionValues() throws Exception {
        String program = """
                define actor Worker as
                  pub compute(int value): Result<int, String> {
                    return Ok(value + 1);
                  }

                  pub maybe(bool present): Option<String> {
                    if present; do
                      return Some("yes");
                    fi
                    return None;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;

                  val Result<int, String> result = await worker.call.compute(41);
                  val Option<String> maybe = await worker.call.maybe(true);

                  stdio.stdout.write(result.unwrap());
                  stdio.stdout.write("|");
                  stdio.stdout.write(maybe.unwrap());

                  worker.stop();
                  await worker.done;
                  return;
                }
                """;

        assertEquals(
                "42|yes",
                run(program, "source-actor-call-result-option.ores"));
    }

    @Test
    void readyFailsWhenOnStartLeavesActorStateUninitialized() throws Exception {
        String program = """
                define actor Worker as
                  val int required;

                  on_start(): void {
                    return;
                  }

                  pub stop(): void {
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
    void suspendingEndpointResumesBeforeLaterUserDispatch() throws Exception {
        String program = """
                define actor Worker as
                  let int phase;

                  on_start(): void {
                    self.phase = 0;
                    return;
                  }

                  pub first(): void {
                    self.phase = 1;
                    self.send("before");
                    rt cooperate;

                    if self.phase != 1; do
                      self.send("interleaved");
                      return;
                    fi

                    self.phase = 2;
                    self.send("after");
                    return;
                  }

                  pub second(): void {
                    if self.phase == 2; do
                      self.send("second");
                    else
                      self.send("interleaved");
                    fi
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;

                  // Queue both endpoint dispatches before the first one cooperates.
                  worker.send.first();
                  worker.send.second();

                  val a = (await worker.outputs.next()).value.unwrap();
                  val b = (await worker.outputs.next()).value.unwrap();
                  val c = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.value);

                  await worker.done;
                  return;
                }
                """;

        assertEquals(
                "before|after|second",
                run(program, "source-actor-suspending-receive.ores"));
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
