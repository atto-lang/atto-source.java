package dev.oreslang.compiler;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V1 compiler-owned scalar wire binding for AOT reflection descriptors.
 *
 * The schema is created only from a source-checked closed nominal through
 * OresCompiler. It uses exact bounded field names and scalar types, not Java
 * reflection, annotations at runtime, JavaBean setters, dynamic proxies,
 * Class.forName, classpath scanning or generated runtime bytecode.
 *
 * Decoding yields immutable, field-ordered scalar slots. Construction of an
 * AttoLang guest object is a separate compiler/backend obligation. No AOT
 * native executable or guest execution is claimed by this host implementation.
 */
public final class AotScalarWireV1 {
    private static final int MAX_INPUT_BYTES = 65_536;
    private static final int MAX_STRING_BYTES = 4_096;
    private static final int MAX_FIELDS = 256;

    private AotScalarWireV1() { }

    public record Field(String name, Kind kind) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }
    public enum Kind { I64, BOOL, STRING }

    /** Immutable, slot-ordered scalar data for compiler-generated constructors. */
    public record Decoded(List<Object> slots) {
        public Decoded {
            try {
                slots = List.copyOf(slots);
            } catch (NullPointerException malformed) {
                throw invalid("null decoded scalar slot or collection");
            }
            for (Object value : slots) {
                if (!(value instanceof Long) &&
                    !(value instanceof Boolean) &&
                    !(value instanceof String)) {
                    throw new IllegalArgumentException("unsupported wire scalar value");
                }
            }
        }
        public Object at(int index) { return slots.get(index); }
    }

    public static final class Schema {
        private final String typeIdHex;
        private final String qualifiedType;
        private final String schemaSha256Hex;
        private final List<Field> fields;

        private Schema(String typeIdHex, String qualifiedType,
                       String schemaSha256Hex, List<Field> fields) {
            this.typeIdHex = typeIdHex;
            this.qualifiedType = qualifiedType;
            this.schemaSha256Hex = schemaSha256Hex;
            this.fields = List.copyOf(fields);
        }
        public String typeIdHex() { return typeIdHex; }
        public String qualifiedType() { return qualifiedType; }
        /** Field-layout identity, distinct from nominal type ID. Not an attestation. */
        public String schemaSha256Hex() { return schemaSha256Hex; }
        public void requireSchemaFingerprint(String expectedFingerprint) {
            if (expectedFingerprint == null ||
                    !schemaSha256Hex.equals(expectedFingerprint)) {
                throw invalid("AOT wire schema fingerprint mismatch");
            }
        }
        public Decoded decodeJsonWithFingerprint(String json, String expectedFingerprint) {
            requireSchemaFingerprint(expectedFingerprint);
            return decodeJson(json);
        }
        public Decoded decodeMessagePackWithFingerprint(
                byte[] messagePack, String expectedFingerprint) {
            requireSchemaFingerprint(expectedFingerprint);
            return decodeMessagePack(messagePack);
        }
        public List<Field> fields() { return fields; }
        public Decoded decodeJson(String json) { return new Json(this, json).decode(); }
        /** Strict UTF-8 entrypoint: no replacement-character coercion. */
        public Decoded decodeJsonUtf8(byte[] data) {
            if (data == null || data.length > MAX_INPUT_BYTES) {
                throw invalid("JSON UTF-8 input exceeds byte budget");
            }
            final String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data.clone())).toString();
            } catch (CharacterCodingException badUtf8) {
                throw invalid("malformed JSON UTF-8");
            }
            return decodeJson(value);
        }
        public Decoded decodeMessagePack(byte[] data) { return new MsgPack(this, data).decode(); }

        private int fieldIndex(String key) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name().equals(key)) return i;
            }
            throw invalid("unknown field");
        }
        private Decoded finish(Object[] slots, boolean[] seen, int count) {
            if (count != fields.size()) throw invalid("missing required field");
            List<Object> values = new ArrayList<>(slots.length);
            for (int i = 0; i < slots.length; i++) {
                if (!seen[i] || slots[i] == null) throw invalid("missing required field");
                values.add(slots[i]);
            }
            return new Decoded(values);
        }
    }

    static Schema fromCheckedQuery(OresCompiler.StaticTypeOfQueryV1 query) {
        Objects.requireNonNull(query, "source-checked compiler query");
        var table = AotReflectionTableV1Reader.decode(query.descriptorTable());
        if (table.types().size() != 1) throw invalid("requires exactly one closed nominal");
        var type = table.types().getFirst();
        if (!type.typeIdHex().equals(query.typeIdHex()) ||
            !type.resolvedSymbol().equals(query.qualifiedType()) ||
            !type.genericArguments().isEmpty() ||
            type.publicFields().size() > MAX_FIELDS) {
            throw invalid("source-checked descriptor identity mismatch");
        }
        List<Field> fields = new ArrayList<>(type.publicFields().size());
        for (var field : type.publicFields()) {
            Kind kind = switch (field.resolvedType()) {
                case "int", "i64" -> Kind.I64;
                case "bool" -> Kind.BOOL;
                case "string" -> Kind.STRING;
                default -> throw invalid("unsupported public field wire type");
            };
            fields.add(new Field(field.name(), kind));
        }
        return new Schema(type.typeIdHex(), type.resolvedSymbol(),
                schemaDigest(query.descriptorTable()), fields);
    }

    private static String schemaDigest(byte[] table) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("attolang.scalar-wire-schema/1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(table);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Json {
        private final Schema schema;
        private final String input;
        private int pos;
        Json(Schema schema, String input) {
            this.schema = schema;
            if (input == null || input.length() > MAX_INPUT_BYTES ||
                    input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                throw invalid("JSON input exceeds byte budget");
            }
            this.input = input;
        }
        Decoded decode() {
            Object[] slots = new Object[schema.fields.size()];
            boolean[] seen = new boolean[slots.length];
            int count = 0;
            ws(); need('{'); ws();
            if (!accept('}')) {
                while (true) {
                    if (count >= schema.fields.size()) throw invalid("too many JSON fields");
                    String key = string();
                    int index = schema.fieldIndex(key);
                    if (seen[index]) throw invalid("duplicate JSON field");
                    ws(); need(':'); ws();
                    slots[index] = value(schema.fields.get(index).kind());
                    seen[index] = true;
                    count++;
                    ws();
                    if (accept('}')) break;
                    need(','); ws();
                }
            }
            ws();
            if (pos != input.length()) throw invalid("trailing JSON data");
            return schema.finish(slots, seen, count);
        }
        private Object value(Kind kind) {
            return switch (kind) {
                case I64 -> integer();
                case BOOL -> {
                    if (input.startsWith("true", pos)) { pos += 4; yield Boolean.TRUE; }
                    if (input.startsWith("false", pos)) { pos += 5; yield Boolean.FALSE; }
                    throw invalid("expected JSON boolean");
                }
                case STRING -> string();
            };
        }
        private Long integer() {
            int start = pos;
            if (accept('-')) { /* signed integer */ }
            if (pos >= input.length()) throw invalid("invalid JSON integer");
            char first = input.charAt(pos);
            if (first == '0') {
                pos++;
                if (pos < input.length() && digit(input.charAt(pos))) {
                    throw invalid("JSON leading zero");
                }
            } else if (first >= '1' && first <= '9') {
                do { pos++; } while (pos < input.length() && digit(input.charAt(pos)));
            } else throw invalid("invalid JSON integer");
            // Fractions, exponents, out-of-range values and implicit conversions
            // do not fit the v1 signed-i64 wire schema.
            try { return Long.valueOf(input.substring(start, pos)); }
            catch (NumberFormatException e) { throw invalid("signed i64 overflow"); }
        }
        private String string() {
            need('"');
            StringBuilder result = new StringBuilder();
            boolean closed = false;
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') { closed = true; break; }
                if (c == '\\') {
                    if (pos == input.length()) throw invalid("truncated JSON escape");
                    char e = input.charAt(pos++);
                    switch (e) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append((char) hex4());
                        default -> throw invalid("invalid JSON escape");
                    }
                } else {
                    if (c < 0x20) throw invalid("unescaped JSON control");
                    result.append(c);
                }
                if (result.length() > MAX_STRING_BYTES) throw invalid("JSON string exceeds budget");
            }
            if (!closed) throw invalid("unterminated JSON string");
            String value = result.toString();
            checkUnicode(value);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
                throw invalid("JSON string exceeds UTF-8 budget");
            }
            return value;
        }
        private int hex4() {
            if (pos + 4 > input.length()) throw invalid("truncated Unicode escape");
            int result = 0;
            for (int j = 0; j < 4; j++) {
                char h = input.charAt(pos++);
                int digit = (h >= '0' && h <= '9') ? h - '0'
                        : (h >= 'a' && h <= 'f') ? h - 'a' + 10
                        : (h >= 'A' && h <= 'F') ? h - 'A' + 10 : -1;
                if (digit < 0) throw invalid("invalid Unicode escape");
                result = (result << 4) | digit;
            }
            return result;
        }
        private void ws() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }
        private boolean accept(char c) {
            if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; }
            return false;
        }
        private void need(char c) {
            if (!accept(c)) throw invalid("invalid JSON structure");
        }
    }

    private static final class MsgPack {
        private final Schema schema;
        private final byte[] input;
        private int pos;
        MsgPack(Schema schema, byte[] input) {
            this.schema = schema;
            if (input == null || input.length > MAX_INPUT_BYTES) {
                throw invalid("MessagePack input exceeds byte budget");
            }
            this.input = input.clone(); // defensive boundary against caller mutation
        }
        Decoded decode() {
            int tag = u8();
            int size;
            if ((tag & 0xf0) == 0x80) size = tag & 0x0f;
            else if (tag == 0xde) size = (int) unsigned(2);
            else throw invalid("MessagePack must be a bounded map");
            if (size > MAX_FIELDS || size != schema.fields.size()) {
                throw invalid("MessagePack field count mismatch");
            }
            Object[] slots = new Object[size];
            boolean[] seen = new boolean[size];
            for (int i = 0; i < size; i++) {
                String key = str();
                int index = schema.fieldIndex(key);
                if (seen[index]) throw invalid("duplicate MessagePack field");
                slots[index] = switch (schema.fields.get(index).kind()) {
                    case I64 -> signed();
                    case BOOL -> bool();
                    case STRING -> str();
                };
                seen[index] = true;
            }
            if (pos != input.length) throw invalid("trailing MessagePack bytes");
            return schema.finish(slots, seen, size);
        }
        private Boolean bool() {
            return switch (u8()) {
                case 0xc2 -> Boolean.FALSE;
                case 0xc3 -> Boolean.TRUE;
                default -> throw invalid("expected MessagePack bool");
            };
        }
        private Long signed() {
            int tag = u8();
            if (tag <= 0x7f) return (long) tag;
            if (tag >= 0xe0) return (long) (byte) tag;
            return switch (tag) {
                case 0xd0 -> (long) (byte) u8();
                case 0xd1 -> (long) (short) unsigned(2);
                case 0xd2 -> (long) (int) unsigned(4);
                case 0xd3 -> unsigned(8);
                case 0xcc -> (long) u8();
                case 0xcd -> unsigned(2);
                case 0xce -> unsigned(4);
                // uint64 is omitted: it can exceed signed 64-bit.
                default -> throw invalid("expected bounded signed MessagePack integer");
            };
        }
        private String str() {
            int tag = u8();
            int length;
            if ((tag & 0xe0) == 0xa0) length = tag & 0x1f;
            else if (tag == 0xd9) length = u8();
            else if (tag == 0xda) length = (int) unsigned(2);
            else throw invalid("expected MessagePack text");
            if (length > MAX_STRING_BYTES || length > input.length - pos) {
                throw invalid("MessagePack string exceeds budget");
            }
            String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input, pos, length)).toString();
            } catch (CharacterCodingException e) {
                throw invalid("invalid MessagePack UTF-8");
            }
            pos += length;
            checkUnicode(value);
            return value;
        }
        private int u8() {
            if (pos >= input.length) throw invalid("truncated MessagePack data");
            return Byte.toUnsignedInt(input[pos++]);
        }
        private long unsigned(int length) {
            if (length > input.length - pos) throw invalid("truncated MessagePack number");
            long value = 0;
            for (int i = 0; i < length; i++) value = (value << 8) | u8();
            return value;
        }
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static void checkUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    throw invalid("unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw invalid("unpaired low surrogate");
            }
        }
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("AOT_SCALAR_WIRE_V1: " + reason);
    }
}
