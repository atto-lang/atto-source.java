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
}
