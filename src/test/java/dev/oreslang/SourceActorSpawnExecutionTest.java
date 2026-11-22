package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSpawnExecutionTest {

    @Test
    void spawnRunsOnStartReceiveAndDoneAcrossActorKinds() throws Exception {
        String program = """
                define actor SharedWorker as
                  let int started;

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

                  await shared.get_ready();
                  await privateWorker.get_ready();
                  await sandboxed.get_ready();

                  shared.send("stop");
                  privateWorker.send("stop");
                  sandboxed.send("stop");

                  await shared.get_done();
                  await privateWorker.get_done();
                  await sandboxed.get_done();

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

                  receive(ActorMail<String> mail): void {
                    self.count = self.count + 1;
                    self.send(self.count);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val left = spawn Counter();
                  val right = spawn Counter();

                  await left.get_ready();
                  await right.get_ready();

                  left.send("go");
                  right.send("go");

                  val leftOut = (await left.outputs.next()).value.unwrap();
                  val rightOut = (await right.outputs.next()).value.unwrap();

                  stdio.stdout.write(leftOut.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(rightOut.value);

                  await left.get_done();
                  await right.get_done();
                  return;
                }
                """;

        assertEquals(
                "1|1",
                run(program, "source-shared-code-distinct-state.ores"),
                "actors may share immutable code identity but never writable actor fields");
    }

    @Test
    void actorPrivateActorRefFieldOwnsChildAndSurvivesReceiveAwait() throws Exception {
        String program = resource("/parity/actorref-child-capability-v1.ores");
        assertEquals(
                "599d1366b5926e5afc567cb7832d4769ebef6c43cd19b8010aeca22fdf83b585",
                sha256(program),
                "fixture bytes must match common-source ActorRef child capability v1");

        assertEquals(
                "42",
                run(program, "actorref-child-capability-v1.ores"),
                "actor-private ActorRef fields must retain a structured child capability across await");
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
                  await worker.get_ready();
                  return;
                }
                """;

        PolyglotException failure =
                assertThrows(
                        PolyglotException.class,
                        () -> run(program, "source-actor-startup-failure.ores"));
        assertTrue(
                // #1223: the spawning guest sees the cause-free snapshot, not the
                // child's diagnostic text.
                failure.getMessage().contains("terminated: FAILURE"),
                failure.getMessage());
    }

    @Test
    void suspendingReceiveResumesBeforeLaterUserMail() throws Exception {
        String program = """
                define actor Worker as
                  let int phase;

                  on_start(): void {
                    self.phase = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
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
                    fi

                    if mail.value == "second"; do
                      if self.phase == 2; do
                        self.send("second");
                      else
                        self.send("interleaved");
                      fi
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();

                  // Queue both messages before the first receive cooperates.
                  worker.send("first");
                  worker.send("second");

                  val a = (await worker.outputs.next()).value.unwrap();
                  val b = (await worker.outputs.next()).value.unwrap();
                  val c = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.value);

                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "before|after|second",
                run(program, "source-actor-suspending-receive.ores"));
    }

    @Test
    void awaitSpawnIsAReadinessBarrierThatReturnsTheActorRef() throws Exception {
        String program = """
                define actor WorkerActor as
                  let int initialized;

                  on_start(): void {
                    self.initialized = 1;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if self.initialized == 1; do
                      self.send("ready");
                    fi
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn WorkerActor();
                  worker.send("run");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.get_done();
                  return;
                }
                """;
        assertEquals("ready", run(program, "source-await-spawn-ref.ores"));
    }

    @Test
    void awaitSpawnFailsWhenOnStartFails() throws Exception {
        String program = """
                define actor WorkerActor as
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
                  val worker = await spawn WorkerActor();
                  worker.send("unreachable");
                  return;
                }
                """;
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-await-spawn-failure.ores"));
        assertTrue(error.getMessage().contains("terminated: FAILURE"),
                error.getMessage());
    }

    @Test
    void actorLifecycleMethodsReturnAwaitableFuturesAndRejectPropertyAccess() throws Exception {
        String valid = """
                define actor Worker as
                  receive(ActorMail<String> message): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  val initialized = worker.get_ready();
                  await initialized;
                  worker.send("finish");
                  val finalized = worker.get_done();
                  await finalized;
                  stdio.stdout.write("finished");
                  return;
                }
                """;
        assertEquals("finished", run(valid, "source-actor-future-getters.ores"));

        for (String forbidden : java.util.List.of(
                "await worker.ready;",
                "await worker.done;",
                "await worker.get_ready(1);",
                "await worker.get_done(1);",
                "await worker.get_ready;",
                "await worker.get_done;")) {
            String source = """
                    define actor Worker as
                      receive(ActorMail<String> message): void {
                        self.end();
                        return;
                      }
                    end
                    pub async routine main(): void {
                      val worker = spawn Worker();
                      """ + forbidden + """
                      
                      worker.stop();
                      return;
                    }
                    """;
            assertThrows(PolyglotException.class,
                    () -> run(source, "source-actor-bad-lifecycle-access.ores"),
                    forbidden + " must not be accepted");
        }
    }

    private static String resource(String path) throws Exception {
        try (InputStream input =
                SourceActorSpawnExecutionTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing test resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
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
