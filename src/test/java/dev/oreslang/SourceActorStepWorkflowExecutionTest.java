package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Source-executed actor-step baseline. A passing test proves only the covered
 * .ores guest fixture; it does not prove a durable Workflow actor, state
 * checkpoints, retries, authenticated replies, or cross-backend parity.
 */
final class SourceActorStepWorkflowExecutionTest {
    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void executesThreeIndependentStepActorsInSequence() throws Exception {
        assertEquals("complete", runResource("/parity/actor-step-sequential.ores"));
    }

    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void executesTwoStepActorsAndJoinsTheirOutputs() throws Exception {
        assertEquals("inventory|invoice", runResource("/parity/actor-step-parallel.ores"));
    }

    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void rejectsInvalidStepInputBeforeStartingDownstreamActors() throws Exception {
        assertEquals("rejected", runResource("/parity/actor-step-rejection.ores"));
    }

    private static String runResource(String resourceName) throws Exception {
        String sourceText;
        try (InputStream sourceFile = SourceActorStepWorkflowExecutionTest.class
                .getResourceAsStream(resourceName)) {
            assertNotNull(sourceFile, "fixture not found: " + resourceName);
            sourceText = new String(sourceFile.readAllBytes(), StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, resourceName)
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
