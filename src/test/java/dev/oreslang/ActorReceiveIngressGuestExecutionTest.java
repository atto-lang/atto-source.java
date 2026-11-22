package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ActorReceiveIngressGuestExecutionTest {
    private static String guestSource() {
        String path = "/atto/actor-ingress/positive-guest-delivery.atto";
        try (var source = ActorReceiveIngressGuestExecutionTest.class.getResourceAsStream(path)) {
            if (source == null) {
                throw new IllegalStateException("missing guest fixture: " + path);
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @Timeout(15)
    void actorReceiveCanOnlyBeReachedThroughTheRealMailbox() throws Exception {
        String program = guestSource();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, program, "actor-ingress-mailbox.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("delivered", output.toString(StandardCharsets.UTF_8));
    }
}
