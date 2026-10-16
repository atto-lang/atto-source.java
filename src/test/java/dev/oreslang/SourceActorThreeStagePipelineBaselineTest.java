package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Actual source-compiled guest actors and ActorMail receives, connected by
 * the supervisor through ActorRef.send and actor output streams.
 *
 * This is deliberately a BASELINE, not a `self.forward_*` conformance proof:
 * guest pipeline declarations, scheduler-owned correlation, and automatic
 * downstream/upstream delivery still require compiler/runtime lowering.
 */
final class SourceActorThreeStagePipelineBaselineTest {

    @Test
    void compiledSourceTransformSinkAndBackToSource() throws Exception {
        final String program = """
                define actor SourceActor as
                  receive(ActorMail<String> mail): void {
                    if mail.value == "begin"; do
                      self.send("origin");
                    else
                      self.send("reply:" + mail.value);
                      self.end();
                    fi
                    return;
                  }
                end

                define actor TransformActor as
                  receive(ActorMail<String> mail): void {
                    self.send(mail.value + ":transform");
                    self.end();
                    return;
                  }
                end

                define actor SinkActor as
                  receive(ActorMail<String> mail): void {
                    self.send(mail.value + ":sink");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val source = await spawn SourceActor();
                  val transform = await spawn TransformActor();
                  val sink = await spawn SinkActor();

                  source.send("begin");
                  val origin = (await source.outputs.next()).value.unwrap();
                  transform.send(origin.value);

                  val transformed = (await transform.outputs.next()).value.unwrap();
                  sink.send(transformed.value);

                  val terminal = (await sink.outputs.next()).value.unwrap();
                  source.send(terminal.value);
                  val response = (await source.outputs.next()).value.unwrap();
                  stdio.stdout.write(response.value);

                  await source.get_done();
                  await transform.get_done();
                  await sink.get_done();
                  return;
                }
                """;

        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program,
                    "source-three-stage-actor-pipeline-baseline.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("reply:origin:transform:sink",
                output.toString(StandardCharsets.UTF_8));
    }
}
