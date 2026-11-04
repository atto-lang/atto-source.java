package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Matrix/differential tests for the exact, compiler-generated scalar wire
 * schema. Host verification ONLY; no AttoLang guest execution or AOT claim.
 * Every encoded payload is deterministic and all mutation seeds are fixed.
 */
final class AotScalarWireV1MatrixTest {
    private static final String SOURCE = """
            pub define class MatrixPayload as
              pub int number: 0
              pub string text: "initial"
              pub bool active: false
              int secret_marker: 9
            end
            """;

    private static AotScalarWireV1.Schema schema() {
        return OresCompiler.compileAotScalarWireSchemaV1(
                SOURCE, "matrix-fixture@1", "type_of<MatrixPayload>()");
    }
    private static final int[][] ORDERS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
            {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };
    private static void key(ByteArrayOutputStream out, String name) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        assertTrue(utf8.length <= 31);
        out.write(0xa0 | utf8.length);
        out.writeBytes(utf8);
    }
    private static void text(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) out.write(0xa0 | bytes.length);
        else if (bytes.length <= 255) {
            out.write(0xd9); out.write(bytes.length);
        } else {
            out.write(0xda); out.write(bytes.length >>> 8); out.write(bytes.length);
        }
        out.writeBytes(bytes);
    }
    private static void signed(ByteArrayOutputStream out, long value) {
        out.write(0xd3);
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(value >>> shift));
    }
    private static byte[] msgpack(long count, String value, boolean enabled,
                                  int[] order, boolean map16) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (map16) { out.write(0xde); out.write(0); out.write(3); }
        else out.write(0x83);
        for (int field : order) {
            switch (field) {
                case 0 -> { key(out, "number"); signed(out, count); }
                case 1 -> { key(out, "text"); text(out, value); }
                case 2 -> { key(out, "active"); out.write(enabled ? 0xc3 : 0xc2); }
                default -> throw new AssertionError("invalid test field index");
            }
        }
        return out.toByteArray();
    }
    private static byte[] msgpack(long count, String value, boolean enabled) {
        return msgpack(count, value, enabled, ORDERS[0], false);
    }
    private static String json(long value, String text, boolean active) {
        return "{\"number\":" + value + ",\"text\":\"" + text
                + "\",\"active\":" + active + "}";
    }
    private static void rejectsJson(String input) {
        assertThrows(IllegalArgumentException.class, () -> schema().decodeJson(input));
    }
    private static void rejectsPack(byte[] input) {
        assertThrows(IllegalArgumentException.class, () -> schema().decodeMessagePack(input));
    }

    @Test void allSixJsonKeyPermutationsHaveIdenticalTypedSlots() {
        String[] entries = {"\"number\":-42", "\"text\":\"same\"", "\"active\":true"};
        for (int[] order : ORDERS) {
            String input = "{" + entries[order[0]] + "," + entries[order[1]] +
                    "," + entries[order[2]] + "}";
            assertEquals(List.of(-42L, "same", true), schema().decodeJson(input).slots());
        }
    }

    @Test void allSixMessagePackKeyPermutationsHaveIdenticalTypedSlots() {
        var fixed = schema();
        for (int[] order : ORDERS) {
            assertEquals(List.of(-42L, "same", true),
                    fixed.decodeMessagePack(msgpack(-42, "same", true, order, false)).slots());
            assertEquals(List.of(-42L, "same", true),
                    fixed.decodeMessagePack(msgpack(-42, "same", true, order, true)).slots());
        }
    }

    @Test void integerBoundaryMatrixAgreesAcrossFormats() {
        long[] values = {
                Long.MIN_VALUE, Long.MIN_VALUE + 1, Integer.MIN_VALUE - 1L,
                Integer.MIN_VALUE, -65536, -32769, -32768, -129, -128, -33, -32,
                -1, 0, 1, 31, 32, 127, 128, 255, 256,
                32767, 32768, 65535, 65536,
                Integer.MAX_VALUE, Integer.MAX_VALUE + 1L, Long.MAX_VALUE - 1,
                Long.MAX_VALUE
        };
        var codec = schema();
        for (long value : values) {
            var expected = List.of(value, "wire", false);
            assertEquals(expected, codec.decodeJson(json(value, "wire", false)).slots(),
                    "JSON " + value);
            assertEquals(expected, codec.decodeMessagePack(msgpack(value, "wire", false)).slots(),
                    "MessagePack " + value);
        }
    }

    @Test void seeded2048SignedI64DifferentialSamplesMatch() {
        var codec = schema();
        Random random = new Random(20261103L);
        for (int i = 0; i < 2048; i++) {
            long n = random.nextLong();
            boolean flag = (i & 1) != 0;
            var expected = List.of(n, "x", flag);
            assertEquals(expected, codec.decodeJson(json(n, "x", flag)).slots(), "json seed " + i);
            assertEquals(expected, codec.decodeMessagePack(msgpack(n, "x", flag)).slots(),
                    "msgpack seed " + i);
        }
    }

    @Test void stringLengthTransitionsAcrossFixstrStr8AndStr16MatchJson() {
        var codec = schema();
        for (int len : new int[]{0, 1, 30, 31, 32, 33, 127, 128,
                254, 255, 256, 257, 1024, 4096}) {
            String value = "x".repeat(len);
            assertEquals(List.of(7L, value, true),
                    codec.decodeMessagePack(msgpack(7L, value, true)).slots(), "msgpack length " + len);
            assertEquals(List.of(7L, value, true),
                    codec.decodeJson(json(7L, value, true)).slots(), "json length " + len);
        }
    }

    @Test void fourByteUtf8CharactersCountTowardWireBudget() {
        var codec = schema();
        String atLimit = "🚀".repeat(1024);
        assertEquals(4096, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(atLimit, codec.decodeJson(json(1, atLimit, false)).at(1));
        assertEquals(atLimit, codec.decodeMessagePack(msgpack(1, atLimit, false)).at(1));
        String pastLimit = atLimit + "🚀";
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeJson(json(1, pastLimit, false)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeMessagePack(msgpack(1, pastLimit, false)));
    }

    @Test void messagePackCanonicalIntegerTagFamiliesPreserveExactValues() {
        byte[][] variants = {
                {(byte)0x00}, {(byte)0x7f}, {(byte)0xff}, {(byte)0xe0},
                {(byte)0xd0, (byte)0x80}, {(byte)0xd0, (byte)0x7f},
                {(byte)0xd1, (byte)0x80, (byte)0x00},
                {(byte)0xd1, (byte)0x7f, (byte)0xff},
                {(byte)0xd2, (byte)0x80, 0, 0, 0},
                {(byte)0xd2, (byte)0x7f, (byte)0xff, (byte)0xff, (byte)0xff},
                {(byte)0xcc, (byte)0xff},
                {(byte)0xcd, (byte)0xff, (byte)0xff},
                {(byte)0xce, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff}
        };
        long[] values = {0, 127, -1, -32, -128, 127,
                -32768, 32767, Integer.MIN_VALUE, Integer.MAX_VALUE,
                255, 65535, 4294967295L};
        assertEquals(variants.length, values.length);
        var codec = schema();
        for (int i = 0; i < variants.length; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x83);
            key(out, "number"); out.writeBytes(variants[i]);
            key(out, "text"); text(out, "x");
            key(out, "active"); out.write(0xc3);
            assertEquals(values[i], codec.decodeMessagePack(out.toByteArray()).at(0),
                    "MessagePack integer family " + i);
        }
    }

    @Test void rootMessagePackTagsMustBePreciselyThreeEntryMap() {
        var codec = schema();
        byte[] original = msgpack(5, "ok", true);
        for (int tag = 0; tag < 256; tag++) {
            byte[] candidate = original.clone();
            candidate[0] = (byte) tag;
            if (tag == 0x83) {
                assertEquals(5L, codec.decodeMessagePack(candidate).at(0));
            } else {
                assertThrows(IllegalArgumentException.class, () ->
                        codec.decodeMessagePack(candidate), "root tag " + tag);
            }
        }
    }

    @Test void map16CountMustMatchSchemaAndNeverExceedQuota() {
        var codec = schema();
        byte[] base = msgpack(5, "ok", true, ORDERS[0], true);
        assertEquals(5L, codec.decodeMessagePack(base).at(0));
        for (int declared : new int[]{0, 1, 2, 4, 15, 255, 256, 257, 65535}) {
            byte[] candidate = base.clone();
            candidate[1] = (byte) (declared >>> 8);
            candidate[2] = (byte) declared;
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeMessagePack(candidate), "map16 count " + declared);
        }
    }

    @Test void everyProperMessagePackPrefixRejectsAsTruncated() {
        var codec = schema();
        byte[] valid = msgpack(Long.MIN_VALUE, "ok", true);
        for (int size = 0; size < valid.length; size++) {
            byte[] prefix = Arrays.copyOf(valid, size);
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeMessagePack(prefix), "prefix length " + size);
        }
    }

    @Test void everyProperJsonPrefixRejectsAsIncomplete() {
        var codec = schema();
        String valid = json(Long.MAX_VALUE, "ok", true);
        for (int size = 0; size < valid.length(); size++) {
            String prefix = valid.substring(0, size);
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeJson(prefix), "prefix length " + size);
        }
    }

    @Test void allSingleByteTrailingMessagePackGarbageRejects() {
        var codec = schema();
        byte[] valid = msgpack(5, "ok", true);
        for (int trailing = 0; trailing < 256; trailing++) {
            byte[] bytes = Arrays.copyOf(valid, valid.length + 1);
            bytes[bytes.length - 1] = (byte) trailing;
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeMessagePack(bytes), "extra byte " + trailing);
        }
    }

    @Test void jsonWhitespaceGrammarDoesNotAcceptUnicodeWhitespaceOrBom() {
        var codec = schema();
        String spaced = "\n\t  { \r \"number\" : 1 , \"text\" : \"ok\" , \"active\" : true }\t\n";
        assertEquals(List.of(1L, "ok", true), codec.decodeJson(spaced).slots());
        for (String invalid : new String[]{
                String.valueOf((char) 0xFEFF) + json(1, "ok", true),
                String.valueOf((char) 0x00A0) + json(1, "ok", true),
                json(1, "ok", true) + (char) 0x2003,
                json(1, "ok", true) + (char) 0x000B
        }) {
            assertThrows(IllegalArgumentException.class, () -> codec.decodeJson(invalid));
        }
    }

    @Test void jsonFieldNamesAreExactCaseSensitiveAndEscapeNormalized() {
        var codec = schema();
        assertEquals(5L, codec.decodeJson(
                "{\"num\\u0062er\":5,\"text\":\"x\",\"active\":true}").at(0));
        for (String bad : new String[]{
                "{\"Number\":5,\"text\":\"x\",\"active\":true}",
                "{\"number\":5,\"TEXT\":\"x\",\"active\":true}",
                "{\"number\":5,\"text\":\"x\",\"Active\":true}",
                "{\"number\":5,\"num\\u0062er\":6,\"text\":\"x\",\"active\":true}",
                "{\"number\":5,\"text\":\"x\",\"active\":true,\"active\":false}"
        }) {
            assertThrows(IllegalArgumentException.class, () -> codec.decodeJson(bad), bad);
        }
    }

    @Test void malformedJsonUtf8CorpusFailsWithoutReplacementCoercion() {
        var codec = schema();
        byte[][] invalid = {
                {(byte)0xff}, {(byte)0xc0, (byte)0xaf}, {(byte)0xe0, (byte)0x80, (byte)0x80},
                {(byte)0xed, (byte)0xa0, (byte)0x80},
                {(byte)0xf4, (byte)0x90, (byte)0x80, (byte)0x80},
                {(byte)0xf0, (byte)0x9f, (byte)0x9a},
                {(byte)0x80}, {(byte)0xfe}
        };
        for (byte[] bytes : invalid) {
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeJsonUtf8(bytes), Arrays.toString(bytes));
        }
    }

    @Test void malformedMessagePackUTF8CorpusFailsWithoutReplacementCoercion() {
        byte[][] invalid = {
                {(byte)0xc0, (byte)0xaf},
                {(byte)0xed, (byte)0xa0, (byte)0x80},
                {(byte)0xf0, (byte)0x9f, (byte)0x9a, (byte)0x80, (byte)0x80},
                {(byte)0xff},
                {(byte)0xe0, (byte)0x80, (byte)0x80}
        };
        var codec = schema();
        for (byte[] corrupted : invalid) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x83);
            key(out, "number"); signed(out, 1);
            key(out, "text");
            out.write(0xd9); out.write(corrupted.length);
            out.writeBytes(corrupted);
            key(out, "active"); out.write(0xc3);
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeMessagePack(out.toByteArray()));
        }
    }

    @Test void messagePackMap32Str32AndBinaryFieldsRemainFailClosed() {
        var codec = schema();
        byte[] valid = msgpack(5, "ok", true);
        byte[] map32 = new byte[valid.length + 4];
        map32[0] = (byte) 0xdf;
        map32[4] = 3;
        System.arraycopy(valid, 1, map32, 5, valid.length - 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMessagePack(map32));

        for (int tag : new int[]{0xdb, 0xc4, 0xc5, 0xc6, 0xc0, 0xca, 0xcb, 0xcf}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x83);
            key(out, "number"); signed(out, 5);
            key(out, "text"); out.write(tag);
            key(out, "active"); out.write(0xc3);
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeMessagePack(out.toByteArray()), "tag " + tag);
        }
    }

    @Test void schemaFingerprintMismatchPrecedesWireParseAndDoesNotAuthenticateOrigin() {
        var codec = schema();
        String correct = codec.schemaSha256Hex();
        assertEquals(List.of(9L, "x", true),
                codec.decodeJsonWithFingerprint(json(9, "x", true), correct).slots());
        var mismatch = assertThrows(IllegalArgumentException.class, () ->
                codec.decodeMessagePackWithFingerprint(new byte[]{(byte) 0xff},
                        "0".repeat(64)));
        assertTrue(mismatch.getMessage().contains("fingerprint"));
        assertThrows(IllegalArgumentException.class, () ->
                codec.decodeJsonWithFingerprint("malformed", null));
    }

    @Test void concurrentReadersDoNotMutateSharedSchemaOrSlots() throws Exception {
        var codec = schema();
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Callable<List<Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                final int n = i;
                tasks.add(() -> {
                    var j = codec.decodeJson(json(n, "x", true));
                    var m = codec.decodeMessagePack(msgpack(n, "x", true));
                    if (!j.equals(m)) throw new AssertionError("format mismatch");
                    return j.slots();
                });
            }
            var futures = pool.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(List.of((long)i, "x", true), futures.get(i).get());
            }
        }
        assertEquals(List.of("number", "text", "active"),
                codec.fields().stream().map(AotScalarWireV1.Field::name).toList());
    }

    @Test void byteMutationSweepNeverEscapesWireErrorOrTypedResult() {
        var codec = schema();
        byte[] valid = msgpack(99, "x", true);
        for (int offset = 0; offset < valid.length; offset++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] changed = valid.clone();
                changed[offset] ^= (byte) (1 << bit);
                try {
                    var value = codec.decodeMessagePack(changed).slots();
                    assertEquals(3, value.size());
                    assertInstanceOf(Long.class, value.get(0));
                    assertInstanceOf(String.class, value.get(1));
                    assertInstanceOf(Boolean.class, value.get(2));
                } catch (IllegalArgumentException expected) {
                    // The wire decoder is allowed to reject any changed message.
                }
            }
        }
    }

    @Test void decodedScalarSlotsAreDefensivelyCopiedAndRemainImmutable() {
        List<Object> mutable = new ArrayList<>(List.of(2L, "x", false));
        var record = new AotScalarWireV1.Decoded(mutable);
        mutable.set(0, 3L);
        assertEquals(2L, record.at(0));
        assertThrows(UnsupportedOperationException.class, () -> record.slots().add(5L));
        assertThrows(IllegalArgumentException.class, () ->
                new AotScalarWireV1.Decoded(Arrays.asList(2L, null, false)));
        assertThrows(IllegalArgumentException.class, () ->
                new AotScalarWireV1.Decoded(List.of(2L, new Object(), false)));
    }
}
