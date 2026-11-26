package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AttoAllocGenericSourceGuestTest {
    @Test
    void originalGenericCollectionGuestMustLinkAndExecute() throws Exception {
        Path entry = Path.of("tests", "fixtures", "alloc-collections-source", "positive.atto");
        assertTrue(Files.isRegularFile(entry));
        var build = LinkedProgramRunner.validate(entry, Map.of());
        assertTrue(build.units().size() >= 4, "all three original imported modules must link");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertEquals(0, result.exitStatus(), err.toString());
        assertEquals("", out.toString());
    }
}
