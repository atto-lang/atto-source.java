package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real guest execution is required before claiming these source modules work.
 * These fixtures deliberately exercise the compiler-owned Atto import path.
 */
final class NativeAttoMetricsJsonZordeTest {
    private static final String DIR =
            "src/test/resources/stdlib-parity/metrics-json-zorde/";

    @Test
    void modulesAreClosedSourceOnlyImports() throws Exception {
        for (String name : Set.of("metrics", "json", "zorde")) {
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(name + ".atto"));
            assertEquals("@std/" + name + ".atto",
                    CoreLibrarySources.unitId("std/" + name));
            assertEquals(CoreLibrarySources.unitId("std/" + name),
                    CoreLibrarySources.unitId("ores.std/" + name));
            String source = CoreLibrarySources.source("std/" + name);
            var parsed = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId("std/" + name),
                    CoreLibrarySources.fileName("std/" + name), source);
            assertTrue(parsed.hasOresSource());
            assertFalse(parsed.hasJavaSource());
            assertFalse(source.contains("java:"));
            assertFalse(source.contains("import java"));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + name + ".ores"));
        }
    }

    private static void assertGuest(String name, String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var compiled = LinkedProgramRunner.run(Path.of(DIR + name + ".atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                out, err);
        assertTrue(compiled.units().containsKey("@std/" + name + ".atto"),
                "missing compiler-owned Atto module " + name);
        assertEquals(String.join("\n", lines),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void metricsGuestRejectsOverflowAndBucketsSamples() throws Exception {
        assertGuest("metrics", "true", "false", "true", "false", "1",
                "false", "true", "-1", "true", "true", "true", "false",
                "3", "23", "1", "1", "1", "1");
    }

    @Test
    void jsonGuestRejectsAmbiguousAndMalformedText() throws Exception {
        assertGuest("json", "true", "6", "true", "true", "true",
                "true", "true", "true", "2147483647",
                "true", "true", "true");
    }

    @Test
    void zordeGuestRoundtripsDeterministicFrame() throws Exception {
        assertGuest("zorde", "true", "true", "7", "1", "2",
                "255", "true", "90", "79");
    }
}
