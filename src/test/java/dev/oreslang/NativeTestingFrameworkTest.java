package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeTestingFrameworkTest {
    @Test void nativeFrameworkReturnsPassSkipAndPropertyRecordsWithoutIo() throws Exception {
        assertTrue(run(Path.of("examples/native-testing.ores")).isEmpty());
    }
    @Test void nativeFrameworkEnforcesFilteringAndFailFastWithoutIo() throws Exception {
        assertTrue(run(Path.of("examples/native-testing-semantics.ores")).isEmpty());
    }

    private static String run(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}
