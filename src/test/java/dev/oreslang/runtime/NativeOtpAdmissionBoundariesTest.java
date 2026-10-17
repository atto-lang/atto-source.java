package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpAdmissionBoundariesTest {
    @Test
    void guestExercisesCompositeAdmissionAndMonotonicDeadlineBoundaries() throws Exception {
        Path entry = Files.createTempFile("ores-otp-admission-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Server from "std/otp/gen_server";
                    import module Events from "std/otp/event_manager";
                    import module Statem from "std/otp/gen_statem";

                    pub routine main(): void {
                      stdio.println(Server.pending_capacity_allows(0, 0, 0, 1).unwrap());
                      stdio.println(Server.pending_capacity_allows(0, 0, 1, 1).unwrap());
                      stdio.println(Server.pending_capacity_allows(2, 1, 0, 3).unwrap());
                      stdio.println(Server.pending_capacity_allows(2, 2, 0, 3).is_err());
                      stdio.println(Server.pending_capacity_allows(0, 0, 0, 0).is_err());
                      stdio.println(Server.pending_capacity_allows(0, -1, 0, 4).is_err());
                      stdio.println(Server.pending_capacity_allows(0, 0, 5, 4).is_err());
                      stdio.println(Server.pending_capacity_allows(999999, 0, 0, 1000000).unwrap());
                      stdio.println(Server.pending_capacity_allows(999999, 1, 0, 1000000).unwrap());

                      stdio.println(Events.delivery_capacity_allows(0, 0, 0, 1).unwrap());
                      stdio.println(Events.delivery_capacity_allows(1, 0, 0, 1).unwrap());
                      stdio.println(Events.delivery_capacity_allows(0, 0, 1, 1).unwrap());
                      stdio.println(Events.delivery_capacity_allows(2, 1, 0, 2).is_err());
                      stdio.println(Events.delivery_capacity_allows(0, 0, 0, 4097).is_err());
                      stdio.println(Events.delivery_capacity_allows(0, 0, -1, 2).is_err());

                      stdio.println(Statem.timer_due(5, 5, 5).unwrap());
                      stdio.println(Statem.timer_due(5, 6, 7).unwrap());
                      stdio.println(Statem.timer_due(6, 5, 7).is_err());
                      stdio.println(Statem.timer_due(-1, 0, 0).is_err());
                      stdio.println(Statem.timer_due(0, 1000, 1000).unwrap());
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var linked = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    stdout, stderr);
            for (String module : List.of("gen_server", "event_manager", "gen_statem")) {
                assertTrue(linked.units().containsKey("@std/otp/" + module + ".ores"), module);
            }
            assertEquals(String.join("\n", List.of(
                    "true", "false", "false", "true", "true", "true", "true",
                    "true", "false",
                    "true", "false", "false", "true", "true", "true",
                    "true", "false", "true", "true", "true")),
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
