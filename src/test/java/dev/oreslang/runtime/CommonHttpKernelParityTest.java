package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Identical guest Oreslang source is compiled/executed by LLVM and GraalVM.
 * HTTP transport, actor isolation, and request dispatch are deliberately not
 * claimed to share source yet.
 */
final class CommonHttpKernelParityTest {
    @Test
    void executesTheSameGuestCounterKernelAsTheLlvmNativeCompiler() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/parity/http-shared-counter-kernel.ores")) {
            assertNotNull(stream, "shared .ores fixture absent");
            bytes = stream.readAllBytes();
        }
        String guest = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(guest.contains("fnc counter_value(int count): int"));
        OresCompiler.parseAndTypeCheck(guest);
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            var result = ctx.eval(Source.newBuilder(OresLanguage.ID, guest, "http-shared-counter-kernel.ores").build());
            assertEquals(43L, result.asLong(), "real guest source code must execute");
        }
        System.out.println("SHARED_ORESLANG_KERNEL_GRAAL_PASS result=43 source_bytes=" + bytes.length);
    }
}
