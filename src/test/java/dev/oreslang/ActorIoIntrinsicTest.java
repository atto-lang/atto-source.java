package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

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
                pub suspends actor fnc worker(String value) -> void {
                  await io.out.write(value);
                  return;
                }

                pub routine main(): void {
                  return;
                }
                """, "actor-io-out.ores");
    }

    @Test
    void processStdioAndPrintAreRejectedInsideActors() {
        for (String statement : new String[] {
                "stdio.println(value);",
                "stdio.stdout.log(value);",
                "print(value);"
        }) {
            PolyglotException failure = assertThrows(PolyglotException.class, () ->
                    check("""
                            pub actor fnc worker(String value) -> void {
                              %s
                              return;
                            }

                            pub routine main(): void {
                              return;
                            }
                            """.formatted(statement), "actor-process-stdio.ores"));
            assertTrue(
                    failure.getMessage().contains("process-level I/O"),
                    failure::getMessage);
        }
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
    void unimplementedActorChannelsFailClosed() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub suspends actor fnc worker(String value) -> void {
                          await io.err.write(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-err-reserved.ores"));
        assertTrue(failure.getMessage().contains("reserved but not implemented"), failure::getMessage);
    }

    @Test
    void actorIoIntrinsicCannotBeShadowed() {
        for (String parameter : new String[] {"io", "stdio", "print"}) {
            PolyglotException failure = assertThrows(PolyglotException.class, () ->
                    check("""
                            pub actor fnc worker(string %s) -> void {
                              return;
                            }

                            pub routine main(): void {
                              return;
                            }
                            """.formatted(parameter), "actor-io-shadow.ores"));
            assertTrue(
                    failure.getMessage().contains("reserved actor execution intrinsic"),
                    failure::getMessage);
        }
    }

    @Test
    void nestedActorBindingsCannotShadowIo() {
        PolyglotException lambda = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(String value) -> void {
                          val callback = |String io| -> { return; };
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-lambda-shadow.ores"));
        assertTrue(
                lambda.getMessage().contains("actor lambda parameter")
                        && lambda.getMessage().contains("reserved actor execution intrinsic"),
                lambda::getMessage);

        PolyglotException refinement = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(String value) -> void {
                          if value is type String io then
                            return;
                          fi
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-refinement-shadow.ores"));
        assertTrue(
                refinement.getMessage().contains("actor type-test binding")
                        && refinement.getMessage().contains("reserved actor execution intrinsic"),
                refinement::getMessage);
    }

    @Test
    void actorIoOutWriteCannotBeCapturedOrDiscarded() {
        PolyglotException capture = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(String value) -> void {
                          val writer = io.out.write;
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-write-capture.ores"));
        assertTrue(capture.getMessage().contains("invoke it directly"), capture::getMessage);

        PolyglotException discarded = assertThrows(PolyglotException.class, () ->
                check("""
                        pub suspends actor fnc worker(String value) -> void {
                          io.out.write(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-unawaited-write.ores"));
        assertTrue(discarded.getMessage().contains("await it"), discarded::getMessage);
    }

    @Test
    void outputStreamIsDataOnlyButMailboxStillCarriesActorRefs() throws Exception {
        PolyglotException ioOutput = assertThrows(PolyglotException.class, () ->
                check("""
                        define actor Child as
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end

                        pub suspends actor fnc worker(String value) -> void {
                          await io.out.write(spawn Child());
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-actorref-output.ores"));
        assertTrue(
                ioOutput.getMessage().contains("data-only")
                        && ioOutput.getMessage().contains("ActorRef"),
                ioOutput::getMessage);

        // Mailbox transport is a different capability boundary: ActorRef is a
        // valid explicit mailbox capability and must not be broken by io.out.
        check("""
                define actor Registry as
                  receive(ActorMail<ActorRef<Registry>> mail): void {
                    return;
                  }
                end

                pub routine main(): void {
                  val registry = spawn Registry();
                  registry.send(registry);
                  return;
                }
                """, "actor-ref-mailbox-control.ores");
    }

    @Test
    void actorIoOutExecutesWithoutTouchingProcessStdout() throws Exception {
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
                "logical actor output must not alias process stdout");
    }
}
