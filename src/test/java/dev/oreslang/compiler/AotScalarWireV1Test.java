package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Host/compiler-side JSON and MessagePack static decoder tests; not guest AOT. */
final class AotScalarWireV1Test {
    private static final String SOURCE = """
            pub define class WirePayload as
              pub int count: 0
              pub string title: "before"
              pub bool enabled: false
              int private_marker: 9
            end
            """;

    private static AotScalarWireV1.Schema schema() {
        return OresCompiler.compileAotScalarWireSchemaV1(
                SOURCE, "pkg@revision1", "type_of<WirePayload>()");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
    private static void append(ByteArrayOutputStream out, String string) {
        byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
        assertTrue(utf8.length <= 31);
        out.write(0xa0 | utf8.length);
        out.writeBytes(utf8);
    }
    private static byte[] pack(boolean reordered, long count, String title, boolean enabled) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        if (reordered) {
            append(out, "enabled"); out.write(enabled ? 0xc3 : 0xc2);
            append(out, "title"); append(out, title);
            append(out, "count"); out.write(0xd3);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(count >>> shift));
        } else {
            append(out, "count"); out.write(0xd3);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(count >>> shift));
            append(out, "title"); append(out, title);
            append(out, "enabled"); out.write(enabled ? 0xc3 : 0xc2);
        }
        return out.toByteArray();
    }
    private static void rejectsJson(String json) {
        assertThrows(IllegalArgumentException.class, () -> schema().decodeJson(json), json);
    }
    private static void rejectsPack(byte[] pack) {
        assertThrows(IllegalArgumentException.class, () -> schema().decodeMessagePack(pack));
    }

    @Test void jsonDecodesPublicScalarSlotsInDeclarationOrder() {
        var compiled = schema();
        assertEquals("__root__.WirePayload", compiled.qualifiedType());
        assertEquals(64, compiled.typeIdHex().length());
        assertEquals(List.of("count", "title", "enabled"),
                compiled.fields().stream().map(AotScalarWireV1.Field::name).toList());
        var result = compiled.decodeJson(
                "{\"enabled\":true,\"title\":\"hello\",\"count\":-42}");
        assertEquals(List.of(-42L, "hello", true), result.slots());
        assertEquals("hello", result.at(1));
        assertThrows(UnsupportedOperationException.class, () -> result.slots().add(1L));
        assertThrows(UnsupportedOperationException.class, () ->
                compiled.fields().add(new AotScalarWireV1.Field("fake", AotScalarWireV1.Kind.I64)));
    }

    @Test void messagePackAndJsonAreSemanticallyEquivalent() {
        var compiled = schema();
        var json = compiled.decodeJson("{\"title\":\"hi\",\"count\":42,\"enabled\":false}");
        assertEquals(json, compiled.decodeMessagePack(pack(false, 42, "hi", false)));
        assertEquals(json, compiled.decodeMessagePack(pack(true, 42, "hi", false)));
        assertEquals(json, compiled.decodeMessagePack(pack(true, 42, "hi", false).clone()));
    }

    @Test void privateFieldsAreNotDecodeAuthority() {
        rejectsJson("{\"count\":1,\"title\":\"x\",\"enabled\":true,\"private_marker\":9}");
        assertFalse(schema().fields().stream().anyMatch(f -> f.name().equals("private_marker")));
    }

    @Test void deniesUnknownDuplicateAndMissingJsonKeys() {
        for (String json : new String[]{
                "{\"count\":1,\"title\":\"x\"}",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true,\"extra\":1}",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true,\"count\":2}",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true,\"title\":\"again\"}",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true,}",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true} trailing",
                "{\"count\":1,\"title\":\"x\",\"enabled\":true}{\"a\":1}",
                "[]", "null", ""
        }) rejectsJson(json);
    }

    @Test void deniesWrongTypesNullFractionAndImplicitConversions() {
        for (String v : new String[]{"null","1.0","1e2","true","\"1\"","01",
                "-01","9223372036854775808","-9223372036854775809","0x10","+1"}) {
            rejectsJson("{\"count\":" + v + ",\"title\":\"x\",\"enabled\":true}");
        }
        rejectsJson("{\"count\":1,\"title\":23,\"enabled\":true}");
        rejectsJson("{\"count\":1,\"title\":\"x\",\"enabled\":1}");
        rejectsJson("{\"count\":1,\"title\":\"x\",\"enabled\":null}");
        rejectsJson("{\"count\":1,\"title\":\"x\",\"enabled\":\"true\"}");
    }

    @Test void signedI64ExtremesAndJsonStringEscapes() {
        var compiled = schema();
        var value = compiled.decodeJson(
                "{\"count\":-9223372036854775808,\"title\":\"x\\n\\\"/\\\\z\",\"enabled\":false}");
        assertEquals(Long.MIN_VALUE, value.at(0));
        assertEquals("x\n\"/\\z", value.at(1));
        assertEquals(Long.MAX_VALUE, compiled.decodeJson(
                "{\"count\":9223372036854775807,\"title\":\"x\",\"enabled\":true}").at(0));
    }

    @Test void rejectsJsonMalformedEscapesControlsAndSurrogates() {
        for (String v : new String[]{"\"\\q\"", "\"\\uD800\"", "\"\\uDC00\"",
                "\"\\uD800x\"", "\"unterminated", "\"bad\nraw\"", "\"\\u12FG\""}) {
            rejectsJson("{\"count\":1,\"title\":" + v + ",\"enabled\":true}");
        }
        var value = schema().decodeJson(
                "{\"count\":1,\"title\":\"\\uD83D\\uDE80\",\"enabled\":true}");
        assertEquals("🚀", value.at(1));
    }

    @Test void jsonAndMessagePackStringByteBudgetsAreBounded() {
        String huge = "x".repeat(4097);
        rejectsJson("{\"count\":1,\"title\":\"" + huge + "\",\"enabled\":true}");
        rejectsJson(" ".repeat(65537));
        rejectsPack(new byte[65537]);
        rejectsPack(bytes(0x83, 0xa5, 'c','o','u','n','t'));
    }

    @Test void messagePackDeniesDuplicateUnknownAndMissingKeys() {
        byte[] valid = pack(false, 7, "ok", true);
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        rejectsPack(trailing);
        rejectsPack(bytes(0x80));
        rejectsPack(bytes(0x81, 0xa1, 'a', 0x01));
        // Three keys, but two copies of count.
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x83);
        append(b,"count"); b.write(0x01);
        append(b,"title"); append(b,"x");
        append(b,"count"); b.write(0x02);
        rejectsPack(b.toByteArray());
    }

    @Test void messagePackDeniesNilArrayNestedMapsBinaryAndFloat() {
        for (int val : new int[]{0xc0,0x90,0x80,0xc4,0xca,0xcb,0xd4}) {
            byte[] valid = pack(false, 1, "x", true);
            // Count is encoded as signed 64-bit. Replace its marker with an
            // unsupported wire tag and rely on strict length/trailing checks.
            int marker = 1 + 1 + "count".length();
            valid[marker] = (byte) val;
            rejectsPack(valid);
        }
    }

    @Test void messagePackValidatesUTF8AndBooleanTags() {
        var valid = pack(false, 1, "x", true);
        valid[valid.length - 1] = (byte) 0xc0;
        rejectsPack(valid);
        // A str8 title with malformed continuation bytes is never coerced.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        append(out, "count"); out.write(0x01);
        append(out, "title"); out.write(0xd9); out.write(2);
        out.write(0xc3); out.write(0x28);
        append(out, "enabled"); out.write(0xc3);
        rejectsPack(out.toByteArray());
    }

    @Test void messagePackIntegerTagsUseSignedSemantics() {
        for (byte[] integer : new byte[][] {
                bytes(0x2a), bytes(0xff), bytes(0xd0,0x80),
                bytes(0xd1,0x80,0x00), bytes(0xd2,0xff,0xff,0xff,0xff),
                bytes(0xcc,0xff), bytes(0xcd,0xff,0xff), bytes(0xce,0xff,0xff,0xff,0xff)
        }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x83); append(out, "count"); out.writeBytes(integer);
            append(out, "title"); append(out, "x");
            append(out, "enabled"); out.write(0xc2);
            assertEquals(3, schema().decodeMessagePack(out.toByteArray()).slots().size());
        }
        assertEquals(Long.MIN_VALUE, schema().decodeMessagePack(pack(false, Long.MIN_VALUE, "x", true)).at(0));
    }

    @Test void rejectionsDoNotIntroduceRuntimeReflectionOrClassInstantiation() {
        assertEquals(0, AotScalarWireV1.Schema.class.getConstructors().length);
        rejectsJson("{\"__class\":\"java.lang.Runtime\",\"count\":1,\"title\":\"x\",\"enabled\":true}");
    }

    @Test void annotatedWireAliasesFailClosedUntilVersionedSchemaExists() {
        String withAlias = """
                pub define class Aliased as
                  @FromJson("other_key")
                  pub int count: 1
                end
                """;
        Ast.Program parsed = Parser.parse(withAlias);
        assertThrows(IllegalArgumentException.class, () ->
                AotReflectionSourceRootsV1.lowerChecked(
                        parsed, "pkg@revision1", List.of("__root__.Aliased")));
    }

    @Test void unsupportedDecimalAndComplexFieldShapesAreNotPromoted() {
        String source = """
                pub define class BadScalar as
                  pub double value: 4.0
                end
                """;
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileAotScalarWireSchemaV1(
                        source, "pkg@revision1", "type_of<BadScalar>()"));
    }

    @Test void strictJsonUtf8RejectsInvalidSequences() {
        var compiled = schema();
        byte[] valid = "{\"count\":5,\"title\":\"rocket\",\"enabled\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(5L, "rocket", true),
                compiled.decodeJsonUtf8(valid).slots());
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonUtf8(bytes(0xc3, 0x28)));
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonUtf8(bytes(0xed, 0xa0, 0x80)));
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonUtf8(null));
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonUtf8(new byte[65_537]));
    }

    @Test void messagePackDoesNotCoerceUnsigned64OrFloatingPoint() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        append(out, "count"); out.write(0xcf); // u64, refused even when value could fit
        out.writeBytes(bytes(0,0,0,0,0,0,0,1));
        append(out, "title"); append(out, "x");
        append(out, "enabled"); out.write(0xc3);
        rejectsPack(out.toByteArray());
    }

    @Test void jsonExactFieldNameMatchingUsesDecodedEscapes() {
        var result = schema().decodeJson(
                "{\"co\\u0075nt\":2,\"title\":\"ok\",\"enabled\":true}");
        assertEquals(2L, result.at(0));
        rejectsJson("{\"count\":2,\"co\\u0075nt\":3,\"title\":\"ok\",\"enabled\":true}");
    }

    @Test void truncatedMessagePackIntegerAndStringReject() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        append(out, "count"); out.write(0xd3); out.write(0);
        rejectsPack(out.toByteArray());
        out.reset();
        out.write(0x83);
        append(out, "count"); out.write(1);
        append(out, "title"); out.write(0xda); out.write(0x10); out.write(0x00);
        rejectsPack(out.toByteArray());
    }

    @Test void decoderHasNoPolymorphicGuestConstructionAuthority() {
        assertEquals(AotScalarWireV1.Kind.I64, schema().fields().getFirst().kind());
        assertEquals(AotScalarWireV1.Kind.STRING, schema().fields().get(1).kind());
        assertEquals(AotScalarWireV1.Kind.BOOL, schema().fields().get(2).kind());
        rejectsJson("{\"count\":1,\"title\":{},\"enabled\":true}");
        rejectsJson("{\"count\":1,\"title\":[],\"enabled\":true}");
    }

    @Test void committedAttoSchemaIsUsedForBothWireFormats() throws Exception {
        String atto;
        try (var in = AotScalarWireV1Test.class.getResourceAsStream(
                "/aot-reflection/scalar_wire_json_msgpack.atto")) {
            assertNotNull(in, "missing .atto scalar-wire fixture");
            atto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var checked = OresCompiler.compileAotScalarWireSchemaV1(
                atto, "pkg@revision1", "type_of<WirePayload>()");
        assertEquals(schema().typeIdHex(), checked.typeIdHex());
        assertEquals(List.of(1L, "ok", true), checked.decodeJson(
                "{\"count\":1,\"title\":\"ok\",\"enabled\":true}").slots());
        assertEquals(List.of(1L, "ok", true), checked.decodeMessagePack(
                pack(false, 1, "ok", true)).slots());
    }

    @Test void rejectsNonAsciiUnicodeEscapeDigits() {
        rejectsJson("{\"count\":1,\"title\":\"\\u１２ＡＦ\",\"enabled\":true}");
    }

    @Test void wireSchemaFingerprintIsDistinctFromNominalTypeIdentity() {
        var first = schema();
        var identical = schema();
        assertEquals(64, first.schemaSha256Hex().length());
        assertEquals(first.schemaSha256Hex(), identical.schemaSha256Hex());
        String updated = SOURCE.replace(
                "  pub bool enabled: false",
                "  pub bool enabled: false\n  pub int revision: 1");
        var changed = OresCompiler.compileAotScalarWireSchemaV1(
                updated, "pkg@revision1", "type_of<WirePayload>()");
        // V1 nominal type IDs bind the package/name, not the field layout.
        assertEquals(first.typeIdHex(), changed.typeIdHex());
        assertNotEquals(first.schemaSha256Hex(), changed.schemaSha256Hex());
        assertThrows(IllegalArgumentException.class,
                () -> changed.requireSchemaFingerprint(first.schemaSha256Hex()));
    }

    @Test void fingerprintIsCheckedBeforeDecodingAnyUntrustedPayload() {
        var compiled = schema();
        var fingerprint = compiled.schemaSha256Hex();
        var json = "{\"count\":3,\"title\":\"hi\",\"enabled\":true}";
        assertEquals(List.of(3L, "hi", true),
                compiled.decodeJsonWithFingerprint(json, fingerprint).slots());
        assertEquals(List.of(3L, "hi", true),
                compiled.decodeMessagePackWithFingerprint(
                        pack(false, 3, "hi", true), fingerprint).slots());
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonWithFingerprint(json, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeMessagePackWithFingerprint(
                        pack(false, 3, "hi", true), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                compiled.decodeJsonWithFingerprint("malformed", null));
    }

    @Test void deterministicMalformedPayloadFuzzDoesNotCrashDecoders() {
        var compiled = schema();
        var random = new java.util.Random(20261103L);
        for (int i = 0; i < 6_000; i++) {
            byte[] message = new byte[random.nextInt(128)];
            random.nextBytes(message);
            if (message.length > 0 && i % 3 == 0) message[0] = (byte) 0x83;
            try {
                compiled.decodeMessagePack(message);
            } catch (IllegalArgumentException expected) {
                // Bounded malformed input must fail as a wire error, never OOB/OOM/CCE.
            }
            StringBuilder json = new StringBuilder();
            for (int j = 0, n = random.nextInt(100); j < n; j++) {
                json.append((char) (32 + random.nextInt(95)));
            }
            try {
                compiled.decodeJson(json.toString());
            } catch (IllegalArgumentException expected) {
                // Malformed JSON must not become an unchecked runtime crash.
            }
        }
    }

    @Test void messagePackAdmitsBoundedStr8AndMap16Forms() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xde); out.write(0); out.write(3); // map16, three exact fields
        append(out, "count"); out.write(0x01);
        append(out, "title"); out.write(0xd9); out.write(80);
        out.writeBytes("x".repeat(80).getBytes(StandardCharsets.UTF_8));
        append(out, "enabled"); out.write(0xc3);
        assertEquals(List.of(1L, "x".repeat(80), true),
                schema().decodeMessagePack(out.toByteArray()).slots());
    }

    @Test void messagePackAdmitsBoundedStr16AndRejectsOversizedLength() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83);
        append(out, "count"); out.write(0x01);
        append(out, "title"); out.write(0xda); out.write(0x01); out.write(0x2c);
        out.writeBytes("z".repeat(300).getBytes(StandardCharsets.UTF_8));
        append(out, "enabled"); out.write(0xc2);
        assertEquals("z".repeat(300), schema().decodeMessagePack(out.toByteArray()).at(1));
        out.reset();
        out.write(0x83); append(out, "count"); out.write(0x01);
        append(out, "title"); out.write(0xda); out.write(0x10); out.write(0x01);
        out.writeBytes("z".repeat(4097).getBytes(StandardCharsets.UTF_8));
        append(out, "enabled"); out.write(0xc2);
        rejectsPack(out.toByteArray());
    }

    @Test void utf8BudgetsCountBytesRatherThanUnicodeCodePoints() {
        String rocket = "🚀".repeat(1024);
        assertEquals(4096, rocket.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(rocket, schema().decodeJson(
                "{\"count\":1,\"title\":\"" + rocket + "\",\"enabled\":true}").at(1));
        rejectsJson("{\"count\":1,\"title\":\"" + rocket + "🚀\",\"enabled\":true}");
    }
}
