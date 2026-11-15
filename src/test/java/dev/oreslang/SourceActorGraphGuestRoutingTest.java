package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First real source-defined three-actor guest routing gate on the supported
 * ActorRef/mailbox/outputs APIs. Main explicitly bridges each hop.
 *
 * This is deliberately NOT an implementation or proof of source 'pipe'
 * lowering, ActorStream typed port authority, supervisor restart, or native AOT.
 * Only the three actor receive() functions produce graph values.
 */
@Timeout(30)
final class SourceActorGraphGuestRoutingTest {
    private static final String SOURCE = loadSource();

    private static String loadSource() {
        try (var input = SourceActorGraphGuestRoutingTest.class.getResourceAsStream(
                "/actor-graph/v1/three-stage-explicit-coordinator.ores")) {
            if (input == null)
                throw new IllegalStateException("missing exact source guest fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void threeRealActorsForwardAndReplyThroughExplicitCoordinator() throws Exception {
        // Explicitly check nominal, actor-bound message ports as well as
        // interpreter evaluation. No source-level 'pipe' is used.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(SOURCE)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, SOURCE, "actor-graph-three-stage.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("19", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorMailboxOutputCannotBecomeProcessStdioWithoutCoordinator() throws Exception {
        // Execute the same real three-actor source path, but remove only the
        // coordinator's explicit process stdout write. ActorRef.send and
        // outputs.next are mailbox transport, not host stdout.
        String emission = "stdio.stdout.write(final_output.value);";
        assertTrue(SOURCE.contains(emission), "guest fixture changed unexpectedly");
        String silentSource = SOURCE.replace(emission,
                "val final_value = final_output.value;");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(silentSource)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, silentSource,
                    "actor-graph-mailbox-not-stdout.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("", output.toString(StandardCharsets.UTF_8),
                "actor-to-actor mailbox traffic must not leak to process stdout");
    }

    @Test
    void operatorSyntaxCannotBePromotedFromExplicitMailboxRouting() {
        String unsupported = SOURCE.replace(
                "val source = spawn SourceStage();",
                "val bogus_graph = spawn SourceStage() pipe spawn TransformStage();"
                + " val source = spawn SourceStage();");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(unsupported)));
        // Main now rejects source-level actor pipes in the parser until
        // role typing, bounded edges and lowering exist.
        assertTrue(failure.getMessage().contains("actor pipe expressions require"),
                failure::getMessage);
    }
}
