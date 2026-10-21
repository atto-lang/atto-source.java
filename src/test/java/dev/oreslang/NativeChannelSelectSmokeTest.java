package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keep the compiled native-image smoke fixture covered by the JVM parser,
 * typechecker and executable guest behavior. The runtime-only integration
 * must not accidentally test a newer, unmerged '->' / Future-arm grammar.
 */
final class NativeChannelSelectSmokeTest {
    @Test
    @Timeout(15)
    void nativeFixturePreservesPriorityAndLosingBufferedValue() throws Exception {
        String source = Files.readString(
                Path.of("examples", "native-channel-select-smoke.ores"));
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("first:7:9", SelectNestedReturnContractTest.run(source));
    }
}
