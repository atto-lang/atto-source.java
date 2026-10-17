package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpBehaviorSlicesTest {
    private static final List<String> MODULES = List.of(
            "gen_server", "supervisor", "gen_statem",
            "application", "event_manager", "registry");

    @Test
    void exactSixOtpModulesAreExplicitlyRegisteredAndPureOreslang() throws Exception {
        Set<String> actual = CoreLibrarySources.packagedSourcePaths().stream()
                .filter(path -> path.startsWith("otp/"))
                .collect(Collectors.toSet());
        Set<String> expected = MODULES.stream()
                .map(name -> "otp/" + name + ".ores")
                .collect(Collectors.toSet());
        assertEquals(expected, actual);

        for (String name : MODULES) {
            String importPath = "std/otp/" + name;
            String source = CoreLibrarySources.source(importPath);
            var parsed = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId(importPath),
                    CoreLibrarySources.fileName(importPath),
                    source);
            assertTrue(parsed.hasOresSource(), name);
            assertFalse(parsed.hasJavaSource(), name);
            for (String banned : List.of(
                    "java:", "stdio.", "process.", "network.", "socket.",
                    "filesystem.", "http.", "Thread")) {
                assertFalse(source.contains(banned),
                        () -> name + " imported ambient/host effect: " + banned);
            }
        }
    }

    @Test
    void guestExecutesGenStatemApplicationEventManagerAndRegistrySlices() throws Exception {
        Path entry = Files.createTempFile("ores-otp-six-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Statem from "std/otp/gen_statem";
                    import module Application from "std/otp/application";
                    import module Events from "std/otp/event_manager";
                    import module Registry from "std/otp/registry";

                    pub routine main(): void {
                      val Statem.Transition<String, int> transition =
                        Statem.next("connected", 1);
                      stdio.println(transition.state);
                      stdio.println(transition.data);
                      stdio.println(transition.defer_event);
                      stdio.println(transition.stop);

                      val deferred = Statem.next_and_defer("waiting", 2);
                      stdio.println(deferred.defer_event);
                      stdio.println(Statem.stop("closed", 3).stop);
                      stdio.println(Statem.timer_slot_valid(0, 1).unwrap());
                      stdio.println(Statem.timer_slot_valid(1, 1).unwrap());
                      stdio.println(Statem.timer_slot_valid(-1, 1).is_err());

                      stdio.println(Application.dependencies_ready(2, 2).unwrap());
                      stdio.println(Application.dependencies_ready(1, 2).unwrap());
                      stdio.println(Application.dependencies_ready(3, 2).is_err());
                      val rollback = Application.rollback_span(3).unwrap();
                      stdio.println(rollback.first);
                      stdio.println(rollback.end_exclusive);
                      stdio.println(Application.rollback_span(4097).is_err());

                      val Events.HandlerResult<int> kept = Events.keep(9);
                      stdio.println(kept.state);
                      stdio.println(kept.remove_handler);
                      stdio.println(Events.remove(10).remove_handler);
                      stdio.println(Events.subscription_capacity_allows(0, 1).unwrap());
                      stdio.println(Events.subscription_capacity_allows(1, 1).unwrap());

                      val Registry.Entry<String, String> registered =
                        Registry.entry("worker", "ref-1", 7).unwrap();
                      stdio.println(registered.name);
                      stdio.println(registered.ref);
                      stdio.println(registered.incarnation);
                      stdio.println(Registry.same_incarnation(7, 7));
                      stdio.println(Registry.same_incarnation(7, 8));
                      stdio.println(Registry.entry("worker", "ref-1", 0).is_err());
                      stdio.println(Registry.capacity_allows(0, 1).unwrap());
                      stdio.println(Registry.capacity_allows(1, 1).unwrap());
                      return;
                    }
                    """);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err);

            for (String name : List.of(
                    "gen_statem", "application", "event_manager", "registry")) {
                assertTrue(build.units().containsKey("@std/otp/" + name + ".ores"),
                        name);
            }

            assertEquals(String.join("\n", List.of(
                    "connected", "1", "false", "false",
                    "true", "true", "true", "false", "true",
                    "true", "false", "true", "0", "3", "true",
                    "9", "false", "true", "true", "false",
                    "worker", "ref-1", "7", "true", "false", "true",
                    "true", "false")),
                    out.toString(StandardCharsets.UTF_8)
                            .trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void traversalAndUnregisteredOtpModulesStillFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/otp/../core"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/otp/release"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/otp/ets"));
    }
}
