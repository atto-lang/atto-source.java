package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoHttpStdlibTest {
    @Test
    void httpPolicyIsCompilerOwnedAttoAndGuestExecutable() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("http.atto"));
        assertEquals("@std/http.atto", CoreLibrarySources.unitId("std/http"));
        assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source("std/http.ores"));
        String source = CoreLibrarySources.source("std/http");
        assertTrue(source.contains("define module http as"));
        assertFalse(source.contains("java:"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/http-module.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/http.atto"),
                "HTTP decisions must execute as linked Atto guest source");
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().replace("\r", "").split("\n");
        assertEquals(66, lines.length, () -> err.toString(StandardCharsets.UTF_8));
        for (int i = 0; i < lines.length; i++) {
            assertEquals("true", lines[i], "HTTP case " + (i + 1) + ": "
                    + err.toString(StandardCharsets.UTF_8));
        }
    }
}
