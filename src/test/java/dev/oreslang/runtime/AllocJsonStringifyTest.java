package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AllocJsonStringifyTest {
    @Test
    void typeSurfaceRequiresCanonicalJsonObjectMap() {
        assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc encode(): String {
                          val Map<String, JsonValue> object =
                              new Map<String, JsonValue>();
                          object["name"] = "Ada";
                          const String encoded = Json.stringify(object);
                          object["age"] = 37;
                          return encoded;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc bad(): String {
                          val Map<String, String> object =
                              new Map<String, String>();
                          return Json.stringify(object);
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc bad(): String {
                          val Map<String, String> object =
                              new Map<String, String>();
                          return Json.stringify(object);
                        }
                        """));
    }

    @Test
    void linkedStringifyIsDeterministicAndDoesNotConsumeObject() throws Exception {
        Path entry = Files.createTempFile("ores-alloc-json-stringify-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Files.writeString(entry, """
                    import module Json from "alloc/json";

                    pub routine main(): void {
                      val Map<String, JsonValue> object =
                          new Map<String, JsonValue>();
                      object["name"] = "Ada \\\"Lovelace\\\"";
                      object["age"] = 37;
                      object["active"] = true;
                      object["score"] = 1.25;
                      object.put("age", 38);

                      stdio.println(Json.stringify(object));
                      object["after"] = "still-owned";
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
                    "{\"name\":\"Ada \\\"Lovelace\\\"\",\"age\":38,\"active\":true,\"score\":1.25}\n5",
                    out.toString(StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .trim(),
                    () -> "unexpected alloc/json stderr="
                            + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
