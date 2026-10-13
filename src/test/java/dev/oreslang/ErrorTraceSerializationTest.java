package dev.oreslang;

import dev.oreslang.runtime.GuestErrorWireCodec;
import dev.oreslang.runtime.GuestTraceCapture;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ErrorTraceSerializationTest {
    @TempDir Path temp;

    @Test
    void guestTraceUsesCallableFramesNotHostStacks() {
        assertTrue(GuestTraceCapture.frames().isEmpty());
        try (GuestTraceCapture.Scope outer = GuestTraceCapture.enter("main")) {
            try (GuestTraceCapture.Scope inner = GuestTraceCapture.enter("worker")) {
                assertEquals(List.of("main", "worker"), GuestTraceCapture.frames());
                assertEquals("main\nworker", GuestTraceCapture.text());
            }
            assertEquals(List.of("main"), GuestTraceCapture.frames());
        }
        assertTrue(GuestTraceCapture.frames().isEmpty());
    }

    @Test
    void jsonEscapesMessagesAndCompactFormOmitsDetails() {
        assertEquals("{\"schema\":1,\"code\":9007,\"name\":\"ActorAskTimeoutError\"}",
                GuestErrorWireCodec.json("ActorAskTimeoutError", 9007,
                        "secret", List.of("main"), false));
        assertEquals(
                "{\"schema\":1,\"code\":9007,\"name\":\"ActorAskTimeoutError\","
                        + "\"message\":\"test\\n\\\"escaped\\\"\",\"stack\":[\"main\"]}",
                GuestErrorWireCodec.json("ActorAskTimeoutError", 9007,
                        "test\n\"escaped\"", List.of("main"), true));
        assertEquals("Error (0)", GuestErrorWireCodec.text("Error", 0, "", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.json("E", 1, "x".repeat(4097), List.of(), true));
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.json("E", 1, "", List.of("x".repeat(257)), true));
    }

    @Test
    void msgpackAndProtobufReturnWireBytesNotPlaceholders() {
        List<Long> msgpack = GuestErrorWireCodec.messagePack(
                "ActorAskTimeoutError", 9007, "late", List.of("main"));
        assertEquals(0x85L, msgpack.getFirst());
        assertEquals(0xa6L, msgpack.get(1));
        assertTrue(msgpack.stream().allMatch(b -> b >= 0 && b < 256));

        List<Long> protobuf = GuestErrorWireCodec.protoBuf(
                "ActorAskTimeoutError", 9007, "late", List.of("main"));
        assertEquals(List.of(8L, 1L, 16L, 175L, 70L), protobuf.subList(0, 5));
        assertTrue(protobuf.contains(26L));
        assertTrue(protobuf.contains(34L));
        assertTrue(protobuf.contains(42L));
    }

    @Test
    void sourceCapturesTraceWithoutConstructingAnException() throws Exception {
        Path entry = temp.resolve("trace.ores");
        Files.writeString(entry, """
                import fnc capture_trace_string from "std/tracing";
                import fnc capture_trace_frames from "std/tracing";
                import class Error from "std/error";

                fnc probe(): String { return capture_trace_string(); }

                pub routine main(): void {
                  stdio.stdout.write(probe());
                  stdio.stdout.write("|");
                  val e = new Error();
                  stdio.stdout.write(e.to_json());
                  stdio.stdout.write("|");
                  e.capture_stack();
                  stdio.stdout.write(e.to_json());
                  stdio.stdout.write("|");
                  val frames = capture_trace_frames();
                  stdio.stdout.write(frames.size);
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("probe"), output);
        assertTrue(output.contains("capture_trace_string"), output);
        assertTrue(output.contains("\"name\":\"Error\",\"message\":\"\",\"stack\":[]"), output);
        assertTrue(output.contains("\"capture_stack\""), output);
        assertFalse(output.contains("java.lang.Thread"), output);
    }
}
