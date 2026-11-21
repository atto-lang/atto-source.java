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

final class NativeOtpGenServerProtocolTest {

    @Test
    void packagedGenServerProtocolIsPureOreslangWithNoAmbientHostEffects() throws Exception {
        String source = CoreLibrarySources.source("std/otp/gen_server");
        var parsed = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/otp/gen_server"),
                CoreLibrarySources.fileName("std/otp/gen_server"), source);

        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        for (String banned : List.of(
                "java:", "stdio.", "process.", "network.", "socket.",
                "filesystem.", "http.", "await ", "spawn ", "Thread")) {
            assertFalse(source.contains(banned),
                    () -> "GenServer protocol imported ambient/host effect: " + banned);
        }

        assertEquals("@std/otp/gen_server.ores",
                CoreLibrarySources.unitId("std/otp/gen_server"));
        assertTrue(CoreLibrarySources.packagedSourcePaths()
                .contains("otp/gen_server.ores"));
    }

    @Test
    void guestExecutesTypedInitCallCastInfoAndBoundedAdmission() throws Exception {
        Path entry = Files.createTempFile("ores-otp-gen-server-", ".ores");
        try {
            Files.writeString(entry, """
                    import module GenServer from "std/otp/gen_server";

                    pub routine main(): void {
                      val GenServer.InitResult<int> initialized =
                        GenServer.initialized(10);
                      stdio.println(initialized.state);

                      val GenServer.CallResult<int, String> call =
                        GenServer.reply(11, "ok");
                      stdio.println(call.state);
                      stdio.println(call.reply);
                      stdio.println(call.stop);

                      val GenServer.CallResult<int, String> stopping_call =
                        GenServer.reply_and_stop(12, "bye");
                      stdio.println(stopping_call.state);
                      stdio.println(stopping_call.reply);
                      stdio.println(stopping_call.stop);

                      val GenServer.CastResult<int> cast =
                        GenServer.continue_cast(13);
                      stdio.println(cast.state);
                      stdio.println(cast.stop);

                      val GenServer.CastResult<int> stopping_cast =
                        GenServer.stop_cast(14);
                      stdio.println(stopping_cast.stop);

                      val GenServer.InfoResult<int> info =
                        GenServer.continue_info(15);
                      stdio.println(info.state);
                      stdio.println(info.stop);
                      stdio.println(GenServer.stop_info(16).stop);

                      stdio.println(
                        GenServer.pending_capacity_allows(0, 1).unwrap());
                      stdio.println(
                        GenServer.pending_capacity_allows(1, 1).unwrap());
                      stdio.println(
                        GenServer.pending_capacity_allows(-1, 1).is_err());
                      stdio.println(
                        GenServer.pending_capacity_allows(0, 0).is_err());
                      stdio.println(
                        GenServer.pending_capacity_allows(0, 1000001).is_err());
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

            assertTrue(build.units().containsKey("@std/otp/gen_server.ores"));
            assertEquals(String.join("\n", List.of(
                    "10",
                    "11", "ok", "false",
                    "12", "bye", "true",
                    "13", "false",
                    "true",
                    "15", "false", "true",
                    "true", "false",
                    "true", "true", "true")),
                    out.toString(StandardCharsets.UTF_8)
                            .trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void replyResultTypesDoNotEraseReplyPayloadType() throws Exception {
        Path entry = Files.createTempFile("ores-otp-gen-server-type-", ".ores");
        try {
            Files.writeString(entry, """
                    import module GenServer from "std/otp/gen_server";

                    pub routine main(): void {
                      val GenServer.CallResult<int, String> wrong =
                        GenServer.reply(1, 42);
                      stdio.println(wrong.reply);
                      return;
                    }
                    """);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.run(
                            entry,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            out,
                            err),
                    "GenServer Reply must remain statically typed");
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void sourceDoesNotPretendRuntimeCallAsyncExists() throws Exception {
        String source = CoreLibrarySources.source("std/otp/gen_server");
        assertFalse(source.contains("call_async("));
        assertFalse(source.contains("ReplyToken"));
        assertFalse(source.contains("ReplyCapability"));
        assertFalse(source.contains("ActorRef"));
    }
}
