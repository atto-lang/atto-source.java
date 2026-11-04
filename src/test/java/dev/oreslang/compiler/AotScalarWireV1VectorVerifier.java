package dev.oreslang.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared, byte-exact JSON and MessagePack scalar corpus verifier.
 * Runs against a compiler-checked, closed nominal schema; never constructs
 * guest objects or claims native AOT/backend execution by itself.
 */
public final class AotScalarWireV1VectorVerifier {
    private static final int MAX_CORPUS_BYTES = 65_536;
    private static final int EXPECTED_POSITIVE = 8;
    private static final int EXPECTED_NEGATIVE = 15;
    private static final String HEADER = "# attolang.aot-wire-scalar-fixtures/1; P|id|signed-i64|bool|string-utf8-base64|json-utf8-base64|msgpack-hex; N|id|format|json-utf8-base64-or-msgpack-hex";
    private static final String[] POSITIVE_IDS = {
            "zero", "one", "negative_one", "i64_min", "i64_max",
            "utf8_rocket", "str8_boundary", "str16_boundary"
    };
    private static final String[] NEGATIVE_IDS = {
            "json_missing", "json_duplicate", "json_unknown", "json_fraction",
            "json_null", "json_overflow", "msgpack_duplicate", "msgpack_unknown",
            "msgpack_nil", "msgpack_float64", "msgpack_uint64",
            "msgpack_truncated", "msgpack_trailing", "msgpack_map32", "msgpack_bad_utf8"
    };

    private AotScalarWireV1VectorVerifier() { }

    public record Summary(int positivePairs, int rejectedVectors, int codecAssertions) { }

    public static Summary verify(AotScalarWireV1.Schema schema, InputStream corpus) {
        Objects.requireNonNull(schema, "compiler-issued scalar schema");
        Objects.requireNonNull(corpus, "golden corpus");
        final byte[] bytes;
        try {
            bytes = corpus.readNBytes(MAX_CORPUS_BYTES + 1);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read golden wire corpus", e);
        }
        if (bytes.length > MAX_CORPUS_BYTES) {
            throw new IllegalArgumentException("golden corpus exceeds byte budget");
        }
        final String text = decodeUtf8(bytes);
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(HEADER)) {
            throw new IllegalArgumentException("unrecognized golden wire corpus version");
        }
        Set<String> ids = new HashSet<>();
        int positive = 0;
        int negative = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) continue;
            String[] columns = line.split("\\|", -1);
            if (columns.length < 4 || !columns[1].matches("[a-z][a-z0-9_]{1,99}")
                    || !ids.add(columns[1])) {
                throw new IllegalArgumentException("invalid or duplicated corpus ID at line " + i);
            }
            if (columns[0].equals("P")) {
                if (columns.length != 7 || positive >= EXPECTED_POSITIVE ||
                        !columns[1].equals(POSITIVE_IDS[positive])) {
                    throw new IllegalArgumentException("invalid positive corpus row at line " + i);
                }
                long n;
                try { n = Long.parseLong(columns[2]); }
                catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("invalid signed i64 golden value", invalid);
                }
                if (!columns[3].equals("true") && !columns[3].equals("false")) {
                    throw new IllegalArgumentException("invalid boolean golden value");
                }
                boolean flag = Boolean.parseBoolean(columns[3]);
                String value = decodeUtf8(base64(columns[4]));
                List<Object> expected = List.of(n, value, flag);
                byte[] json = base64(columns[5]);
                byte[] msgpack = hex(columns[6]);
                if (!schema.decodeJsonUtf8(json).slots().equals(expected)) {
                    throw new AssertionError("JSON golden mismatch: " + columns[1]);
                }
                if (!schema.decodeMessagePack(msgpack).slots().equals(expected)) {
                    throw new AssertionError("MessagePack golden mismatch: " + columns[1]);
                }
                positive++;
            } else if (columns[0].equals("N")) {
                if (columns.length != 4 || negative >= EXPECTED_NEGATIVE ||
                        !columns[1].equals(NEGATIVE_IDS[negative])) {
                    throw new IllegalArgumentException("invalid negative corpus row at line " + i);
                }
                final byte[] payload;
                final boolean json;
                if (columns[2].equals("json") && negative < 6) {
                    json = true;
                    payload = base64(columns[3]);
                } else if (columns[2].equals("messagepack") && negative >= 6) {
                    json = false;
                    payload = hex(columns[3]);
                } else {
                    throw new IllegalArgumentException("unrecognized wire format");
                }
                try {
                    if (json) schema.decodeJsonUtf8(payload);
                    else schema.decodeMessagePack(payload);
                } catch (IllegalArgumentException expectedRejection) {
                    negative++;
                    continue;
                }
                throw new AssertionError("negative wire vector accepted: " + columns[1]);
            } else {
                throw new IllegalArgumentException("invalid corpus row kind");
            }
        }
        if (positive != EXPECTED_POSITIVE || negative != EXPECTED_NEGATIVE) {
            throw new AssertionError("incomplete golden corpus: positive=" + positive +
                    " negative=" + negative);
        }
        return new Summary(positive, negative, positive * 2 + negative);
    }

    private static byte[] base64(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(bytes).equals(value)) {
                throw new IllegalArgumentException("noncanonical golden base64");
            }
            return bytes;
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid corpus base64", invalid);
        }
    }

    private static byte[] hex(String value) {
        if (value.length() % 2 != 0 || !value.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("noncanonical golden hex");
        }
        try { return HexFormat.of().parseHex(value); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid corpus hex", invalid);
        }
    }

    private static String decodeUtf8(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid golden UTF-8 corpus", invalid);
        }
    }
}
