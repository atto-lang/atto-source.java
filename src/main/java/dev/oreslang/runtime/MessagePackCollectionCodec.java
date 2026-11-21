package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * First portable, schema-guided MessagePack collection codec.
 *
 * <p>Wire v2: [2, schema_id, SHA-256(shape_descriptor), payload]. The
 * full digest binds collection kind, arity, and recursively typed elements.
 * Sequences are encoded as MessagePack
 * arrays. The schema, not the byte array header, distinguishes Tuple,
 * FixedArray, FixedList, Array and List. Scalars in this initial subset are
 * bool, signed i64, finite f64 and UTF-8 string. All other types fail closed.
 *
 * <p>This is an isolated binary-codec foundation, NOT yet the transport used
 * by ActorRuntime.send. Only compiler-owned descriptors may be used to admit
 * untrusted actor messages once the runtime integration lands.
 */
public final class MessagePackCollectionCodec {
    public static final int VERSION = 2;
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_NODES = 100_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ELEMENTS = 100_000;
    public static final int MAX_SCHEMA_ID_BYTES = 256;
    // Bound the canonical descriptor independently of the encoded payload.
    // Nested enums may list thousands of declared cases with long names.
    public static final int MAX_DESCRIPTOR_BYTES = 1_048_576;

    private MessagePackCollectionCodec() { }

    public sealed interface Shape permits Scalar, Sequence, NestedEnum { }

    /**
     * Immutable trusted actor mailbox codec declaration, constructed by
     * compiler lowering (or by explicitly trusted host integration tests).
     * The sender supplies values, NEVER this descriptor from wire bytes.
     */
    /**
     * Runtime-owned transient allocation budget used while constructing a
     * mailbox frame. Implementations must charge before variable-size
     * allocation and release temporary capacity after it becomes unreachable.
     */
    public interface ScratchBudget {
        void reserve(long bytes);
        void release(long bytes);

        ScratchBudget NONE = new ScratchBudget() {
            @Override public void reserve(long bytes) {
                if (bytes < 0) throw new IllegalArgumentException("negative scratch reservation");
            }
            @Override public void release(long bytes) {
                if (bytes < 0) throw new IllegalArgumentException("negative scratch release");
            }
        };
    }

    /**
     * Encoding proof returned to ActorRuntime. decodedBytes is a conservative
     * logical upper bound for the recipient-side decoded graph and short-lived
     * decode containers; it is reserved before mailbox publication.
     */
    public record EncodedMailboxFrame(byte[] bytes, long decodedBytes) {
        public EncodedMailboxFrame {
            Objects.requireNonNull(bytes, "mailbox frame");
            if (bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("message exceeds maximum byte size");
            if (decodedBytes < 0)
                throw new IllegalArgumentException("decoded mailbox footprint cannot be negative");
        }
    }

    /** Compiler-trusted, runtime-bound binary mailbox. Implementations cannot
     * be chosen by untrusted wire bytes; the actor binds one before admission. */
    public sealed interface MailboxWireCodec permits MailboxSchema, TaggedMessageCodec {
        byte[] encode(Object value);
        Object decode(byte[] bytes);

        /**
         * Encode while charging transient storage before allocation. The
         * returned decodedBytes is trusted because this interface is sealed to
         * compiler/runtime codecs.
         */
        EncodedMailboxFrame encodeForMailbox(Object value, ScratchBudget scratch);
    }

    public static final class MailboxSchema implements MailboxWireCodec {
        private final String schemaId;
        private final Shape shape;
        private final byte[] descriptorHash;

        public MailboxSchema(String schemaId, Shape shape) {
            checkSchemaId(schemaId);
            this.schemaId = schemaId;
            this.shape = Objects.requireNonNull(shape, "mailbox shape");
            // Schema shape is immutable compiler/runtime metadata. Compute the
            // potentially large descriptor exactly once at bind construction,
            // never on each mailbox send/receive where it would escape the
            // transient codec reservation.
            this.descriptorHash = shapeDigest(shape);
        }

        public String schemaId() { return schemaId; }
        public Shape shape() { return shape; }

        @Override
        public byte[] encode(Object value) {
            return MessagePackCollectionCodec.encodeForMailbox(
                    schemaId,
                    shape,
                    value,
                    descriptorHash,
                    ScratchBudget.NONE).bytes();
        }

        @Override
        public EncodedMailboxFrame encodeForMailbox(
                Object value,
                ScratchBudget scratch) {
            return MessagePackCollectionCodec.encodeForMailbox(
                    schemaId,
                    shape,
                    value,
                    descriptorHash,
                    scratch);
        }

        @Override
        public Object decode(byte[] bytes) {
            return MessagePackCollectionCodec.decode(
                    schemaId,
                    shape,
                    descriptorHash,
                    bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MailboxSchema schema
                    && schemaId.equals(schema.schemaId)
                    && shape.equals(schema.shape);
        }

        @Override
        public int hashCode() {
            return Objects.hash(schemaId, shape);
        }

        @Override
        public String toString() {
            return "MailboxSchema[schemaId=" + schemaId + ", shape=" + shape + "]";
        }
    }

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

    /**
     * Closed nominal enum as a nested value in a compiler-bound MessagePack
     * shape. Definitions are a finite DAG; recursive/boxed schemas are NOT
     * admitted by the compiler. No wire bytes can supply these descriptors.
     */
    /**
     * Nominal wire identifiers are an ASCII-only v2 ABI. Without this check
     * shapeDigest()'s US-ASCII conversion replaces non-ASCII characters,
     * allowing different source names to bind to the same digest.
     * Non-ASCII local identifiers may be valid language values but require a
     * new, explicitly versioned UTF-8 wire grammar before transport.
     */
    static void requireCanonicalEnumWireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 256
                || !((name.charAt(0) >= 'A' && name.charAt(0) <= 'Z')
                     || (name.charAt(0) >= 'a' && name.charAt(0) <= 'z')
                     || name.charAt(0) == '_')) {
            throw new IllegalArgumentException("nested enum wire name must be an ASCII identifier");
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                throw new IllegalArgumentException("nested enum wire name must be an ASCII identifier");
            }
        }
    }

    public record EnumCaseShape(String name, int tag, Shape payload) {
        public EnumCaseShape {
            requireCanonicalEnumWireName(name);
            if (tag < 1)
                throw new IllegalArgumentException("invalid nested enum case tag");
        }
    }

    public record NestedEnum(String name, List<EnumCaseShape> cases) implements Shape {
        public NestedEnum {
            requireCanonicalEnumWireName(name);
            if (cases == null || cases.isEmpty() || cases.size() > 4096)
                throw new IllegalArgumentException("invalid nested enum shape");
            cases = List.copyOf(cases);
            var names = new java.util.HashSet<String>();
            int lastTag = 0;
            for (EnumCaseShape c : cases) {
                if (!names.add(c.name()) || c.tag() <= lastTag)
                    throw new IllegalArgumentException("unordered/duplicate nested enum case");
                lastTag = c.tag();
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
        return encodeForMailbox(
                schemaId,
                shape,
                value,
                ScratchBudget.NONE).bytes();
    }

    public static EncodedMailboxFrame encodeForMailbox(
            String schemaId,
            Shape shape,
            Object value,
            ScratchBudget scratch) {
        return encodeForMailbox(
                schemaId,
                shape,
                value,
                shapeDigest(shape),
                scratch);
    }

    static EncodedMailboxFrame encodeForMailbox(
            String schemaId,
            Shape shape,
            Object value,
            byte[] descriptorHash,
            ScratchBudget scratch) {
        checkSchemaId(schemaId);
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(descriptorHash, "shape digest");
        Objects.requireNonNull(scratch, "scratch budget");
        if (descriptorHash.length != 32)
            throw new IllegalArgumentException("shape digest must be SHA-256");
        Writer writer = new Writer(scratch);
        try {
            // Decode peak includes the header-selection schema string
            // (TaggedMessageCodec), the full-decode schema string, Reader/
            // digest temporaries and object headers. Charge both UTF-16
            // strings here rather than only the final payload graph.
            writer.addDecodedBytes(
                    Math.addExact(320L, Math.multiplyExact(4L, schemaId.length())));
            writer.arrayHeader(4);
            writer.signedInt(VERSION);
            writer.string(schemaId);
            writer.binaryDigest(descriptorHash);
            writer.value(shape, value, 0);
            return new EncodedMailboxFrame(
                    writer.result(),
                    writer.decodedBytes());
        } finally {
            writer.releaseTraversalScratch();
        }
    }

    /**
     * Strict, bounded first-stage header parsing for closed actor codecs.
     * It reads only the wire version and caller schema ID, never selects a
     * schema from the incoming bytes. Callers must look it up in a trusted
     * compiler-bound table and then invoke decode(...) to verify the full
     * shape digest, canonical payload and absence of trailing bytes.
     */
    public static String envelopeSchemaId(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("message exceeds maximum byte size");
        }
        Reader reader = new Reader(bytes);
        if (reader.arrayHeader() != 4) {
            throw new IllegalArgumentException("invalid message envelope arity");
        }
        if (reader.signedInt() != VERSION) {
            throw new IllegalArgumentException("unsupported message format version");
        }
        String schema = reader.string();
        checkSchemaId(schema);
        return schema;
    }

    public static Object decode(String schemaId, Shape shape, byte[] bytes) {
        return decode(schemaId, shape, shapeDigest(shape), bytes);
    }

    static Object decode(
            String schemaId,
            Shape shape,
            byte[] expectedDigest,
            byte[] bytes) {
        checkSchemaId(schemaId);
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(expectedDigest, "shape digest");
        Objects.requireNonNull(bytes, "bytes");
        if (expectedDigest.length != 32)
            throw new IllegalArgumentException("shape digest must be SHA-256");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("message exceeds maximum byte size");
        Reader reader = new Reader(bytes);
        if (reader.arrayHeader() != 4) throw new IllegalArgumentException("invalid message envelope arity");
        if (reader.signedInt() != VERSION) throw new IllegalArgumentException("unsupported message format version");
        String actualSchema = reader.string();
        if (!schemaId.equals(actualSchema)) throw new IllegalArgumentException("message schema identity mismatch");
        if (!MessageDigest.isEqual(expectedDigest, reader.binaryDigest())) {
            throw new IllegalArgumentException("message shape descriptor mismatch");
        }
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

    /**
     * Canonical grammar:
     *   B | I | F | S
     *   T[shape,...] | FA[shape,...] | FL[shape,...]
     *   A[shape] | L[shape]
     *
     * Hash domain separation includes the wire-major version. This prevents
     * different collections using the same caller schema ID from being decoded
     * under incompatible type/arity assumptions. Hashing does NOT authenticate
     * an untrusted sender: caller schemas must be compiler-owned.
     */
    public static byte[] shapeDigest(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        StringBuilder descriptor = new StringBuilder("oreslang.actor.shape.v2/");
        appendShape(descriptor, shape, 0, new int[]{0});
        if (descriptor.length() > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("codec shape descriptor exceeds byte quota");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(descriptor.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 provider unavailable", impossible);
        }
    }

    private static void appendShape(StringBuilder out, Shape shape, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
            throw new IllegalArgumentException("codec shape exceeds maximum depth/node budget");
        }
        if (out.length() > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("codec shape descriptor exceeds byte quota");
        }
        if (shape instanceof Scalar scalar) {
            out.append(switch (scalar.kind()) {
                case BOOL -> 'B';
                case INT64 -> 'I';
                case FLOAT64 -> 'F';
                case STRING -> 'S';
            });
            return;
        }
        if (shape instanceof NestedEnum nested) {
            // Length delimiters and stable case tags avoid ambiguity between
            // different enum constructors with identical payload shapes.
            out.append("E(").append(nested.name().length()).append(':')
                    .append(nested.name()).append(")[");
            for (EnumCaseShape item : nested.cases()) {
                out.append(item.tag()).append(':')
                        .append(item.name().length()).append(':')
                        .append(item.name()).append('=');
                if (item.payload() == null) out.append('N');
                else appendShape(out, item.payload(), depth + 1, nodes);
                out.append(';');
                if (out.length() > MAX_DESCRIPTOR_BYTES) {
                    throw new IllegalArgumentException("codec shape descriptor exceeds byte quota");
                }
            }
            out.append(']');
            return;
        }
        Sequence seq = (Sequence) shape;
        out.append(switch (seq.kind()) {
            case TUPLE -> "T[";
            case FIXED_ARRAY -> "FA[";
            case FIXED_LIST -> "FL[";
            case ARRAY -> "A[";
            case LIST -> "L[";
        });
        if (isFixed(seq.kind())) {
            for (int i = 0; i < seq.slots().size(); i++) {
                if (i > 0) out.append(',');
                appendShape(out, seq.slots().get(i), depth + 1, nodes);
            }
        } else {
            appendShape(out, seq.element(), depth + 1, nodes);
        }
        out.append(']');
    }

    private static final class Writer {
        private static final long VISITING_SCRATCH_BYTES = 8_192L;

        private final ScratchBudget scratch;
        private final IdentityHashMap<Object, Boolean> visiting;
        private byte[] buffer = new byte[0];
        private int size;
        private int nodes;
        private long decodedBytes;
        private boolean traversalScratchReleased;

        Writer(ScratchBudget scratch) {
            this.scratch = Objects.requireNonNull(scratch, "scratch budget");
            // Reserve before allocating the cycle-detection table. Its live
            // entry count is bounded by MAX_DEPTH because entries are removed
            // after each recursive subtree.
            scratch.reserve(VISITING_SCRATCH_BYTES);
            this.visiting = new IdentityHashMap<>();
        }

        void releaseTraversalScratch() {
            if (!traversalScratchReleased) {
                traversalScratchReleased = true;
                scratch.release(VISITING_SCRATCH_BYTES);
            }
        }

        long decodedBytes() { return decodedBytes; }

        void addDecodedBytes(long bytes) {
            if (bytes < 0)
                throw new IllegalArgumentException("negative decoded mailbox footprint");
            try {
                decodedBytes = Math.addExact(decodedBytes, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "decoded mailbox footprint accounting overflow", overflow);
            }
        }

        void ensureCapacity(int required) {
            if (required < 0 || required > MAX_BYTES) {
                throw new IllegalArgumentException("message exceeds maximum byte size");
            }
            if (required <= buffer.length) return;
            int next = Math.max(64, buffer.length);
            while (next < required) {
                int grown = next > MAX_BYTES / 2 ? MAX_BYTES : next * 2;
                if (grown <= next) {
                    next = MAX_BYTES;
                    break;
                }
                next = grown;
            }
            if (next < required) {
                throw new IllegalArgumentException("message exceeds maximum byte size");
            }

            // During growth both old and new arrays coexist. Charge the full
            // replacement before allocation, then release the old capacity.
            scratch.reserve(next);
            byte[] replacement = Arrays.copyOf(buffer, next);
            scratch.release(buffer.length);
            buffer = replacement;
        }

        void add(int b) {
            if (size >= MAX_BYTES)
                throw new IllegalArgumentException("message exceeds maximum byte size");
            ensureCapacity(size + 1);
            buffer[size++] = (byte) b;
        }

        void bytes(byte[] data) {
            Objects.requireNonNull(data, "encoded bytes");
            long required = (long) size + data.length;
            if (required > MAX_BYTES) {
                throw new IllegalArgumentException("message exceeds maximum byte size");
            }
            ensureCapacity((int) required);
            System.arraycopy(data, 0, buffer, size, data.length);
            size += data.length;
        }

        void uint(int size, long value) {
            for (int shift = (size - 1) * 8; shift >= 0; shift -= 8)
                add((int) (value >>> shift) & 0xff);
        }

        void arrayHeader(int n) {
            if (n < 0 || n > MAX_ELEMENTS)
                throw new IllegalArgumentException("sequence exceeds maximum elements");
            if (n <= 15) add(0x90 | n);
            else if (n <= 65535) { add(0xdc); uint(2, n); }
            else { add(0xdd); uint(4, n); }
        }

        void binaryDigest(byte[] digest) {
            if (digest.length != 32)
                throw new IllegalArgumentException("expected 32-byte schema digest");
            add(0xc4);
            add(32);
            bytes(digest);
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

        private static int utf8Length(String s) {
            long bytes = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c <= 0x7f) {
                    bytes++;
                } else if (c <= 0x7ff) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= s.length()
                            || !Character.isLowSurrogate(s.charAt(i + 1))) {
                        throw new IllegalArgumentException(
                                "malformed UTF-16 input string");
                    }
                    i++;
                    bytes += 4;
                } else if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException(
                            "malformed UTF-16 input string");
                } else {
                    bytes += 3;
                }
                if (bytes > MAX_BYTES)
                    throw new IllegalArgumentException("string exceeds byte budget");
            }
            return (int) bytes;
        }

        void string(String s) {
            Objects.requireNonNull(s, "string");
            int n = utf8Length(s);
            // String.getBytes creates one n-byte temporary. Reserve it before
            // allocation and release it immediately after copying into the
            // runtime-owned output buffer.
            scratch.reserve(n);
            byte[] encoded;
            try {
                encoded = s.getBytes(StandardCharsets.UTF_8);
                if (encoded.length != n) {
                    throw new IllegalStateException(
                            "UTF-8 sizing and encoding disagreed");
                }
                if (n <= 31) add(0xa0 | n);
                else if (n <= 255) { add(0xd9); uint(1, n); }
                else if (n <= 65535) { add(0xda); uint(2, n); }
                else { add(0xdb); uint(4, n); }
                bytes(encoded);
            } finally {
                scratch.release(n);
            }
        }

        void value(Shape shape, Object value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new IllegalArgumentException(
                        "message shape exceeds depth/node quota");
            }
            if (shape instanceof Scalar scalar) {
                switch (scalar.kind()) {
                    case BOOL -> {
                        if (!(value instanceof Boolean b))
                            throw new IllegalArgumentException("expected bool");
                        addDecodedBytes(16L);
                        add(b ? 0xc3 : 0xc2);
                    }
                    case INT64 -> {
                        if (!(value instanceof Byte || value instanceof Short
                                || value instanceof Integer || value instanceof Long)) {
                            throw new IllegalArgumentException("expected signed i64");
                        }
                        addDecodedBytes(24L);
                        signedInt(((Number) value).longValue());
                    }
                    case FLOAT64 -> {
                        if (!(value instanceof Float || value instanceof Double)) {
                            throw new IllegalArgumentException("expected float64");
                        }
                        double n = ((Number) value).doubleValue();
                        if (!Double.isFinite(n))
                            throw new IllegalArgumentException("nonfinite floats not allowed");
                        addDecodedBytes(24L);
                        add(0xcb);
                        uint(8, Double.doubleToRawLongBits(n));
                    }
                    case STRING -> {
                        if (!(value instanceof String string))
                            throw new IllegalArgumentException("expected string");
                        addDecodedBytes(Math.addExact(
                                40L,
                                Math.multiplyExact(2L, string.length())));
                        string(string);
                    }
                }
                return;
            }

            if (shape instanceof NestedEnum nested) {
                if (!(value instanceof TaggedMessageCodec.TaggedValue tagged)
                        || !nested.name().equals(tagged.enumName()))
                    throw new IllegalArgumentException(
                            "nested enum nominal owner mismatch");
                EnumCaseShape selected = null;
                for (EnumCaseShape member : nested.cases()) {
                    if (member.tag() == tagged.tag()
                            && member.name().equals(tagged.variant())) {
                        selected = member;
                        break;
                    }
                }
                if (selected == null)
                    throw new IllegalArgumentException(
                            "unknown nested enum tag/variant");
                if (visiting.put(value, Boolean.TRUE) != null)
                    throw new IllegalArgumentException(
                            "cyclic nested enum value");
                try {
                    addDecodedBytes(64L);
                    addDecodedBytes(Math.addExact(
                            40L,
                            Math.multiplyExact(2L, nested.name().length())));
                    if (selected.payload() == null) {
                        if (tagged.payload() != null)
                            throw new IllegalArgumentException(
                                    "nullary nested enum carries payload");
                        arrayHeader(2);
                        string(nested.name());
                        signedInt(tagged.tag());
                    } else {
                        if (tagged.payload() == null)
                            throw new IllegalArgumentException(
                                    "nested enum payload is missing");
                        arrayHeader(3);
                        string(nested.name());
                        signedInt(tagged.tag());
                        value(selected.payload(), tagged.payload(), depth + 1);
                    }
                } finally {
                    visiting.remove(value);
                }
                return;
            }

            Sequence sequence = (Sequence) shape;
            if (value == null)
                throw new IllegalArgumentException("sequence cannot be null");
            int n;
            boolean list = value instanceof List<?>;
            if (list) n = ((List<?>) value).size();
            else if (value.getClass().isArray()) n = Array.getLength(value);
            else throw new IllegalArgumentException("expected sequence data");
            if (n > MAX_ELEMENTS)
                throw new IllegalArgumentException("sequence exceeds maximum elements");
            if (isFixed(sequence.kind()) && sequence.slots().size() != n) {
                throw new IllegalArgumentException("wrong fixed sequence arity");
            }
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic actor collection");
            }
            try {
                // Decoder currently builds an ArrayList and then an immutable
                // list. Reserve a conservative two-container peak.
                addDecodedBytes(Math.addExact(
                        64L,
                        Math.multiplyExact(16L, n)));
                arrayHeader(n);
                for (int i = 0; i < n; i++) {
                    Object item = list
                            ? ((List<?>) value).get(i)
                            : Array.get(value, i);
                    Shape member = isFixed(sequence.kind())
                            ? sequence.slots().get(i)
                            : sequence.element();
                    value(member, item, depth + 1);
                }
            } finally {
                visiting.remove(value);
            }
        }

        byte[] result() {
            if (size == 0) return new byte[0];
            if (size == buffer.length) {
                byte[] result = buffer;
                buffer = new byte[0];
                return result;
            }
            // Exact-size result allocation coexists briefly with the growth
            // buffer, so reserve it before copying and release old capacity
            // only afterward.
            scratch.reserve(size);
            byte[] result = Arrays.copyOf(buffer, size);
            scratch.release(buffer.length);
            buffer = new byte[0];
            return result;
        }
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
            if ((tag == 0xdc && n <= 15) || (tag == 0xdd && n <= 65535)) {
                throw new IllegalArgumentException("noncanonical MessagePack array header");
            }
            return (int) n;
        }

        byte[] binaryDigest() {
            if (u8() != 0xc4 || u8() != 32) {
                throw new IllegalArgumentException("expected MessagePack bin8 32-byte shape digest");
            }
            if (bytes.length - at < 32) {
                throw new IllegalArgumentException("truncated shape digest");
            }
            byte[] digest = Arrays.copyOfRange(bytes, at, at + 32);
            at += 32;
            return digest;
        }

        long signedInt() {
            int tag = u8();
            if (tag <= 0x7f) return tag;
            if (tag >= 0xe0) return (byte) tag;
            long result = switch (tag) {
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
            // Accept exactly the minimal representation the local encoder emits.
            boolean noncanonical = switch (tag) {
                case 0xcc -> result <= 127;
                case 0xcd -> result <= 255;
                case 0xce -> result <= 65535;
                case 0xcf -> result <= 0xffffffffL;
                case 0xd0 -> result >= -32;
                case 0xd1 -> result >= Byte.MIN_VALUE;
                case 0xd2 -> result >= Short.MIN_VALUE;
                case 0xd3 -> result >= Integer.MIN_VALUE;
                default -> false;
            };
            if (noncanonical) throw new IllegalArgumentException("noncanonical MessagePack integer");
            return result;
        }

        String string() {
            int tag = u8();
            long length;
            if ((tag & 0xe0) == 0xa0) length = tag & 0x1f;
            else if (tag == 0xd9) length = uint(1);
            else if (tag == 0xda) length = uint(2);
            else if (tag == 0xdb) length = uint(4);
            else throw new IllegalArgumentException("expected MessagePack UTF-8 string");
            if ((tag == 0xd9 && length <= 31)
                    || (tag == 0xda && length <= 255)
                    || (tag == 0xdb && length <= 65535)) {
                throw new IllegalArgumentException("noncanonical MessagePack string header");
            }
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
            if (shape instanceof NestedEnum nested) {
                int n = arrayHeader();
                if (n != 2 && n != 3)
                    throw new IllegalArgumentException("nested enum tuple must have two or three slots");
                String owner = string();
                if (!nested.name().equals(owner))
                    throw new IllegalArgumentException("nested enum owner mismatch");
                long tag = signedInt();
                EnumCaseShape selected = null;
                for (EnumCaseShape member : nested.cases()) {
                    if (member.tag() == tag) {
                        selected = member;
                        break;
                    }
                }
                if (selected == null || (selected.payload() == null ? 2 : 3) != n)
                    throw new IllegalArgumentException("unknown nested enum tag or wrong payload arity");
                Object payload = selected.payload() == null
                        ? null : value(selected.payload(), depth + 1);
                return new TaggedMessageCodec.TaggedValue(
                        nested.name(), selected.name(), selected.tag(), payload);
            }
            Sequence seq = (Sequence) shape;
            int n = arrayHeader();
            if (isFixed(seq.kind()) && n != seq.slots().size()) {
                throw new IllegalArgumentException("wrong fixed sequence arity");
            }
            // One byte is the absolute minimum MessagePack footprint per
            // value, including an empty nested array; never allocate based
            // on an untrusted count without proving sufficient bytes and
            // remaining global node quota first.
            if (n > bytes.length - at || n > MAX_NODES - nodes) {
                throw new IllegalArgumentException("sequence length exceeds remaining input/node budget");
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
