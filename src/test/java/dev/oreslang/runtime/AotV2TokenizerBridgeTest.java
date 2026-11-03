package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the package-private V1 tokenizer bridge reused by
 * V2: V1's public typed parse(...) API remains untouched.
 */
final class AotV2TokenizerBridgeTest {
    private static Object parse(String text) {
        return ActorArtifactManifestJsonV1.parseStrictJsonValue(text.getBytes(StandardCharsets.UTF_8));
    }
    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class, () -> parse(source), source);
    }

    @Test void preservesExactDecimalAndExponentValues() {
        @SuppressWarnings("unchecked")
        Map<String,Object> doc=(Map<String,Object>)parse(
                "{\"size_bytes\":1.28e2,\"precise\":128.0000000000000001}");
        assertEquals(new BigDecimal("1.28e2"),doc.get("size_bytes"));
        assertEquals(new BigDecimal("128.0000000000000001"),doc.get("precise"));
        assertNotEquals(new BigDecimal("128"),doc.get("precise"));
    }
    @Test void decodedKeyCollisionAndPrototypalFieldsCannotDisappear() {
        rejects("{\"a\":1,\"\\u0061\":2}");
        rejects("{\"a\":0,\"a\":1}");
        @SuppressWarnings("unchecked")
        Map<String,Object> obj=(Map<String,Object>)parse("{\"__proto__\":\"danger\",\"constructor\":1}");
        assertEquals("danger",obj.get("__proto__"));
        assertEquals(new BigDecimal("1"),obj.get("constructor"));
        assertEquals(2,obj.size());
    }
    @Test void rejectsMalformedUtf8AndOversizedOrDeepInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parseStrictJsonValue(new byte[]{'{','\"',(byte)0xc3,0x28,'\"','}'}));
        rejects("[ ".repeat(65)+"0"+"]".repeat(65));
        assertThrows(IllegalArgumentException.class,
                () -> ActorArtifactManifestJsonV1.parseStrictJsonValue(new byte[65537]));
        rejects("{\"x\":\"\\uD800\"}");
        rejects("{\"x\":1e9999999999}");
    }
    @Test void returnedObjectsCannotBeMutatedOrAffectedByCallerInputMutation() {
        byte[] raw="{\"safe\":128}".getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String,Object> doc=(Map<String,Object>)ActorArtifactManifestJsonV1.parseStrictJsonValue(raw);
        java.util.Arrays.fill(raw,(byte)'x');
        assertEquals(new BigDecimal("128"),doc.get("safe"));
        assertThrows(UnsupportedOperationException.class, () -> doc.put("safe",BigDecimal.ONE));
    }
}
