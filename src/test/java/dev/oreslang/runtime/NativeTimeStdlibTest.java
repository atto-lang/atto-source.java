package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile and execute actual packaged native Atto time source; source-inspection
 * tests below are not substituted for guest execution.
 */
final class NativeTimeStdlibTest {
    @TempDir Path temp;

    @Test
    void nativeTimeIsCompilerOwnedPackagedAttoAndDoesNotAllowLegacyShadowing()
            throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("time.atto"));
        assertEquals("@std/time.atto", CoreLibrarySources.unitId("std/time"));
        assertEquals(CoreLibrarySources.unitId("std/time"),
                CoreLibrarySources.unitId("ores.std/time.atto"));
        assertEquals("time.atto", CoreLibrarySources.fileName("std/time"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/time.ores"));
        String source = CoreLibrarySources.source("std/time");
        var parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/time"),
                CoreLibrarySources.fileName("std/time"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertTrue(source.contains("pub define class Duration"));
        assertTrue(source.contains("pub define class Timer"));
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("Thread.sleep"));
        assertFalse(source.contains("java.util"));
    }

    @Test
    void compiledGuestLinksNativeDurationAndChecksOverflowWithoutHostArithmetic()
            throws Exception {
        Path entry = temp.resolve("time_guest.atto");
        Files.writeString(entry, """
                import module time from "std/time";
                pub routine main(): void {
                  val one = time.seconds(1).expect("one");
                  stdio.println(one.as_ns());
                  stdio.println(one.as_ms_ceil());
                  val tiny = time.microseconds(1).expect("tiny");
                  stdio.println(tiny.as_ms_floor());
                  stdio.println(tiny.as_ms_ceil());
                  stdio.println(time.milliseconds(-1).is_err());
                  stdio.println(time.hours(2562048).is_err());
                  stdio.println(one.checked_add(one).expect("sum").as_ns());
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, errors);
        assertTrue(linked.units().containsKey("@std/time.atto"),
                "compiler must link trusted packaged .atto guest source");
        assertEquals(String.join("\n",
                "1000000000", "1000", "0", "1", "true", "true",
                "2000000000"),
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void projectLocalFakeTimeMustNotShadowCompilerOwnedTime()
            throws Exception {
        Path fake = Files.createDirectories(temp.resolve("std"));
        Files.writeString(fake.resolve("time.atto"),
                "define module time as pub fnc seconds(int n): int { return 7; } end");
        Path entry = temp.resolve("time_shadow.atto");
        Files.writeString(entry, """
                import module time from "std/time";
                pub routine main(): void {
                  stdio.println(time.seconds(1).expect("real").as_ns());
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/time.atto"));
        assertEquals("1000000000", output.toString(StandardCharsets.UTF_8).trim());
    }
}
