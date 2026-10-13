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
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.json("E", 1, String.valueOf((char) 0xd800), List.of(), true));
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.protoBuf("E", 1, "", List.of(String.valueOf((char) 0xdc00))));
    }

    @Test
    void msgpackAndProtobufReturnWireBytesNotPlaceholders() {
        List<Long> msgpack = GuestErrorWireCodec.messagePack(
                "ActorAskTimeoutError", 9007, "late", List.of("main"));
        assertEquals(0x85L, msgpack.getFirst());
        List<Long> compactMessagePack = GuestErrorWireCodec.messagePack(
                "ActorAskTimeoutError", 9007, "private", List.of("secret"), false);
        assertEquals(0x83L, compactMessagePack.getFirst()); // only schema/code/name
        assertTrue(compactMessagePack.size() < msgpack.size());
        assertEquals(0xa6L, msgpack.get(1));
        assertTrue(msgpack.stream().allMatch(b -> b >= 0 && b < 256));

        List<Long> protobuf = GuestErrorWireCodec.protoBuf(
                "ActorAskTimeoutError", 9007, "late", List.of("main"));
        assertEquals(List.of(8L, 1L, 16L, 175L, 70L), protobuf.subList(0, 5));
        assertTrue(protobuf.contains(26L));
        assertTrue(protobuf.contains(34L));
        assertTrue(protobuf.contains(42L));
        List<Long> compactProto = GuestErrorWireCodec.protoBuf(
                "ActorAskTimeoutError", 9007, "private", List.of("secret"), false);
        assertTrue(compactProto.size() < protobuf.size());
        assertFalse(compactProto.contains(34L)); // field 4 message omitted
        assertFalse(compactProto.contains(42L)); // field 5 stack omitted
    }

    @Test
    void mutableErrorReceiverIsRequiredOnlyForCapture() {
        String prelude = """
                define class LocalError as
                  pub let Array<String> stack = [];
                  pub capture(mut self)(): Array<String> {
                    self.stack = rt trace_frames();
                    return self.stack;
                  }
                end
                """;
        assertDoesNotThrow(() -> dev.oreslang.types.TypeChecker.check(
                dev.oreslang.parser.Parser.parse(prelude + """
                  fnc valid(): void {
                    val LocalError e = new LocalError();
                    e.capture();
                    return;
                  }
                  """)));
        assertDoesNotThrow(() -> dev.oreslang.types.TypeChecker.check(
                dev.oreslang.parser.Parser.parse(prelude + """
                  fnc ephemeral(): Array<String> {
                    return new LocalError().capture();
                  }
                  """)));
        assertThrows(IllegalArgumentException.class, () -> dev.oreslang.types.TypeChecker.check(
                dev.oreslang.parser.Parser.parse(prelude + """
                  fnc bad(): void {
                    const LocalError e = new LocalError();
                    e.capture();
                    return;
                  }
                  """)));
    }


    @Test
    void supervisorTransportNeverInvokesOrTransfersGuestFunctions() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Runnable forbiddenMethod = () -> {
            calls.incrementAndGet();
            throw new AssertionError("a guest method must never execute during transport");
        };
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("message", "password=private");
        fields.put("stack", List.of("secret/actor.ores", "worker"));
        fields.put("full_trace", true);
        fields.put("json_full", true);
        fields.put("binary_full", true);
        fields.put("code", forbiddenMethod);
        fields.put("method", forbiddenMethod);
        fields.put("callback", forbiddenMethod);
        fields.put("actor_ref", new Object());
        fields.put("self", fields); // Arbitrary cycle must not be followed.

        GuestErrorWireCodec.ErrorSnapshot transfer =
                GuestErrorWireCodec.supervisorCompactSnapshot("ActorAskTimeoutError", 9007, fields);
        assertEquals("{\\\"schema\\\":1,\\\"code\\\":9007,\\\"name\\\":\\\"ActorAskTimeoutError\\\"}",
                transfer.toJson());
        assertEquals(0x83L, transfer.toMessagePack().getFirst());
        assertEquals(List.of(8L, 1L, 16L, 175L, 70L),
                transfer.toProtoBuf().subList(0, 5));
        assertEquals(0, calls.get());
        assertEquals("", transfer.message());
        assertTrue(transfer.frames().isEmpty());
        assertFalse(transfer.toJson().contains("password"));
        assertFalse(transfer.toJson().contains("secret/actor.ores"));
    }

    @Test
    void trustedSnapshotCopiesOnlyAllowedScalarFieldsAndRejectsCallableFrames() {
        java.util.ArrayList<String> frames = new java.util.ArrayList<>(List.of("main", "process"));
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("message", "test");
        fields.put("stack", frames);
        fields.put("method", (Runnable) () -> {
            throw new AssertionError("guest methods must not run during encoding");
        });
        GuestErrorWireCodec.ErrorSnapshot trusted =
                GuestErrorWireCodec.trustedLocalSnapshot("Error", 0, fields);
        frames.clear(); // Sending an Error must not share the actor's heap.
        fields.put("message", "changed");
        assertEquals(List.of("main", "process"), trusted.frames());
        assertEquals("test", trusted.message());
        assertEquals("{\\\"schema\\\":1,\\\"code\\\":0,\\\"name\\\":\\\"Error\\\","
                + "\\\"message\\\":\\\"test\\\",\\\"stack\\\":[\\\"main\\\",\\\"process\\\"]}",
                trusted.toJson());

        fields.put("stack", List.of((Runnable) () -> {
            throw new AssertionError("should never invoke callable");
        }));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> GuestErrorWireCodec.trustedLocalSnapshot("Error", 0, fields));
        assertTrue(invalid.getMessage().contains("non-string"), invalid::getMessage);
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
                  stdio.stdout.write("|");
                  val captured = new Error().capture_stack();
                  stdio.stdout.write(captured.size);
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
