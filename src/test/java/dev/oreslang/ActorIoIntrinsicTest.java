package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorIoIntrinsicTest {
    private static void check(String code, String name) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, name).build());
        }
    }

    @Test
    void actorIoOutIsIntrinsicAndAwaitable() throws Exception {
        check("""
                pub suspends actor fnc worker(string value) -> void {
                  await io.out.write(value);
                  return;
                }

                pub routine main(): void {
                  return;
                }
                """, "actor-io-out.ores");
    }

    @Test
    void actorIoOutExecutesThroughActorContinuationWithoutProcessStdout() throws Exception {
        var out = new ByteArrayOutputStream();
        String code = """
                define actor Worker as
                  suspends receive(ActorMail<String> mail): void {
                    await io.out.write(mail.value);
                    self.end();
                    return;
                  }
                end

                pub suspends routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("hello");
                  await worker.get_done();
                  return;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, code, "actor-io-runtime.ores").build());
        }
        assertEquals(
                "",
                out.toString(StandardCharsets.UTF_8),
                "actor io.out must not be routed to process stdout");
    }

    @Test
    void actorIoOutDeliversOrderedActorOwnedDataAndTerminalEof() throws Exception {
        var out = new ByteArrayOutputStream();
        String code = Files.readString(
                Path.of("examples/actor-io-output.ores"), StandardCharsets.UTF_8);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, code, "actor-io-output.ores").build());
        }
        // Exact output also rejects any extra host stdout emitted by the actor.
        assertEquals(
                "false|hello|0|true|false|second|1|true|true",
                out.toString(StandardCharsets.UTF_8),
                "root must observe both actor-owned records in order, then EOF");
    }

    @Test
    void processStdioIsRejectedInsideActor() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string value) -> void {
                          stdio.println(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-process-stdio.ores"));
        assertTrue(failure.getMessage().contains("process-level I/O"), failure::getMessage);
    }

    @Test
    void nestedStdoutBuiltinCannotBypassActorStdioBan() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string value) -> void {
                          stdio.stdout.log(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-process-stdout-log.ores"));
        assertTrue(failure.getMessage().contains("process-level I/O"), failure::getMessage);
    }

    @Test
    void actorIoOutWriteCannotBeCapturedAsFunctionValue() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string value) -> void {
                          val writer = io.out.write;
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-write-capture.ores"));
        assertTrue(
                failure.getMessage().contains("actor execution intrinsic")
                        && failure.getMessage().contains("invoke it directly"),
                failure::getMessage);
    }

    @Test
    void actorIoOutFutureCannotBeSilentlyDiscarded() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub suspends actor fnc worker(string value) -> void {
                          io.out.write(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-unawaited-write.ores"));
        assertTrue(failure.getMessage().contains("await it"), failure::getMessage);
    }

    @Test
    void actorIoCapabilityCannotBeWrittenAsActorData() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub suspends actor fnc worker(string value) -> void {
                          await io.out.write(io);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-capability-escape.ores"));
        assertTrue(failure.getMessage().contains("cannot use $ActorIO"), failure::getMessage);
    }

    @Test
    void actorRefCannotEscapeThroughIoOut() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        define actor Child as
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end

                        pub suspends actor fnc worker(string value) -> void {
                          await io.out.write(spawn Child());
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-actorref-output.ores"));
        assertTrue(
                failure.getMessage().contains("data-only")
                        && failure.getMessage().contains("ActorRef"),
                failure::getMessage);
    }

    @Test
    void actorRefCannotEscapeThroughSelfSendOutput() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        define actor Parent as
                          receive(ActorMail<String> mail): void {
                            self.send(spawn Parent());
                            return;
                          }
                        end

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-self-send-actorref-output.ores"));
        assertTrue(
                failure.getMessage().contains("data-only")
                        && failure.getMessage().contains("ActorRef"),
                failure::getMessage);
    }

    @Test
    void rootIoIsNotAnAmbientProcessAlias() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub routine main(): void {
                          io.out.write("wrong");
                          return;
                        }
                        """, "root-actor-io.ores"));
        assertTrue(failure.getMessage().contains("unknown name 'io'"), failure::getMessage);
    }

    @Test
    void actorIoIntrinsicCannotBeShadowedByAParameter() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string io) -> void {
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-shadow.ores"));
        assertTrue(
                failure.getMessage().contains("reserved actor execution intrinsic"),
                failure::getMessage);
    }

    @Test
    void reservedActorChannelsCannotBeAccessedOrAliased() {
        // Reserved means inaccessible, not a movable placeholder capability.
        for (String channel : new String[] {"in", "err", "events", "meta"}) {
            PolyglotException failure = assertThrows(PolyglotException.class, () ->
                    check("""
                            pub actor fnc worker(string value) -> void {
                              val unavailable = io.%s;
                              return;
                            }

                            pub routine main(): void {
                              return;
                            }
                            """.formatted(channel), "actor-io-reserved-" + channel + ".ores"));
            assertTrue(
                    failure.getMessage().contains("reserved but not implemented"),
                    failure::getMessage);
        }
    }

    @Test
    void unimplementedActorChannelsFailClosedAtTypecheck() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub suspends actor fnc worker(string value) -> void {
                          await io.err.write(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-err-reserved.ores"));
        assertTrue(failure.getMessage().contains("reserved but not implemented"), failure::getMessage);
    }
}
