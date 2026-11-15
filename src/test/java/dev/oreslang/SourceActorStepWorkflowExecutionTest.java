package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Source-executed actor-step baseline. A passing test proves only the covered
 * .ores guest fixture; it does not prove a durable Workflow actor, sealed state
 * loans, checkpoints, authenticated replies, or cross-backend parity.
 * No test is a PASS unless Context.eval actually executes on a Graal runner.
 */
final class SourceActorStepWorkflowExecutionTest {
    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void executesThreeIndependentStepActorsInSequence() throws Exception {
        assertEquals("complete", runResource("/parity/actor-step-sequential.ores",
                "7ad94d37efb5f1b457144f0b78efb6dbee2387d2"));
    }

    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void executesTwoStepActorsAndJoinsTheirOutputs() throws Exception {
        assertEquals("inventory|invoice", runResource("/parity/actor-step-parallel.ores",
                "40fd43e4b73bb29c9dd896d954ec2101bf36b1d1"));
    }

    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void rejectsInvalidStepInputBeforeStartingDownstreamActors() throws Exception {
        assertEquals("rejected", runResource("/parity/actor-step-rejection.ores",
                "f95baeb8fd8d0dec826c58bbe0ba88a3b444948f"));
    }

    @Test
    @Timeout(value=30,unit=TimeUnit.SECONDS)
    void executesCoordinatorActorWithGuardedForkJoinAndDuplicateReceipt() throws Exception {
        assertEquals("workflow:actor-fork-join", runResource(
                "/parity/actor-coordinator-fork-join.ores",
                "9470d82a3180744c28abe9f890545b9bafdb2110"));
    }

    private static String runResource(String resourceName, String expectedGitBlob)
            throws Exception {
        byte[] bytes;
        try (InputStream sourceFile = SourceActorStepWorkflowExecutionTest.class
                .getResourceAsStream(resourceName)) {
            assertNotNull(sourceFile, "fixture not found: " + resourceName);
            bytes = sourceFile.readAllBytes();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        assertEquals(expectedGitBlob, HexFormat.of().formatHex(digest.digest()),
                "Pinned Oreslang source blob drifted: " + resourceName);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID,
                        new String(bytes, StandardCharsets.UTF_8), resourceName)
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
