package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shares the exact Future-select guest fixture with AOT and hybrid CLI gates. */
final class NativeFutureSelectSmokeTest {
    @Test
    @Timeout(15)
    void typedAwaitArmWinsOverPendingChannelOnJVM() throws Exception {
        String source = Files.readString(
                Path.of("examples", "native-future-select-smoke.ores"));
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("future:4", SelectNestedReturnContractTest.run(source));
    }
}
