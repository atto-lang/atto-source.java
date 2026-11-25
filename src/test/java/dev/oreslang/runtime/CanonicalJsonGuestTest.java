package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CanonicalJsonGuestTest {
    @TempDir Path root;

    private String run(String program) throws Exception {
        Path entry = root.resolve("main.atto");
        Files.writeString(entry, program);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), out, new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    @Test
    void dynamicReadAndExactRoundTrip() throws Exception {
        assertEquals("true\ntrue\nHélium 😀\n{\"name\":\"Hélium 😀\",\"number\":999}\n7", run("""
                import class Json from "alloc/json";
                import class JsonValue from "alloc/json";
                pub fnc main(): void {
                  val JsonValue document = Json.parse("{\\\"Xx\\\":{\\\"name\\\":\\\"Hélium 😀\\\",\\\"number\\\":999},\\\"nullish\\\":null,\\\"shells\\\":[7,11]}");
                  val absent = document.get("absent");
                  stdio.println(absent.is_none());
                  val JsonValue nullish = document.get("nullish").unwrap();
                  stdio.println(nullish.is_null());
                  val JsonValue element = document.get("Xx").unwrap();
                  val JsonValue name = element.get("name").unwrap();
                  stdio.println(name.as_string().unwrap());
                  stdio.println(Json.stringify(rt borrow element));
                  val JsonValue shells = document.get("shells").unwrap();
                  val JsonValue first = shells.at(0).unwrap();
                  stdio.println(first.as_int().unwrap());
                  return;
                }
                """));
    }

    @Test
    void canonicalOpaqueConstructionIsRejected() throws Exception {
        Path entry = root.resolve("forge.atto");
        Files.writeString(entry, """
                import class JsonValue from "alloc/json";
                pub fnc main(): void {
                  val forged = new JsonValue();
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(entry));
    }

    @Test
    void userClassNamedJsonIsNotRewrittenToCanonicalIntrinsic() throws Exception {
        assertEquals("local", run("""
                define class Json as
                  pub static fnc parse(string source): string {
                    return "local";
                  }
                end
                pub fnc main(): void {
                  stdio.println(Json.parse("not JSON"));
                  return;
                }
                """));
    }
}
