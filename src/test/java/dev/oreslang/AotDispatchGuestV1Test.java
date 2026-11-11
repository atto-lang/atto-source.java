package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real guest-source semantic regression, NOT evidence of native guest AOT.
 * Native AOT and every other backend require independent execution receipts.
 */
final class AotDispatchGuestV1Test {
    @Test
    void baseTypedReceiverRetainsVirtualOverrideAndStaticCallsStayQualified()
            throws Exception {
        String source = Files.readString(
                Path.of("tests/fixtures/dispatch/known_and_virtual_v1.atto"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "known_and_virtual_v1.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("121020", out.toString(StandardCharsets.UTF_8));
    }
}
