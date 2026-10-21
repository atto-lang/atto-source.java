package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AllocJsonMapSourceSurfaceTest {
    @Test
    void linkedAllocJsonImportBuildsJsonDomainMapAndLiftsScalars() throws Exception {
        Path entry = Files.createTempFile("ores-alloc-json-map-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Files.writeString(entry, """
                    import module Json from "alloc/json";

                    pub routine main(): void {
                      val Map<String, JsonValue> object =
                          new Map<String, JsonValue>();
                      object["name"] = "Ada";
                      object["age"] = 37;
                      object["active"] = true;
                      object["score"] = 1.25;
                      object.put("age", 38);
                      stdio.println(object.size);
                      return;
                    }
                    """);

            var build = LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err);

            assertTrue(build.units().containsKey("@alloc/json.ores"));
            assertEquals(
                    "4",
                    out.toString(StandardCharsets.UTF_8).trim(),
                    () -> "unexpected alloc/json execution stderr="
                            + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
