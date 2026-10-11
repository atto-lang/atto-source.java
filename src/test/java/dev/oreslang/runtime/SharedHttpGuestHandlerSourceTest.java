package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Run the byte-identical public Oreslang counter_response source compiled
 * by the LLVM conformance fixture. Trusted host transport is deliberately
 * not represented as a guest http.listen implementation.
 */
final class SharedHttpGuestHandlerSourceTest {
    @Test
    void sameGuestHandlerReturnsDeterministicValuesAcrossPreparsedCalls() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/parity/http-guest-counter-handler.ores")) {
            assertNotNull(stream, "byte-identical LLVM handler fixture absent");
            bytes = stream.readAllBytes();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("pub fnc counter_response(int count): int"));
        Source source = Source.newBuilder(OresLanguage.ID, text, "http-guest-counter-handler.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value target = context.parse(source);
            assertTrue(target.canExecute());
            assertEquals(43L, target.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main").asLong());
            for (int i = 0; i < 128; ++i) {
                assertEquals((long) (i + 1) * 43L,
                        target.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                "counter_response", (long) (i + 1)).asLong());
            }
        }
    }
}
