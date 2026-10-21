package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NativeHatStdlibTest {
    @Test
    void hatIsPackagedPortableOreslangWithoutHostInteropOrFakeDispatch() throws Exception {
        String source = CoreLibrarySources.source("std/hat");
        MixedSourceUnit parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/hat"),
                CoreLibrarySources.fileName("std/hat"),
                source);

        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("pub fnc dispatch"),
                "HAT must not expose dispatch before compiler/runtime device lowering exists");
        assertFalse(source.contains("pub fnc upload"),
                "HAT must not fake host/device transfer in userland");
        assertFalse(source.contains("pub fnc download"),
                "HAT must not fake host/device transfer in userland");
    }

    @Test
    void portableDescriptorsAndAdmissionHelpersExecuteInGuest() throws Exception {
        Path entry = Files.createTempFile("ores-native-hat-", ".ores");
        try {
            Files.writeString(entry, """
                    import module hat from "std/hat";

                    pub routine main(): void {
                      val device = hat.device_info(
                        "cpu-reference",
                        "reference device",
                        0,
                        64,
                        1024,
                        1048576,
                        true,
                        false
                      );
                      val layout = hat.buffer_layout("aosoa", 4096, 4, 16, 8);
                      val work = hat.work_2d(64, 32, 8, 4);
                      val accepted = hat.requirements(64, 524288, true, false);
                      val rejected = hat.requirements(64, 2097152, true, false);

                      stdio.println(hat.api_version());
                      stdio.println(device.backend);
                      stdio.println(layout.kind);
                      stdio.println(layout.lanes);
                      stdio.println(work.dimensions);
                      stdio.println(work.global_y);
                      stdio.println(hat.device_satisfies(&device, &accepted));
                      stdio.println(hat.device_satisfies(&device, &rejected));
                      return;
                    }
                    """);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    stdout,
                    stderr);

            assertTrue(build.units().containsKey("@std/hat.ores"));
            assertEquals(
                    "1\ncpu-reference\naosoa\n8\n2\n32\ntrue\nfalse",
                    stdout.toString(StandardCharsets.UTF_8)
                            .trim()
                            .replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
