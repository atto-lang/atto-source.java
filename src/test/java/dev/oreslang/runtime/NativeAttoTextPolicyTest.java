package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoTextPolicyTest {
    @Test
    void canonicalTextRulesAreNativeAttoAndCompilerOwned() throws Exception {
        assertEquals("@std/text.atto", CoreLibrarySources.unitId("ores.std/text"));
        assertEquals("text.atto", CoreLibrarySources.fileName("std/text"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/text.ores"));
        String atto = CoreLibrarySources.source("std/text");
        assertTrue(atto.contains("define module text"));
        assertFalse(atto.contains("java:"));
        assertFalse(atto.contains("do java"));
        assertFalse(atto.contains("java {"));
    }

    @Test
    void linkedGuestTextPolicyMatchesLegacyAndUnicodeScalarSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/text-policy.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/text.atto"),
                "must compile the .atto text source, not only execute Java regex policy");
        String expected = IntStream.range(0, 17).mapToObj(i -> "true")
                .collect(Collectors.joining("\n")) + "\nfalse\n3\n65\n128512\n20013";
        assertEquals(expected,
                output.toString(StandardCharsets.UTF_8).replace("\r", "").trim(),
                () -> errors.toString(StandardCharsets.UTF_8));
    }
}
