package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Byte-identical LLVM fresh-array constructor fixtures, actual JVM guests. */
final class ArrayConstructorCrossBackendGuestTest {
    private static final Path ROOT = Path.of("tests", "fixtures", "class-array-constructor-v1");

    @Test
    void freshArrayFieldsHaveIndependentStorage() throws Exception {
        Path fixture = ROOT.resolve("class-explicit-array-constructor.atto");
        LinkedProgramRunner.validate(fixture, Map.of());
        var result = LinkedProgramRunner.runWithExitStatus(fixture, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus());
    }

    @Test
    void rejectsUnprovenFreshAllocationExpressionsInDeferredPrefix() {
        for (String expression : new String[]{
                "new Array<string>()", "new Array<int>(1)", "self.first", "make()"}) {
            String source = "define class Pair as pub final mut Array<int> first; "
                    + "constructor() { self.first = " + expression + "; } end";
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.parser.Parser.parse(source), expression);
            assertTrue(failure.getMessage().contains("E_INIT"), failure::getMessage);
        }
    }

    @Test
    void duplicateAndMissingFinalArrayInitializationRejectBeforeExecution() {
        for (String name : new String[]{"duplicate", "missing"}) {
            Path fixture = ROOT.resolve("class-explicit-array-constructor-" + name + "-reject.atto");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(fixture, Map.of()), name);
        }
    }
}
