package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ActorPipelineDifferentialExecutionTest {
    @Test
    void compiledThreeActorGuestEmitsExactCausalHopTranscript() throws Exception {
        String program = Files.readString(Path.of("examples/actor-pipeline-differential.ores"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program,
                "actor-pipeline-differential.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("PIPE_FORWARD_SOURCE_5|PIPE_FORWARD_TRANSFORM_10|"
                + "PIPE_BACKWARD_SINK_13|PIPE_BACKWARD_TRANSFORM_14|"
                + "JAVA_PIPELINE_RESULT_19", output.toString(StandardCharsets.UTF_8));
    }
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void compiledNominalMessagePackGuestPreservesBothSignedI64Endpoints() throws Exception {
        String program=Files.readString(Path.of("examples/actor-pipeline-signed-i64.ores"));
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Source guest=Source.newBuilder(OresLanguage.ID,program,"actor-pipeline-signed-i64.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try(Context context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()){
            context.eval(guest);
        }
        assertEquals("V2_PIPE_FORWARD_SOURCE_-9223372036854775808|"
                +"V2_PIPE_FORWARD_TRANSFORM_-9223372036854775808|"
                +"V2_PIPE_BACKWARD_SINK_9223372036854775807|"
                +"V2_PIPE_BACKWARD_TRANSFORM_9223372036854775807|"
                +"JAVA_V2_PIPELINE_RESULT_MAX",output.toString(StandardCharsets.UTF_8));
    }

}
