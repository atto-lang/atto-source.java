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

final class NativeHttpWireStdlibTest {
    @Test
    void parserIsPureOreslangAndContainsNoHostIo() throws Exception {
        String source = CoreLibrarySources.source("std/http_wire");
        MixedSourceUnit unit = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/http_wire"),
                CoreLibrarySources.fileName("std/http_wire"), source);
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        for (String forbidden : List.of("java:", "http.listen(", "network.", "socket.", "stdio.", "await ")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test
    void guestParsesOnlyStrictBodylessHttp11Heads() throws Exception {
        String guest = """
            import module http_wire from "std/http_wire";
            pub routine main(): void {
              val valid = http_wire.parse_head("GET /health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: keep-alive\\r\\n\\r\\n");
              stdio.println(valid.is_ok());
              val head = valid.unwrap();
              stdio.println(head.method);
              stdio.println(head.target);
              stdio.println(head.host);
              stdio.println(head.keep_alive);
              val close = http_wire.parse_head("HEAD /ping HTTP/1.1\\r\\nHost: localhost:8080\\r\\nConnection: close\\r\\n\\r\\n");
              stdio.println(close.is_ok());
              stdio.println(close.unwrap().keep_alive);
              stdio.println(http_wire.parse_head("POST /upload HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.0\\r\\nHost: x\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.1\\r\\nHost: x\\r\\nContent-Length: 1\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.1\\r\\nHost: x\\r\\nHost: y\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET //evil HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.1\\r\\nHost: x\\nX: y\\r\\n\\r\\n").is_err());
              stdio.println(http_wire.parse_head("GET / HTTP/1.1\\r\\nHost: x\\r\\n").is_err());
              return;
            }
            """;
        Path entry = Files.createTempFile("ores-http-wire-", ".ores");
        try {
            Files.writeString(entry, guest);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), output, err);
            assertEquals(String.join("\n", List.of(
                    "true","GET","/health","localhost","true",
                    "true","false","true","true","true","true",
                    "true","true","true","true")), output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
