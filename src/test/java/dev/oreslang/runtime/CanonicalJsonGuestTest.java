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
    void actorCanUseExplicitlyLinkedCanonicalJsonWithoutAmbientSiblingAuthority() throws Exception {
        assertEquals("42", run("""
                import class Json from "alloc/json";
                import class JsonValue from "alloc/json";
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val JsonValue document = Json.parse(mail.value);
                    val JsonValue number = document.get("number").unwrap();
                    stdio.println(number.as_int().unwrap());
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("{\\\"number\\\":42}");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void actorCannotTreatLocalJsonLookalikeAsLinkedClass() throws Exception {
        Path entry = root.resolve("ambient.atto");
        Files.writeString(entry, """
                define class Json as
                  pub static fnc parse(string source): string { return source; }
                end
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    stdio.println(Json.parse(mail.value));
                    return;
                  }
                end
                pub fnc main(): void { return; }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("actor 'Worker'"), failure.getMessage());
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
