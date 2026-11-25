package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SourceActorOutputStreamExecutionTest {

    @Test
    void sourceActorStreamsOrderedOutputsAndClosesAfterFinalization() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("hello");
                    self.send("world");
                    self.send("!");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");

                  val firstStep = await worker.outputs.next();
                  val first = firstStep.value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(first.sequence);

                  val secondStep = await worker.outputs.next();
                  val second = secondStep.value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(second.sequence);

                  val thirdStep = await worker.outputs.next();
                  val third = thirdStep.value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(third.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(third.sequence);

                  await worker.get_done();

                  val terminal = await worker.outputs.next();
                  stdio.stdout.write("|");
                  stdio.stdout.write(terminal.done);
                  return;
                }
                """;

        assertEquals(
                "hello:0|world:1|!:2|true",
                run(program, "source-actor-output-stream.ores"));
    }

    @Test
    void forAwaitConsumesActorOutputsUntilActorFinalization() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("hello");
                    self.send("world");
                    self.send("!");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");

                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }

                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "helloworld!",
                run(program, "source-actor-output-for-await.ores"));
    }

    @Test
    void inlineAndDeclaredSerialStreamActorsHaveTheSameGuestLifecycleAndOutput() throws Exception {
        String inline = """
                define serial stream actor Worker receives int emits int as
                  double_value(int input): int { return input * 2; }
                  receive(ActorMail<int> mail): void {
                    self.send(self.double_value(mail.value));
                    self.end();
                  }
                end
                """;
        String split = """
                mtd (Worker self) double_value(int input): int { return input * 2; }
                declare serial stream actor Worker emits int receives int;
                define state on actor Worker as {}
                mtd (Worker self) receive(ActorMail<int> mail): void {
                  self.send(self.double_value(mail.value));
                  self.end();
                }
                """;
        String main = """
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send(21);
                  val ActorOutputStream<int> outputs = worker.outputs;
                  val step = await outputs.next();
                  stdio.stdout.write(step.value.unwrap().value);
                  await worker.get_done();
                  val terminal = await outputs.next();
                  stdio.stdout.write(terminal.done);
                }
                """;
        assertEquals("42true", run(inline + main, "inline-serial-stream.atto"));
        assertEquals("42true", run(split + main, "declared-serial-stream.atto"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void serialUnaryMailboxKeepsRequestOrderAcrossSuspension() throws Exception {
        String source = """
                define serial unary actor Worker receives int as
                  let int total = 0;
                  run(ActorMail<int> mail): int {
                    val previous = self.total;
                    await process.sleep_async(15);
                    self.total = previous + mail.value;
                    return self.total;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  val first = worker.request(2);
                  val second = worker.request(3);
                  val Future<int> third = worker.request(5);
                  stdio.stdout.write(await third);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await second);
                  worker.stop();
                  await worker.get_done();
                }
                """;
        assertEquals("10|2|5", run(source, "serial-unary-mailbox.atto"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void attachedUnaryMailboxCanTakeAnUnwrappedPayload() throws Exception {
        String source = """
                declare unary actor Worker receives int;
                define state on actor Worker as {}
                mtd (Worker self) run(int input): int { return input * 2; }
                pub async routine main(): void {
                  val worker = spawn Worker();
                  stdio.stdout.write(await worker.request(21));
                  worker.stop();
                  await worker.get_done();
                }
                """;
        assertEquals("42", run(source, "attached-unary-mailbox.atto"));
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, name)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
