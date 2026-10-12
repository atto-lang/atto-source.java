package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncActorMethodTest {

    @Test
    void actorAsyncMethodsStayMailboxOwnedAndExposeOneFutureLayer() throws Exception {
        String program = """
                async fnc bounce(int value): int {
                  return value;
                }

                actor Counter {
                  let int value = 0;

                  private async compute(int amount): int {
                    return await bounce(amount);
                  }

                  pub async add(int amount): int {
                    val int delta = await self.compute(amount);
                    self.value = self.value + delta;
                    return self.value;
                  }
                }

                pub async fnc main(): void {
                  val counter = spawn Counter();

                  // ActorRef protocol dispatch is exactly Future<declared-return>,
                  // even though the target method itself is async.
                  val Future<int> first = counter.add(2);
                  val Future<int> second = counter.add(3);

                  stdio.println(await first);
                  stdio.println(await second);
                  return;
                }
                """;

        OresCompiler.parseAndTypeCheck(program);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "async-actor-method.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals(
                "2\n5",
                output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void privateAsyncActorHelperMayRetainActorLocalChannelCapability() {
        String program = """
                actor Worker {
                  private async consume(Channel<int> input): int {
                    return readch input;
                  }

                  pub async run(): int {
                    val Channel<int> local = Channel.new<int>(1);
                    writech local, 42;
                    return await self.consume(local);
                  }
                }

                pub async fnc main(): void {
                  val worker = spawn Worker();
                  val Future<int> result = worker.run();
                  stdio.println(await result);
                  return;
                }
                """;

        OresCompiler.parseAndTypeCheck(program);
    }

    @Test
    void ordinaryClassAsyncInstanceMethodStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          pub async value(): int {
                            return 42;
                          }
                        end

                        pub routine main(): void {
                          val box = new Box();
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("ordinary class receivers"));
    }
}
