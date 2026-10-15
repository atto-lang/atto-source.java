package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

final class NativeOresHttpStdlibTest {
    @Test void targetControlsOverflowAndPerHeadLimitsExecuteInOreslang() throws Exception {
        String valid = "GET /shared HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String[] requests = {valid + "x".repeat(8192),
                "GET /bad\r\npath HTTP/1.1\r\nHost: localhost\r\n\r\n",
                "GET /bad\\path HTTP/1.1\r\nHost: localhost\r\n\r\n",
                "GET /bad#fragment HTTP/1.1\r\nHost: localhost\r\n\r\n",
                "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n", "x".repeat(8192)};
        int[] expected = {200, 400, 400, 400, 404, 431};
        StringBuilder code = new StringBuilder("import module http_wire from \"std/http\"; pub routine main(): int {\n");
        for (int i = 0; i < requests.length; ++i) {
            String bytes = requests[i].chars().mapToObj(Integer::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            code.append("val Array<int> input").append(i).append(" = arr[").append(bytes).append("];\n")
                    .append("val http_wire.Head head").append(i)
                    .append(" = http_wire.parse_head(rt borrow input").append(i).append(");\n")
                    .append("if head").append(i).append(".status != ").append(expected[i])
                    .append("; then return 1; fi\n")
                    .append("if head").append(i).append(".keep_alive != ").append(i == 0 ? "true" : "false")
                    .append("; then return 2; fi\n");
        }
        code.append("val Array<int> tiny = arr[1];\n")
                .append("if http_wire.byte_equal(rt borrow tiny, 9223372036854775807, arr[1]); then return 3; fi\n")
                .append("return 0; }");
        Path guest = Files.createTempFile("ores-http-boundaries-", ".ores");
        try {
            Files.writeString(guest, code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(0, LinkedProgramRunner.runWithExitStatus(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, new ByteArrayOutputStream()).exitStatus());
            assertEquals(0, out.size());
        } finally { Files.deleteIfExists(guest); }
    }

    @Test void httpModuleIsOreslangOnlyAndClosedRegistered() throws Exception {
        String source = CoreLibrarySources.source("std/http");
        MixedSourceUnit compiled = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/http"), "http.ores", source);
        assertTrue(compiled.hasOresSource());
        assertFalse(compiled.hasJavaSource());
        assertFalse(source.contains("http.listen("));
    }

    @Test void incrementalHttpFramingAndSmugglingRegressionsExecuteAsOreslang() throws Exception {
        Path guest = Files.createTempFile("ores-native-http-wire-", ".ores");
        try {
            Files.writeString(guest, """
                import module http_wire from "std/http";
                pub routine main(): int {
                  val Array<int> valid_shared = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10];
                  val http_wire.Head valid_shared_head = http_wire.parse_head(rt borrow valid_shared);
                  if valid_shared_head.status != 200; then return 1; fi
                  if valid_shared_head.consumed != 41; then return 11; fi
                  val Array<int> valid_head = arr[72,69,65,68,32,47,105,115,111,108,97,116,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,67,111,110,110,101,99,116,105,111,110,58,32,99,108,111,115,101,13,10,13,10];
                  val http_wire.Head valid_head_head = http_wire.parse_head(rt borrow valid_head);
                  if valid_head_head.status != 200; then return 2; fi
                  if valid_head_head.consumed != 63; then return 12; fi
                  val Array<int> smuggling = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,67,111,110,116,101,110,116,45,76,101,110,103,116,104,58,32,48,13,10,13,10];
                  val http_wire.Head smuggling_head = http_wire.parse_head(rt borrow smuggling);
                  if smuggling_head.status != 400; then return 3; fi
                  if smuggling_head.consumed != 60; then return 13; fi
                  val Array<int> dup_host = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,72,111,115,116,58,32,97,103,97,105,110,13,10,13,10];
                  val http_wire.Head dup_host_head = http_wire.parse_head(rt borrow dup_host);
                  if dup_host_head.status != 400; then return 4; fi
                  if dup_host_head.consumed != 54; then return 14; fi
                  val Array<int> bare_lf = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,10,10];
                  val http_wire.Head bare_lf_head = http_wire.parse_head(rt borrow bare_lf);
                  if bare_lf_head.status != 400; then return 5; fi
                  if bare_lf_head.consumed != 0; then return 15; fi
                  val Array<int> partial = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10];
                  val http_wire.Head partial_head = http_wire.parse_head(rt borrow partial);
                  if partial_head.status != 0; then return 6; fi
                  if partial_head.consumed != 0; then return 16; fi
                  val Array<int> pipelines = arr[71,69,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10,71,69,84,32,47,105,115,111,108,97,116,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,122,13,10,13,10];
                  val http_wire.Head pipelines_head = http_wire.parse_head(rt borrow pipelines);
                  if pipelines_head.status != 200; then return 7; fi
                  if pipelines_head.consumed != 41; then return 17; fi
                  val Array<int> unsupported = arr[80,79,83,84,32,47,115,104,97,114,101,100,32,72,84,84,80,47,49,46,49,13,10,72,111,115,116,58,32,108,111,99,97,108,104,111,115,116,13,10,13,10];
                  val http_wire.Head unsupported_head = http_wire.parse_head(rt borrow unsupported);
                  if unsupported_head.status != 405; then return 8; fi
                  if unsupported_head.consumed != 42; then return 18; fi
                  return 0;
                }
                """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status = LinkedProgramRunner.runWithExitStatus(guest, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, new ByteArrayOutputStream()).exitStatus();
            assertEquals(0, status);
            assertEquals(0, out.size());
        } finally {
            Files.deleteIfExists(guest);
        }
    }
}
