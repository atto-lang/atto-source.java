package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

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
    void operatorSyntaxCannotBePromotedFromExplicitMailboxRouting() {
        String unsupported = SOURCE.replace(
                "val source = spawn SourceStage();",
                "val bogus_graph = spawn SourceStage() pipe spawn TransformStage();"
                + " val source = spawn SourceStage();");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(unsupported)));
        assertTrue(failure.getMessage().contains("typed pipe ports are compatible"),
                failure::getMessage);
    }
}
