package dev.oreslang.nodes;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-private, deterministic source-actor persistent-state codec v1.
 *
 * V1 intentionally supports only int, bool and string fields. Unsupported
 * graphs fail closed rather than silently serializing JVM identities,
 * capabilities, actor refs, closures or native handles.
 */
final class SourceActorCheckpointCodecV1 {
    private static final byte[] MAGIC = "ORESAS01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 1_048_576;

    record Field(String name, String type) {
        Field {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("blank field name");
            if (!List.of("int", "bool", "string").contains(type))
                throw new IllegalArgumentException("unsupported actor checkpoint field type: " + type);
        }
    }

    private SourceActorCheckpointCodecV1() { }

    static String digest(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String schemaSha256(String actorTypeName, List<Field> fields) {
        if (actorTypeName == null || actorTypeName.isBlank())
            throw new IllegalArgumentException("blank actor type");
        StringBuilder canonical = new StringBuilder("ores-source-actor-state-v1\n")
                .append(actorTypeName).append('\n');
        for (Field field : fields) {
            canonical.append(field.name()).append(':').append(field.type()).append('\n');
        }
        return digest(canonical.toString());
    }

    static byte[] encode(List<Field> fields, Map<String, Object> values) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(values, "values");
        if (values.size() != fields.size())
            throw new SecurityException("source actor state contains hidden/missing fields");
        for (Field field : fields) {
            if (!values.containsKey(field.name()))
                throw new SecurityException("source actor state missing field: " + field.name());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeU32(out, fields.size());
        for (Field field : fields) {
            byte[] name = field.name().getBytes(StandardCharsets.UTF_8);
            if (name.length > 65_535) throw new IllegalArgumentException("actor field name too long");
            writeU16(out, name.length);
            out.writeBytes(name);
            Object value = values.get(field.name());
            switch (field.type()) {
                case "int" -> {
                    out.write(1);
                    writeI64(out, exactInt64(value, field.name()));
                }
                case "bool" -> {
                    if (!(value instanceof Boolean flag))
                        throw new SecurityException("actor bool field has invalid runtime value: " + field.name());
                    out.write(2);
                    out.write(flag ? 1 : 0);
                }
                case "string" -> {
                    if (!(value instanceof String text))
                        throw new SecurityException("actor string field has invalid runtime value: " + field.name());
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    out.write(3);
                    writeU32(out, bytes.length);
                    out.writeBytes(bytes);
                }
                default -> throw new AssertionError(field.type());
            }
            if (out.size() > MAX_BYTES)
                throw new IllegalArgumentException("actor checkpoint state exceeds 1 MiB");
        }
        return out.toByteArray();
    }

    static LinkedHashMap<String, Object> decode(List<Field> expected, byte[] bytes) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES || bytes.length < MAGIC.length + 4)
            throw new IllegalArgumentException("invalid actor checkpoint state size");
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (byte wanted : MAGIC) {
            if (!in.hasRemaining() || in.get() != wanted)
                throw new IllegalArgumentException("invalid source actor checkpoint magic");
        }
        long count = Integer.toUnsignedLong(readI32(in));
        if (count != expected.size())
            throw new SecurityException("source actor checkpoint field count mismatch");
        LinkedHashMap<String, Object> restored = new LinkedHashMap<>();
        for (Field field : expected) {
            int nameSize = Short.toUnsignedInt(readI16(in));
            if (nameSize > in.remaining())
                throw new IllegalArgumentException("truncated actor field name");
            byte[] nameBytes = new byte[nameSize];
            in.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            if (!name.equals(field.name()))
                throw new SecurityException("source actor checkpoint field order/name mismatch");
            if (!in.hasRemaining()) throw new IllegalArgumentException("missing actor field type tag");
            int tag = Byte.toUnsignedInt(in.get());
            Object value = switch (field.type()) {
                case "int" -> {
                    if (tag != 1 || in.remaining() < 8)
                        throw new SecurityException("source actor int field tag/size mismatch");
                    yield in.getLong();
                }
                case "bool" -> {
                    if (tag != 2 || !in.hasRemaining())
                        throw new SecurityException("source actor bool field tag/size mismatch");
                    int bit = Byte.toUnsignedInt(in.get());
                    if (bit > 1) throw new SecurityException("invalid source actor bool encoding");
                    yield bit == 1;
                }
                case "string" -> {
                    if (tag != 3 || in.remaining() < 4)
                        throw new SecurityException("source actor string field tag/size mismatch");
                    long length = Integer.toUnsignedLong(in.getInt());
                    if (length > in.remaining())
                        throw new IllegalArgumentException("truncated source actor string");
                    byte[] text = new byte[(int) length];
                    in.get(text);
                    yield strictUtf8(text);
                }
                default -> throw new AssertionError(field.type());
            };
            restored.put(field.name(), value);
        }
        if (in.hasRemaining())
            throw new SecurityException("trailing bytes in source actor checkpoint state");
        return restored;
    }

    private static long exactInt64(Object value, String field) {
        if (value instanceof Byte v) return v.longValue();
        if (value instanceof Short v) return v.longValue();
        if (value instanceof Integer v) return v.longValue();
        if (value instanceof Long v) return v;
        if (value instanceof BigInteger v) {
            try {
                return v.longValueExact();
            } catch (ArithmeticException overflow) {
                throw new SecurityException(
                        "actor int field exceeds signed int64 checkpoint range: " + field,
                        overflow);
            }
        }
        throw new SecurityException(
                "actor int field has non-integral runtime value: " + field);
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new SecurityException(
                    "source actor checkpoint contains noncanonical UTF-8",
                    malformed);
        }
    }

    private static short readI16(ByteBuffer in) {
        if (in.remaining() < 2) throw new IllegalArgumentException("truncated actor checkpoint");
        return in.getShort();
    }

    private static int readI32(ByteBuffer in) {
        if (in.remaining() < 4) throw new IllegalArgumentException("truncated actor checkpoint");
        return in.getInt();
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeI64(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8)
            out.write((int) (value >>> shift) & 0xff);
    }
}
