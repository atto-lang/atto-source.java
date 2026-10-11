package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, canonical, owned actor message transport. This is a host/runtime
 * codec substrate, not permission for the source compiler to emit unverified
 * actor sends. The compiler must derive a stable schema id and variant id and
 * prove the exact declared ActorMail domain before calling encode.
 *
 * Every decode builds a detached, immutable value tree. No mutable sender
 * array, ByteBuffer, class instance, ActorRef or capability crosses the lane.
 */
public final class ActorMessageWireV1 {
    private ActorMessageWireV1() { }

    public static final int HEADER_SIZE = 24;
    public static final int MAX_FRAME_BYTES = 1 << 20;
    public static final int MAX_NODES = 4096;
    public static final int MAX_DEPTH = 64;
    public static final int TAG_BOOL = 1;
    public static final int TAG_INT64 = 2;
    public static final int TAG_STRING = 3;
    public static final int TAG_TUPLE = 4;
    public static final int TAG_ARRAY = 5;
    public static final int TAG_LIST = 6;

    public sealed interface Value
            permits BoolValue, IntValue, StringValue, TupleValue, ArrayValue, ListValue { }

    public record BoolValue(boolean value) implements Value { }
    public record IntValue(long value) implements Value { }
    public record StringValue(String value) implements Value {
        public StringValue { Objects.requireNonNull(value, "value"); }
    }
    public record TupleValue(List<Value> values) implements Value {
        public TupleValue { values = List.copyOf(values); }
    }
    public record ArrayValue(List<Value> values) implements Value {
        public ArrayValue { values = List.copyOf(values); }
    }
    public record ListValue(List<Value> values) implements Value {
        public ListValue { values = List.copyOf(values); }
    }
    public record Decoded(long schemaId, int variantId, Value value) { }

    public static byte[] encode(long schemaId, int variantId, Value value) {
        requireIdentity(schemaId, variantId);
        Objects.requireNonNull(value, "value");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeNode(payload, value, 0, new int[] {0});
        byte[] body = payload.toByteArray();
        if (body.length > MAX_FRAME_BYTES - HEADER_SIZE) {
            throw invalid("message exceeds maximum frame size");
        }
        ByteBuffer frame = ByteBuffer.allocate(HEADER_SIZE + body.length)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put((byte) 'O').put((byte) 'M').put((byte) 'S').put((byte) 'G');
        frame.put((byte) 1).put((byte) 0).putShort((short) 0);
        frame.putLong(schemaId).putInt(variantId).putInt(body.length);
        frame.put(body);
        return frame.array();
    }

    public static Decoded decode(byte[] input, long expectedSchema, int expectedVariant) {
        Objects.requireNonNull(input, "input");
        requireIdentity(expectedSchema, expectedVariant);
        if (input.length < HEADER_SIZE || input.length > MAX_FRAME_BYTES) {
            throw invalid("invalid message frame size");
        }
        ByteBuffer source = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN);
        if (source.get() != 'O' || source.get() != 'M'
                || source.get() != 'S' || source.get() != 'G'
                || source.get() != 1 || source.get() != 0 || source.getShort() != 0) {
            throw invalid("unknown or unsupported wire frame");
        }
        long schema = source.getLong();
        int variant = source.getInt();
        int length = source.getInt();
        // The receiver checks BOTH identities before touching the payload.
        if (schema != expectedSchema || variant != expectedVariant) {
            throw invalid("actor message schema/variant mismatch");
        }
        if (length != source.remaining()) {
            throw invalid("noncanonical payload length");
        }
        Value value = readNode(source, 0, new int[] {0});
        if (source.hasRemaining()) {
            throw invalid("trailing payload bytes");
        }
        return new Decoded(schema, variant, value);
    }

    private static void requireIdentity(long schema, int variant) {
        if (schema == 0 || variant == 0) {
            throw invalid("schema and variant ids must be nonzero");
        }
    }

    private static void visit(int depth, int[] count) {
        if (depth >= MAX_DEPTH || ++count[0] > MAX_NODES) {
            throw invalid("message nesting or node limit exceeded");
        }
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 255);
        out.write((value >>> 16) & 255);
        out.write((value >>> 8) & 255);
        out.write(value & 255);
    }

    private static void writeItems(ByteArrayOutputStream out, int tag,
                                   List<Value> items, int depth, int[] count) {
        if (items.size() > MAX_NODES) throw invalid("collection count exceeds limit");
        out.write(tag);
        putInt(out, items.size());
        for (Value item : items) writeNode(out, item, depth + 1, count);
    }

    private static void writeNode(ByteArrayOutputStream out, Value value,
                                  int depth, int[] count) {
        visit(depth, count);
        if (value instanceof BoolValue b) {
            out.write(TAG_BOOL);
            out.write(b.value() ? 1 : 0);
        } else if (value instanceof IntValue n) {
            out.write(TAG_INT64);
            long bits = n.value();
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (bits >>> shift) & 255);
            }
        } else if (value instanceof StringValue t) {
            byte[] bytes = strictUtf8(t.value());
            if (bytes.length > MAX_FRAME_BYTES - HEADER_SIZE - 5) {
                throw invalid("string too large");
            }
            out.write(TAG_STRING);
            putInt(out, bytes.length);
            out.writeBytes(bytes);
        } else if (value instanceof TupleValue t) {
            writeItems(out, TAG_TUPLE, t.values(), depth, count);
        } else if (value instanceof ArrayValue a) {
            writeItems(out, TAG_ARRAY, a.values(), depth, count);
        } else if (value instanceof ListValue l) {
            writeItems(out, TAG_LIST, l.values(), depth, count);
        } else {
            throw invalid("unsupported message value");
        }
        if (out.size() > MAX_FRAME_BYTES - HEADER_SIZE) {
            throw invalid("message exceeds maximum frame size");
        }
    }

    private static Value readNode(ByteBuffer input, int depth, int[] count) {
        visit(depth, count);
        if (!input.hasRemaining()) throw invalid("truncated value tag");
        int tag = Byte.toUnsignedInt(input.get());
        if (tag == TAG_BOOL) {
            requireBytes(input, 1);
            int b = Byte.toUnsignedInt(input.get());
            if (b > 1) throw invalid("noncanonical boolean");
            return new BoolValue(b == 1);
        }
        if (tag == TAG_INT64) {
            requireBytes(input, 8);
            return new IntValue(input.getLong());
        }
        if (tag == TAG_STRING) {
            requireBytes(input, 4);
            long size = Integer.toUnsignedLong(input.getInt());
            if (size > input.remaining()) throw invalid("truncated string");
            byte[] bytes = new byte[(int) size];
            input.get(bytes);
            return new StringValue(strictText(bytes));
        }
        if (tag == TAG_TUPLE || tag == TAG_ARRAY || tag == TAG_LIST) {
            requireBytes(input, 4);
            long size = Integer.toUnsignedLong(input.getInt());
            if (size > MAX_NODES || size > input.remaining()) {
                throw invalid("invalid collection length");
            }
            List<Value> items = new ArrayList<>((int) size);
            for (int i = 0; i < size; i++) {
                items.add(readNode(input, depth + 1, count));
            }
            if (tag == TAG_TUPLE) return new TupleValue(items);
            if (tag == TAG_ARRAY) return new ArrayValue(items);
            return new ListValue(items);
        }
        throw invalid("unknown value tag");
    }

    private static void requireBytes(ByteBuffer input, int count) {
        if (input.remaining() < count) throw invalid("truncated message value");
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] copy = new byte[bytes.remaining()];
            bytes.get(copy);
            return copy;
        } catch (CharacterCodingException e) {
            throw invalid("invalid UTF-8 source text");
        }
    }

    private static String strictText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("invalid UTF-8 payload");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("actor wire v1: " + reason);
    }
}
