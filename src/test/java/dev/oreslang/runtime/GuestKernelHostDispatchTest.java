package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Trusted HTTP server adapters must reuse one parsed code target instead of
 * reparsing guest source for every request. Public exports are the only
 * callable entrypoints; the private kernel must remain hidden to hosts.
 */
final class GuestKernelHostDispatchTest {
    @Test
    void preParsedPublicKernelDispatchIsReusableAndPrivateFunctionsStayPrivate() throws Exception {
        byte[] sourceBytes;
        try (var stream = getClass().getResourceAsStream("/parity/http-shared-counter-kernel.ores")) {
            assertNotNull(stream, "shared source fixture missing");
            sourceBytes = stream.readAllBytes();
        }
        String text = new String(sourceBytes, StandardCharsets.UTF_8);
        Source source = Source.newBuilder(OresLanguage.ID, text, "http-shared-counter-kernel.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value code = context.parse(source);
            assertTrue(code.canExecute(), "compiler must expose a reusable parsed target");
            for (int n = 0; n < 1024; ++n) {
                assertEquals(43L, code.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main")
                        .asLong(), "shared Oreslang guest kernel must be invoked, not simulated");
            }
            assertThrows(PolyglotException.class,
                    () -> code.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                            "counter_value", 7L),
                    "a non-public guest kernel cannot be invoked as a host API");
            System.out.println("GRAAL_GUEST_KERNEL_DISPATCH_PASS calls=1024 value=43 private_export_denied=true");
        }
    }
}
