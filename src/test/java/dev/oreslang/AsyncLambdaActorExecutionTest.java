package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncLambdaActorExecutionTest {

    @Test
    void asyncPipeLambdaUsesSlimArrowAndInfersFutureResult() throws Exception {
        String program = """
                pub async routine main(): void {
                  val create = async || -> {
                    await Future.all([]);
                    return 42;
                  };

                  val value = await create();
                  stdio.stdout.write(value);
                  return;
                }
                """;

        assertEquals("42", run(program, "async-lambda-basic.ores"));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val bad = async || => { return 1; };
                          return;
                        }
                        """));
    }

    @Test
    void asyncCreateLambdaSpawnsThreeWorkersAndStreamsAcrossCooperate() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    if mail.value != "start"; do
                      return;
                    fi

                    self.send("hello");
                    rt cooperate;
                    self.send("world");
                    rt cooperate;
                    self.send("!");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val create = async || -> {
                    val worker = spawn Worker();
                    await worker.ready;
                    return worker;
                  };

                  val workers = await Future.all([
                    create(),
                    create(),
                    create()
                  ]);

                  workers[0].send("start");
                  workers[1].send("start");
                  workers[2].send("start");

                  val a0 = (await workers[0].outputs.next()).value.unwrap();
                  val a1 = (await workers[0].outputs.next()).value.unwrap();
                  val a2 = (await workers[0].outputs.next()).value.unwrap();

                  val b0 = (await workers[1].outputs.next()).value.unwrap();
                  val b1 = (await workers[1].outputs.next()).value.unwrap();
                  val b2 = (await workers[1].outputs.next()).value.unwrap();

                  val c0 = (await workers[2].outputs.next()).value.unwrap();
                  val c1 = (await workers[2].outputs.next()).value.unwrap();
                  val c2 = (await workers[2].outputs.next()).value.unwrap();

                  stdio.stdout.write(a0.value);
                  stdio.stdout.write(a1.value);
                  stdio.stdout.write(a2.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b0.value);
                  stdio.stdout.write(b1.value);
                  stdio.stdout.write(b2.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c0.value);
                  stdio.stdout.write(c1.value);
                  stdio.stdout.write(c2.value);

                  await Future.all([
                    workers[0].done,
                    workers[1].done,
                    workers[2].done
                  ]);
                  return;
                }
                """;

        assertEquals(
                "helloworld!|helloworld!|helloworld!",
                run(program, "async-lambda-actors.ores"));
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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
