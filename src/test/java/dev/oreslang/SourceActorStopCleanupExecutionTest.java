package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorStopCleanupExecutionTest {
    @Test
    @Timeout(12)
    void compiledSourceActorRunsFinallyAndDeferAfterStopWithoutEnteringCatch()
            throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> gate = Channel.new<int>(0);
                    defer self.send("defer");
                    try {
                      self.send("entered");
                      readch gate;
                      self.send("unreachable");
                    } catch (err) {
                      self.send("caught");
                    } finally {
                      self.send("finally");
                    }
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  val entered = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(entered.value);
                  worker.stop();
                  for await const output of worker.outputs {
                    stdio.stdout.write("|");
                    stdio.stdout.write(output.value);
                  }
                  await worker.get_done();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program,
                    "source-actor-stop-defer-finally.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("entered|finally|defer", output.toString(StandardCharsets.UTF_8));
    }
}
