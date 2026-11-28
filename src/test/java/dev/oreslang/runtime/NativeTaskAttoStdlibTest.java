package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual compiler-linked Atto std/task module, not a host policy simulation. */
@Timeout(45)
final class NativeTaskAttoStdlibTest {
    @Test
    void taskModuleUsesPackagedNativeAttoWithClosedImportIdentity() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("task.atto"));
        assertEquals("@std/task.atto", CoreLibrarySources.unitId("std/task"));
        assertEquals(CoreLibrarySources.unitId("std/task"),
                CoreLibrarySources.unitId("ores.std/task.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/task.ores"));
        String source = CoreLibrarySources.source("std/task");
        var parsed = MixedSourceUnit.parse("@std/task.atto", "task.atto", source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
    }

    @Test
    void nativeTaskPolicyAndActualChildTaskExecuteInGuest() throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/task-v1.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/task.atto"),
                "std/task must be compiled from .atto, never substituted by Java");
        assertEquals("42", output.toString(StandardCharsets.UTF_8).trim(),
                () -> errors.toString(StandardCharsets.UTF_8));
    }
}
