package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpPolicyHardeningTest {
    @Test
    void sourcePolicyRejectsRestartAmplificationFalseRegistryIdentityAndCorruptCounts()
            throws Exception {
        Path entry = Files.createTempFile("ores-otp-policy-hardening-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Supervisor from "std/otp/supervisor";
                    import module Registry from "std/otp/registry";
                    import module Server from "std/otp/gen_server";
                    import module Events from "std/otp/event_manager";

                    pub routine main(): void {
                      stdio.println(Supervisor.restart_budget_allows(1, 3, 4).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(2, 3, 4).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(0, 5, 4).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(0, 0, 0).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(0, 1, 0).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(3, 4).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(4, 4).unwrap());
                      stdio.println(Supervisor.restart_budget_allows(5, 4).is_err());
                      stdio.println(Supervisor.restart_budget_allows(5, 0, 4).is_err());
                      stdio.println(Supervisor.restart_budget_allows(0, -1, 4).is_err());
                      stdio.println(Supervisor.restart_budget_allows(0, 1, 4097).is_err());
                      stdio.println(Supervisor.restart_budget_allows(0, 2147483647, 4096).unwrap());
                      stdio.println(Registry.registration_matches(1, 2, 3, 1, 2, 3));
                      stdio.println(Registry.registration_matches(1, 2, 3, 4, 2, 3));
                      stdio.println(Registry.registration_matches(1, 2, 3, 1, 4, 3));
                      stdio.println(Registry.registration_matches(1, 2, 3, 1, 2, 4));
                      stdio.println(Registry.registration_matches(0, 2, 3, 0, 2, 3));
                      stdio.println(Registry.registration_matches(1, 0, 3, 1, 0, 3));
                      stdio.println(Registry.registration_matches(1, 2, -1, 1, 2, -1));
                      stdio.println(Registry.same_incarnation(3, 3));
                      stdio.println(Registry.capacity_allows(0, 1).unwrap());
                      stdio.println(Registry.capacity_allows(1, 1).unwrap());
                      stdio.println(Registry.capacity_allows(2, 1).is_err());
                      stdio.println(Server.pending_capacity_allows(0, 1).unwrap());
                      stdio.println(Server.pending_capacity_allows(1, 1).unwrap());
                      stdio.println(Server.pending_capacity_allows(2, 1).is_err());
                      stdio.println(Events.subscription_capacity_allows(0, 1).unwrap());
                      stdio.println(Events.subscription_capacity_allows(1, 1).unwrap());
                      stdio.println(Events.subscription_capacity_allows(2, 1).is_err());
                      stdio.println(Events.delivery_capacity_allows(1, 1, 1, 2).is_err());
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var linked = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), stdout, stderr);
            for (String name : List.of("supervisor", "registry", "gen_server", "event_manager")) {
                assertTrue(linked.units().containsKey("@std/otp/" + name + ".ores"), name);
            }
            assertEquals(String.join("\n", List.of(
                    "true",
                    "false",
                    "false",
                    "true",
                    "false",
                    "true",
                    "false",
                    "true",
                    "true",
                    "true",
                    "true",
                    "false",
                    "true",
                    "false",
                    "false",
                    "false",
                    "false",
                    "false",
                    "false",
                    "true",
                    "true",
                    "false",
                    "true",
                    "true",
                    "false",
                    "true",
                    "true",
                    "false",
                    "true",
                    "true")),
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
