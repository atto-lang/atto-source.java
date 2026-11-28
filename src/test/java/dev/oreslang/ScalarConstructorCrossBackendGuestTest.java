package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-identical restricted constructor parity, no host model. */
final class ScalarConstructorCrossBackendGuestTest {
    private static final Path ROOT = Path.of("tests", "fixtures", "class-scalar-constructor-v1");

    @Test
    void directParameterFieldInitializationExecutesAsGuest() throws Exception {
        Path fixture = ROOT.resolve("class-scalar-constructor-field-init.atto");
        LinkedProgramRunner.validate(fixture, Map.of());
        var result = LinkedProgramRunner.runWithExitStatus(fixture, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus());
    }

    @Test
    void duplicateUnknownAndMissingFinalFieldInitializationRejectBeforeExecution() {
        for (String name : new String[]{"duplicate", "unknown", "missing"}) {
            Path fixture = ROOT.resolve("class-scalar-constructor-field-init-" + name + ".atto");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(fixture, Map.of()), name);
        }
    }
}
