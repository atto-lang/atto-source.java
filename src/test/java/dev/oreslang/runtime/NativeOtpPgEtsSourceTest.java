package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NativeOtpPgEtsSourceTest {
    @Test
    void guestExecutesPgAndEtsPolicySlices() throws Exception {
        Path entry = Files.createTempFile("ores-otp-pg-ets-", ".ores");
        try {
            Files.writeString(entry, """
                    import module Pg from "otp/pg";
                    import module Ets from "std/otp/ets";

                    pub routine main(): void {
                      val Pg.Member<String> member =
                        Pg.member("worker-ref", 3).unwrap();
                      stdio.println(member.actor_ref);
                      stdio.println(member.incarnation);
                      stdio.println(Pg.member("worker-ref", 0).is_err());

                      stdio.println(Pg.membership_capacity_allows(0, 1).unwrap());
                      stdio.println(Pg.membership_capacity_allows(1, 1).unwrap());
                      stdio.println(Pg.membership_capacity_allows(2, 1).is_err());
                      stdio.println(Pg.group_capacity_allows(0, 2).unwrap());
                      stdio.println(Pg.group_capacity_allows(2, 2).unwrap());
                      stdio.println(Pg.group_capacity_allows(-1, 2).is_err());

                      stdio.println(Ets.entry_capacity_allows(0, 1).unwrap());
                      stdio.println(Ets.entry_capacity_allows(1, 1).unwrap());
                      stdio.println(Ets.entry_capacity_allows(2, 1).is_err());
                      stdio.println(Ets.insert_capacity_allows(1, 1, true).unwrap());
                      stdio.println(Ets.insert_capacity_allows(1, 1, false).unwrap());
                      stdio.println(Ets.insert_capacity_allows(2, 1, true).is_err());
                      stdio.println(Ets.query_limit_valid(1, 2, 3).unwrap());
                      stdio.println(Ets.query_limit_valid(3, 2, 3).unwrap());
                      stdio.println(Ets.query_limit_valid(-1, 2, 3).is_err());
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

            assertTrue(build.units().containsKey("@std/otp/pg.ores"));
            assertTrue(build.units().containsKey("@std/otp/ets.ores"));
            assertEquals(String.join("\n", List.of(
                    "worker-ref", "3", "true",
                    "true", "false", "true",
                    "true", "false", "true",
                    "true", "false", "true",
                    "true", "false", "true",
                    "true", "false", "true")),
                    out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void canonicalPgAndEtsAliasesCoalesceToOneUnitEach() throws Exception {
        assertEquals(
                "@std/otp/pg.ores",
                dev.oreslang.stdlib.CoreLibrarySources.unitId("otp/pg"));
        assertEquals(
                dev.oreslang.stdlib.CoreLibrarySources.unitId("otp/pg"),
                dev.oreslang.stdlib.CoreLibrarySources.unitId("std/otp/pg"));
        assertEquals(
                "@std/otp/ets.ores",
                dev.oreslang.stdlib.CoreLibrarySources.unitId("otp/ets"));
        assertEquals(
                dev.oreslang.stdlib.CoreLibrarySources.unitId("otp/ets"),
                dev.oreslang.stdlib.CoreLibrarySources.unitId("std/otp/ets"));
    }
}
