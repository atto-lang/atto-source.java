package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * First portable, schema-guided MessagePack collection codec.
 *
 * <p>Wire v1: [1, schema_id, payload], with sequences encoded as MessagePack
 * arrays. The schema, not the byte array header, distinguishes Tuple,
 * FixedArray, FixedList, Array and List. Scalars in this initial subset are
 * bool, signed i64, finite f64 and UTF-8 string. All other types fail closed.
 *
 * <p>This is an isolated binary-codec foundation, NOT yet the transport used
 * by ActorRuntime.send. Only compiler-owned descriptors may be used to admit
 * untrusted actor messages once the runtime integration lands.
 */
public final class MessagePackCollectionCodec {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_NODES = 100_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ELEMENTS = 100_000;
    public static final int MAX_SCHEMA_ID_BYTES = 256;

    private MessagePackCollectionCodec() { }

    public sealed interface Shape permits Scalar, Sequence { }

    public enum ScalarKind { BOOL, INT64, FLOAT64, STRING }
    public enum SequenceKind { TUPLE, FIXED_ARRAY, FIXED_LIST, ARRAY, LIST }

    public record Scalar(ScalarKind kind) implements Shape {
        public Scalar { Objects.requireNonNull(kind, "scalar kind"); }
    }

    public record Sequence(
            SequenceKind kind,
            List<Shape> slots,
            Shape element) implements Shape {
        public Sequence {
            Objects.requireNonNull(kind, "sequence kind");
            slots = List.copyOf(slots);
            boolean fixed = isFixed(kind);
            if (fixed && (element != null || slots.size() > MAX_ELEMENTS)) {
                throw new IllegalArgumentException("fixed sequences require slots and no element descriptor");
            }
            if (!fixed && (element == null || !slots.isEmpty())) {
                throw new IllegalArgumentException("variable sequences require an element descriptor");
            }
        }
    }

    public static Scalar bool() { return new Scalar(ScalarKind.BOOL); }
    public static Scalar int64() { return new Scalar(ScalarKind.INT64); }
    public static Scalar float64() { return new Scalar(ScalarKind.FLOAT64); }
    public static Scalar string() { return new Scalar(ScalarKind.STRING); }

    public static Sequence tuple(Shape... items) {
        return new Sequence(SequenceKind.TUPLE, List.of(items), null);
    }
    public static Sequence fixedArray(Shape... items) {
        return new Sequence(SequenceKind.FIXED_ARRAY, List.of(items), null);
    }
    public static Sequence fixedList(Shape... items) {
        return new Sequence(SequenceKind.FIXED_LIST, List.of(items), null);
    }
    public static Sequence array(Shape element) {
        return new Sequence(SequenceKind.ARRAY, List.of(), element);
    }
    public static Sequence list(Shape element) {
        return new Sequence(SequenceKind.LIST, List.of(), element);
    }

    private static boolean isFixed(SequenceKind kind) {
        return kind == SequenceKind.TUPLE
                || kind == SequenceKind.FIXED_ARRAY
                || kind == SequenceKind.FIXED_LIST;
    }

    public static byte[] encode(String schemaId, Shape shape, Object value) {
        checkSchemaId(schemaId);
        Objects.requireNonNull(shape, "shape");
        Writer writer = new Writer();
        writer.arrayHeader(3);
        writer.signedInt(VERSION);
        writer.string(schemaId);
        writer.value(shape, value, 0);
        return writer.result();
    }

    public static Object decode(String schemaId, Shape shape, byte[] bytes) {
        checkSchemaId(schemaId);
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("message exceeds maximum byte size");
        Reader reader = new Reader(bytes);
        if (reader.arrayHeader() != 3) throw new IllegalArgumentException("invalid message envelope arity");
        if (reader.signedInt() != VERSION) throw new IllegalArgumentException("unsupported message format version");
        String actualSchema = reader.string();
        if (!schemaId.equals(actualSchema)) throw new IllegalArgumentException("message schema identity mismatch");
        Object value = reader.value(shape, 0);
        if (!reader.exhausted()) throw new IllegalArgumentException("trailing MessagePack bytes");
        return value;
    }

    private static void checkSchemaId(String id) {
        Objects.requireNonNull(id, "schema id");
        if (id.isBlank() || id.length() > MAX_SCHEMA_ID_BYTES
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(id)
                || id.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("schema id must be printable ASCII (1-256 bytes)");
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        private int nodes;

        void add(int b) {
            if (stream.size() >= MAX_BYTES) throw new IllegalArgumentException("message exceeds maximum byte size");
            stream.write(b);
        }

        void bytes(byte[] data) {
            if ((long) stream.size() + data.length > MAX_BYTES) {
                throw new IllegalArgumentException("message exceeds maximum byte size");
            }
            stream.writeBytes(data);
        }

        void uint(int size, long value) {
            for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) add((int) (value >>> shift) & 0xff);
        }

        void arrayHeader(int n) {
            if (n < 0 || n > MAX_ELEMENTS) throw new IllegalArgumentException("sequence exceeds maximum elements");
            if (n <= 15) add(0x90 | n);
            else if (n <= 65535) { add(0xdc); uint(2, n); }
            else { add(0xdd); uint(4, n); }
        }

        void signedInt(long n) {
            if (n >= 0 && n <= 127) add((int) n);
            else if (n >= -32 && n < 0) add((int) n & 0xff);
            else if (n >= 0 && n <= 255) { add(0xcc); uint(1, n); }
            else if (n >= 0 && n <= 65535) { add(0xcd); uint(2, n); }
            else if (n >= 0 && n <= 0xffffffffL) { add(0xce); uint(4, n); }
            else if (n >= 0) { add(0xcf); uint(8, n); }
            else if (n >= Byte.MIN_VALUE) { add(0xd0); uint(1, n); }
            else if (n >= Short.MIN_VALUE) { add(0xd1); uint(2, n); }
            else if (n >= Integer.MIN_VALUE) { add(0xd2); uint(4, n); }
            else { add(0xd3); uint(8, n); }
        }

        void string(String s) {
            if (s.length() > MAX_BYTES) throw new IllegalArgumentException("string exceeds byte budget");
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            int n = bytes.length;
            if (n <= 31) add(0xa0 | n);
            else if (n <= 255) { add(0xd9); uint(1, n); }
            else if (n <= 65535) { add(0xda); uint(2, n); }
            else { add(0xdb); uint(4, n); }
            bytes(bytes);
        }

        void value(Shape shape, Object value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new IllegalArgumentException("message shape exceeds depth/node quota");
            }
            if (shape instanceof Scalar scalar) {
                switch (scalar.kind()) {
                    case BOOL -> {
                        if (!(value instanceof Boolean b)) throw new IllegalArgumentException("expected bool");
                        add(b ? 0xc3 : 0xc2);
                    }
                    case INT64 -> {
                        if (!(value instanceof Byte || value instanceof Short
                                || value instanceof Integer || value instanceof Long)) {
                            throw new IllegalArgumentException("expected signed i64");
                        }
                        signedInt(((Number) value).longValue());
                    }
                    case FLOAT64 -> {
                        if (!(value instanceof Float || value instanceof Double)) {
                            throw new IllegalArgumentException("expected float64");
                        }
                        double n = ((Number) value).doubleValue();
                        if (!Double.isFinite(n)) throw new IllegalArgumentException("nonfinite floats not allowed");
                        add(0xcb);
                        uint(8, Double.doubleToRawLongBits(n));
                    }
                    case STRING -> {
                        if (!(value instanceof String s)) throw new IllegalArgumentException("expected string");
                        string(s);
                    }
                }
                return;
            }

            Sequence sequence = (Sequence) shape;
            if (value == null) throw new IllegalArgumentException("sequence cannot be null");
            int n;
            boolean list = value instanceof List<?>;
            if (list) n = ((List<?>) value).size();
            else if (value.getClass().isArray()) n = Array.getLength(value);
            else throw new IllegalArgumentException("expected sequence data");
            if (n > MAX_ELEMENTS) throw new IllegalArgumentException("sequence exceeds maximum elements");
            if (isFixed(sequence.kind()) && sequence.slots().size() != n) {
                throw new IllegalArgumentException("wrong fixed sequence arity");
            }
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic actor collection");
            }
            try {
                arrayHeader(n);
                for (int i = 0; i < n; i++) {
                    Object item = list ? ((List<?>) value).get(i) : Array.get(value, i);
                    Shape member = isFixed(sequence.kind()) ? sequence.slots().get(i) : sequence.element();
                    value(member, item, depth + 1);
                }
            } finally {
                visiting.remove(value);
            }
        }

        byte[] result() { return stream.toByteArray(); }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int at;
        private int nodes;

        Reader(byte[] bytes) { this.bytes = bytes; }

        int u8() {
            if (at >= bytes.length) throw new IllegalArgumentException("truncated MessagePack value");
            return bytes[at++] & 0xff;
        }

        long uint(int len) {
            if (len > bytes.length - at) throw new IllegalArgumentException("truncated MessagePack integer");
            long n = 0;
            for (int i = 0; i < len; i++) n = (n << 8) | u8();
            return n;
        }

        int arrayHeader() {
            int tag = u8();
            long n;
            if ((tag & 0xf0) == 0x90) n = tag & 0x0f;
            else if (tag == 0xdc) n = uint(2);
            else if (tag == 0xdd) n = uint(4);
            else throw new IllegalArgumentException("expected MessagePack array");
            if (n > MAX_ELEMENTS) throw new IllegalArgumentException("sequence exceeds maximum elements");
            return (int) n;
        }

        long signedInt() {
            int tag = u8();
            if (tag <= 0x7f) return tag;
            if (tag >= 0xe0) return (byte) tag;
            return switch (tag) {
                case 0xcc -> uint(1);
                case 0xcd -> uint(2);
                case 0xce -> uint(4);
                case 0xcf -> {
                    long u = uint(8);
                    if (u < 0) throw new IllegalArgumentException("unsigned integer overflows signed i64");
                    yield u;
                }
                case 0xd0 -> (byte) uint(1);
                case 0xd1 -> (short) uint(2);
                case 0xd2 -> (int) uint(4);
                case 0xd3 -> uint(8);
                default -> throw new IllegalArgumentException("expected signed i64 MessagePack value");
            };
        }

        String string() {
            int tag = u8();
            long length;
            if ((tag & 0xe0) == 0xa0) length = tag & 0x1f;
            else if (tag == 0xd9) length = uint(1);
            else if (tag == 0xda) length = uint(2);
            else if (tag == 0xdb) length = uint(4);
            else throw new IllegalArgumentException("expected MessagePack UTF-8 string");
            if (length > MAX_BYTES || length > bytes.length - at) {
                throw new IllegalArgumentException("oversized or truncated MessagePack string");
            }
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                String result = decoder.decode(ByteBuffer.wrap(bytes, at, (int) length)).toString();
                at += (int) length;
                return result;
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("malformed UTF-8 in MessagePack", malformed);
            }
        }

        Object value(Shape shape, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new IllegalArgumentException("message shape exceeds depth/node quota");
            }
            if (shape instanceof Scalar scalar) {
                return switch (scalar.kind()) {
                    case BOOL -> {
                        int tag = u8();
                        if (tag != 0xc2 && tag != 0xc3) throw new IllegalArgumentException("expected bool");
                        yield tag == 0xc3;
                    }
                    case INT64 -> signedInt();
                    case FLOAT64 -> {
                        if (u8() != 0xcb) throw new IllegalArgumentException("expected float64");
                        double n = Double.longBitsToDouble(uint(8));
                        if (!Double.isFinite(n)) throw new IllegalArgumentException("nonfinite floats not allowed");
                        yield n;
                    }
                    case STRING -> string();
                };
            }
            Sequence seq = (Sequence) shape;
            int n = arrayHeader();
            if (isFixed(seq.kind()) && n != seq.slots().size()) {
                throw new IllegalArgumentException("wrong fixed sequence arity");
            }
            ArrayList<Object> items = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Shape member = isFixed(seq.kind()) ? seq.slots().get(i) : seq.element();
                items.add(value(member, depth + 1));
            }
            return List.copyOf(items);
        }

        boolean exhausted() { return at == bytes.length; }
    }
}
