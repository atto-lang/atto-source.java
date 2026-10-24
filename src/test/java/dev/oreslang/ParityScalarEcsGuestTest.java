package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class ParityScalarEcsGuestTest {
    @TempDir Path temp;

    @Test
    void sourceCompilerExecutesSameScalarEcsGuestAsLlvm() throws Exception {
        String source;
        try (InputStream input = getClass().getResourceAsStream(
                "/parity/ecs-scalar-guest-baseline.ores")) {
            assertNotNull(input, "shared source-level parity fixture is missing");
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path entry = temp.resolve("ecs-scalar-guest-baseline.ores");
        Files.writeString(entry, source, StandardCharsets.UTF_8);
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus(),
                "GraalVM emitted guest result must match LLVM's --run 42 fixture");
    }
}
