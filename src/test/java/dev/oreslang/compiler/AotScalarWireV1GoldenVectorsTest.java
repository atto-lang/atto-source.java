package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production-source admission of the exact shared wire vector corpus.
 * These are host JUnit tests, NOT compiler-backed AttoLang guest or AOT runs.
 */
final class AotScalarWireV1GoldenVectorsTest {
    private static String resource(String path) throws Exception {
        try (var stream = AotScalarWireV1GoldenVectorsTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing committed golden resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static AotScalarWireV1.Schema checkedSchema() throws Exception {
        String source = resource("/aot-reflection/matrix_wire_json_msgpack.atto");
        return OresCompiler.compileAotScalarWireSchemaV1(
                source, "matrix-fixture@1", "type_of<MatrixPayload>()");
    }

    private static String corpus() throws Exception {
        return resource("/aot-reflection/aot-wire-scalar-v1-vectors.txt");
    }

    private static AotScalarWireV1VectorVerifier.Summary verify(
            AotScalarWireV1.Schema schema, String text) {
        return AotScalarWireV1VectorVerifier.verify(
                schema, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void productionParserCheckedSchemaDecodesAllPortableVectors() throws Exception {
        var schema = checkedSchema();
        assertEquals("__root__.MatrixPayload", schema.qualifiedType());
        assertEquals(List.of("number", "text", "active"),
                schema.fields().stream().map(AotScalarWireV1.Field::name).toList());
        assertEquals(64, schema.typeIdHex().length());
        assertEquals(64, schema.schemaSha256Hex().length());
        var result = verify(schema, corpus());
        assertEquals(8, result.positivePairs());
        assertEquals(15, result.rejectedVectors());
        assertEquals(31, result.codecAssertions());
    }

    @Test void changedBooleanExpectationRejectsGoldenData() throws Exception {
        var original = corpus();
        assertTrue(original.contains("P|zero|0|false|"));
        String altered = original.replace("P|zero|0|false|", "P|zero|0|true|");
        assertThrows(AssertionError.class, () -> verify(checkedSchema(), altered));
    }

    @Test void changedSignedIntegerExpectationRejectsGoldenData() throws Exception {
        String original = corpus();
        assertTrue(original.contains("P|one|1|true|"));
        String altered = original.replace("P|one|1|true|", "P|one|2|true|");
        assertThrows(AssertionError.class, () -> verify(checkedSchema(), altered));
    }

    @Test void duplicateFixtureIdentifiersRejectBeforePromotion() throws Exception {
        String original = corpus();
        assertTrue(original.contains("N|json_missing|json|"));
        String altered = original.replace("N|json_missing|json|", "N|json_duplicate|json|");
        assertThrows(IllegalArgumentException.class, () -> verify(checkedSchema(), altered));
    }

    @Test void missingNegativeVectorRejectsIncompleteCorpus() throws Exception {
        String original = corpus();
        String altered = original.lines()
                .filter(line -> !line.startsWith("N|msgpack_bad_utf8|"))
                .reduce("", (a, b) -> a + b + "\n");
        assertThrows(AssertionError.class, () -> verify(checkedSchema(), altered));
    }

    @Test void missingPositiveVectorRejectsIncompleteCorpus() throws Exception {
        String original = corpus();
        String altered = original.lines()
                .filter(line -> !line.startsWith("P|utf8_rocket|"))
                .reduce("", (a, b) -> a + b + "\n");
        assertThrows(IllegalArgumentException.class, () -> verify(checkedSchema(), altered));
    }

    @Test void malformedBase64IsNeverReplacementDecoded() throws Exception {
        String original = corpus();
        String altered = original.replace("N|json_missing|json|",
                "N|json_missing|json|???");
        assertThrows(IllegalArgumentException.class, () -> verify(checkedSchema(), altered));
    }

    @Test void malformedHexAndUnknownWireFormatReject() throws Exception {
        String original = corpus();
        assertThrows(IllegalArgumentException.class, () ->
                verify(checkedSchema(), original.replace(
                        "N|msgpack_nil|messagepack|", "N|msgpack_nil|messagepack|XYZ")));
        assertThrows(IllegalArgumentException.class, () ->
                verify(checkedSchema(), original.replace(
                        "N|msgpack_nil|messagepack|", "N|msgpack_nil|binary|")));
    }

    @Test void unreviewedCorpusVersionRejects() throws Exception {
        String original = corpus();
        assertThrows(IllegalArgumentException.class, () ->
                verify(checkedSchema(), original.replace(
                        "attolang.aot-wire-scalar-fixtures/1",
                        "attolang.aot-wire-scalar-fixtures/2")));
    }

    @Test void oversizedCorpusFailsBeforeParsingAnyWirePayload() throws Exception {
        byte[] tooLarge = new byte[65_537];
        java.util.Arrays.fill(tooLarge, (byte) 'a');
        assertThrows(IllegalArgumentException.class, () ->
                AotScalarWireV1VectorVerifier.verify(
                        checkedSchema(), new ByteArrayInputStream(tooLarge)));
    }

    @Test void unknownRowTypeRejects() throws Exception {
        String original = corpus();
        String altered = original.replace("P|zero|", "X|zero|");
        assertThrows(IllegalArgumentException.class, () -> verify(checkedSchema(), altered));
    }

    @Test void invalidI64BoundsRejectBeforeDecoding() throws Exception {
        String original = corpus();
        String altered = original.replace("P|one|1|true|",
                "P|one|9223372036854775808|true|");
        assertThrows(IllegalArgumentException.class, () -> verify(checkedSchema(), altered));
    }
}
