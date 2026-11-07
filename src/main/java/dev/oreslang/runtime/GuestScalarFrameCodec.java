package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pointer-free, typed argument slots for the guarded synchronous evaluator.
 *
 * <p>The codec copies exactly-representable Java primitive wrappers into
 * actor-owned frame bytes. It never serializes object graphs, references,
 * strings, collections, closures or capabilities. Such calls continue on
 * the existing JVM evaluator path and are NOT evidence of stackless lowering.
 *
 * <p>Each slot is one type tag and eight big-endian payload bytes. Do not
 * coerce values: the exact wrapper kind and floating-point bit pattern must
 * survive. No implicit conversions are introduced into the source language.
 */
public final class GuestScalarFrameCodec {
    public static final int SLOT_BYTES = 9;
    public static final int MAX_SCALAR_ARGUMENTS = 256;

    private static final byte BYTE = 1;
    private static final byte SHORT = 2;
    private static final byte INT = 3;
    private static final byte LONG = 4;
    private static final byte FLOAT = 5;
    private static final byte DOUBLE = 6;
    private static final byte BOOLEAN = 7;
    private static final byte CHAR = 8;

    private GuestScalarFrameCodec() { }

    /**
     * @return a fresh pointer-free payload, or null for an unsupported value
     *         or excessively large call; null is not a failed guest call.
     */
    public static byte[] encode(List<?> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.size() > MAX_SCALAR_ARGUMENTS) return null;
        ByteBuffer bytes = ByteBuffer.allocate(arguments.size() * SLOT_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        for (Object argument : arguments) {
            if (argument instanceof Byte value) {
                bytes.put(BYTE).putLong(value.longValue());
            } else if (argument instanceof Short value) {
                bytes.put(SHORT).putLong(value.longValue());
            } else if (argument instanceof Integer value) {
                bytes.put(INT).putLong(value.longValue());
            } else if (argument instanceof Long value) {
                bytes.put(LONG).putLong(value.longValue());
            } else if (argument instanceof Float value) {
                bytes.put(FLOAT).putLong(Float.floatToRawIntBits(value) & 0xffff_ffffL);
            } else if (argument instanceof Double value) {
                bytes.put(DOUBLE).putLong(Double.doubleToRawLongBits(value));
            } else if (argument instanceof Boolean value) {
                bytes.put(BOOLEAN).putLong(value ? 1L : 0L);
            } else if (argument instanceof Character value) {
                bytes.put(CHAR).putLong(value.charValue());
            } else {
                // Never allow a guest reference or null through this primitive ABI.
                return null;
            }
        }
        return bytes.array();
    }

    /** Reject malformed/corrupted tags and noncanonical payloads fail closed. */
    public static List<Object> decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length % SLOT_BYTES != 0
                || encoded.length / SLOT_BYTES > MAX_SCALAR_ARGUMENTS) {
            throw new IllegalArgumentException("invalid typed guest slot byte count");
        }
        ByteBuffer data = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        List<Object> values = new ArrayList<>(encoded.length / SLOT_BYTES);
        while (data.hasRemaining()) {
            byte tag = data.get();
            long bits = data.getLong();
            Object result = switch (tag) {
                case BYTE -> {
                    if (bits != (long) (byte) bits) throw invalidPayload();
                    yield (byte) bits;
                }
                case SHORT -> {
                    if (bits != (long) (short) bits) throw invalidPayload();
                    yield (short) bits;
                }
                case INT -> {
                    if (bits != (long) (int) bits) throw invalidPayload();
                    yield (int) bits;
                }
                case LONG -> bits;
                case FLOAT -> {
                    if ((bits & ~0xffff_ffffL) != 0L) throw invalidPayload();
                    yield Float.intBitsToFloat((int) bits);
                }
                case DOUBLE -> Double.longBitsToDouble(bits);
                case BOOLEAN -> {
                    if (bits != 0L && bits != 1L) throw invalidPayload();
                    yield bits == 1L;
                }
                case CHAR -> {
                    if (bits < 0L || bits > Character.MAX_VALUE) throw invalidPayload();
                    yield (char) bits;
                }
                default -> throw new IllegalArgumentException("unknown typed guest slot tag");
            };
            values.add(result);
        }
        return List.copyOf(values);
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("noncanonical typed guest slot payload");
    }
}
