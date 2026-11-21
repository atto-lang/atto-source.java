package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpSupervisorPolicyTest {
    @Test
    void packagedSourceIsOreslangOnlyAndHasNoAmbientHostEffects() throws Exception {
        String source = CoreLibrarySources.source("std/otp/supervisor");
        var parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/otp/supervisor"),
                CoreLibrarySources.fileName("std/otp/supervisor"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        for (String banned : List.of("java:", "stdio.", "process.", "network.",
                "socket.", "filesystem.", "http.", "await ", "Thread")) {
            assertFalse(source.contains(banned), () -> "OTP policy imported host effect: " + banned);
        }
        assertEquals("@std/otp/supervisor.ores", CoreLibrarySources.unitId("std/otp/supervisor"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("otp/supervisor.ores"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("otp/gen_server.ores"),
                "GenServer protocol slice is now an explicitly registered source module");
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/otp/../core"));
    }

    @Test
    void guestExecutesRestartSpansExitPoliciesAndIntensityRejection() throws Exception {
        Path entry = Files.createTempFile("ores-otp-supervisor-", ".ores");
        try {
            Files.writeString(entry, """
                    import module OtpSupervisor from "std/otp/supervisor";
                    pub routine main(): void {
                      val one = OtpSupervisor.restart_span("one_for_one", 2, 5).unwrap();
                      stdio.println(one.first);
                      stdio.println(one.end_exclusive);
                      val all = OtpSupervisor.restart_span("one_for_all", 2, 5).unwrap();
                      stdio.println(all.first);
                      stdio.println(all.end_exclusive);
                      val rest = OtpSupervisor.restart_span("rest_for_one", 2, 5).unwrap();
                      stdio.println(rest.first);
                      stdio.println(rest.end_exclusive);
                      stdio.println(OtpSupervisor.should_restart("permanent", true).unwrap());
                      stdio.println(OtpSupervisor.should_restart("transient", true).unwrap());
                      stdio.println(OtpSupervisor.should_restart("transient", false).unwrap());
                      stdio.println(OtpSupervisor.should_restart("temporary", false).unwrap());
                      stdio.println(OtpSupervisor.restart_budget_allows(3, 3).unwrap());
                      stdio.println(OtpSupervisor.restart_budget_allows(2, 3).unwrap());
                      stdio.println(OtpSupervisor.restart_budget_allows(0, 0).unwrap());
                      stdio.println(OtpSupervisor.restart_span("one_for_one", -1, 5).is_err());
                      stdio.println(OtpSupervisor.restart_span("one_for_one", 5, 5).is_err());
                      stdio.println(OtpSupervisor.restart_span("one_for_one", 0, 0).is_err());
                      stdio.println(OtpSupervisor.restart_span("one_for_one", 0, 4097).is_err());
                      stdio.println(OtpSupervisor.restart_span("unknown", 0, 5).is_err());
                      stdio.println(OtpSupervisor.should_restart("unknown", false).is_err());
                      stdio.println(OtpSupervisor.restart_budget_allows(-1, 3).is_err());
                      stdio.println(OtpSupervisor.restart_budget_allows(1, -1).is_err());
                      return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, err);
            assertTrue(build.units().containsKey("@std/otp/supervisor.ores"));
            assertEquals(String.join("\n", List.of(
                    "2", "3", "0", "5", "2", "5",
                    "true", "false", "true", "false",
                    "false", "true", "false",
                    "true", "true", "true", "true", "true",
                    "true", "true", "true")),
                    out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
