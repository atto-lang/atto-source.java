package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First real AttoLang-owned scalar SET/GET/DEL guest. This is not a
 * persistent Redis server, actor scheduler test or cross-backend benchmark.
 */
final class AttoRedisScalarMapMutationGuestTest {
    private static String guest() throws Exception {
        try (InputStream in = AttoRedisScalarMapMutationGuestTest.class.getResourceAsStream(
                "/atto/redis-clone/redis_kv_smoke.atto")) {
            assertNotNull(in, "missing canonical Atto source");
            byte[] data = in.readAllBytes();
            assertTrue(data.length > 0 && data.length < 65536);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    @Test
    void actualAttoSetGetAndDeleteExecutes42() throws Exception {
        String src = guest();
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(src));
        Source source = Source.newBuilder(
                OresLanguage.ID, src, "redis_kv_smoke.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value result = assertDoesNotThrow(() -> ctx.eval(source));
            assertTrue(result.fitsInInt());
            assertEquals(42, result.asInt());
        }
    }

    @Test
    void mutableMapRejectsWrongKeyAndValueTypesBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(
                        "pub fnc main(): void {"
                        + "let Map<string,int> db = ~map<string,int>(); "
                        + "db.set(4, 12); return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(
                        "pub fnc main(): void {"
                        + "let Map<string,int> db = ~map<string,int>(); "
                        + "db.set(\"k\", \"wrong\"); return; }"));
    }
    @Test
    void actualAttoActorOwnedMapRunsSetGetDelete() throws Exception {
        String source;
        try (InputStream input = AttoRedisScalarMapMutationGuestTest.class
                .getResourceAsStream("/atto/redis-clone/redis_actor_guest.atto")) {
            assertNotNull(input);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        Source artifact = Source.newBuilder(
                OresLanguage.ID, source, "redis_actor_guest.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            assertDoesNotThrow(() -> context.eval(artifact));
        }
        assertEquals("nil|set|get|delete|nil", stdout.toString(StandardCharsets.UTF_8));
    }


    @Test
    void atomicOptionalLookupPreservesPresentAbsentAcrossDelete() throws Exception {
        String src = """
                pub fnc main(): int {
                  let Map<string,int> db = ~map<string,int>();
                  if db.get_optional("missing").is_some() then return 1; fi
                  db.set("key", 41);
                  val found = db.get_optional("key");
                  if found.is_none() then return 2; fi
                  db.delete("key");
                  if db.get_optional("key").is_some() then return 3; fi
                  return found.unwrap() + 1;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(src));
        Source artifact = Source.newBuilder(OresLanguage.ID, src, "redis-optional-get.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42, context.eval(artifact).asInt());
        }
    }
}
