package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic guest error wire encoding (no host stacks, Java Throwable
 * objects, executable closures, or ambient serialization/reflection).
 * Wire schema version 1; protobuf field numbers are immutable.
 */
public final class GuestErrorWireCodec {
    private static final int MAX_MESSAGE = 4096;
    private static final int MAX_FRAMES = 64;
    private static final int MAX_FRAME_TEXT = 256;

    private GuestErrorWireCodec() { }

    /**
     * Strict value-only diagnostic snapshot. This is not an actor CONTROL
     * envelope and conveys no supervision authority or runtime identity.
     * No arbitrary guest object, method, closure or mutable map is retained.
     */
    public record ErrorSnapshot(String name, int code, String message,
                                List<String> frames, boolean full) {
        public ErrorSnapshot {
            // Validation is shared by all local/wire codecs. No guest code.
            validate(name, code, message, frames);
            frames = List.copyOf(frames);
            if (!full && (!message.isEmpty() || !frames.isEmpty())) {
                throw new IllegalArgumentException(
                        "compact actor diagnostic cannot retain message or trace");
            }
        }

        public String toJson() {
            return GuestErrorWireCodec.json(name, code, message, frames, full);
        }

        public List<Long> toMessagePack() {
            return GuestErrorWireCodec.messagePack(name, code, message, frames, full);
        }

        public List<Long> toProtoBuf() {
            return GuestErrorWireCodec.protoBuf(name, code, message, frames, full);
        }
    }

    /**
     * Default actor -> supervisor wire diagnostic. Runtime must provide a
     * trusted nominal class name and catalog code; the fields argument is
     * intentionally NEVER read, enumerated or rendered. It may contain
     * methods/closures, foreign handles, actor refs or cyclic structures.
     * Those have no presence in the resulting snapshot. Caller-defined
     * full_trace/json_full/binary_full flags have zero effect here.
     *
     * The result is DIAGNOSTIC DATA ONLY. Only the ActorRuntime's protected
     * CONTROL scheduler can create authoritative supervisor notices.
     */
    public static ErrorSnapshot supervisorCompactSnapshot(
            String trustedNominalName, int trustedCatalogCode,
            java.util.Map<String, ?> ignoredGuestFields) {
        java.util.Objects.requireNonNull(ignoredGuestFields, "guest fields");
        return new ErrorSnapshot(trustedNominalName, trustedCatalogCode,
                "", List.of(), false);
    }

    /**
     * Host-authorized local diagnostics only; never the default supervisor
     * transfer path. Read exactly two allowlisted scalar/array fields without
     * ever invoking code(), getters, toString(), reflection or other guest
     * callables, even if unknown subclass fields contain closures.
     * The trusted caller must already have validated nominal Error identity.
     */
    public static ErrorSnapshot trustedLocalSnapshot(
            String trustedNominalName, int trustedCatalogCode,
            java.util.Map<String, ?> guestFields) {
        java.util.Objects.requireNonNull(guestFields, "guest fields");
        Object message = guestFields.get("message");
        Object trace = guestFields.get("stack");
        if (!(message instanceof String safeMessage) || !(trace instanceof List<?> rawFrames)) {
            throw new IllegalArgumentException("trusted error snapshot requires String message and frame array");
        }
        if (rawFrames.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("too many guest error frames");
        }
        List<String> safeFrames = new ArrayList<>(rawFrames.size());
        for (Object raw : rawFrames) {
            if (!(raw instanceof String frame)) {
                // Do NOT coerce unknown values via Object.toString().
                throw new IllegalArgumentException(
                        "guest error stack contains non-string or executable value");
            }
            safeFrames.add(frame);
        }
        return new ErrorSnapshot(trustedNominalName, trustedCatalogCode,
                safeMessage, safeFrames, true);
    }

    private static void validate(String name, int code, String message, List<String> frames) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")
                || code < 0 || message == null || message.length() > MAX_MESSAGE
                || frames == null || frames.size() > MAX_FRAMES)
            throw new IllegalArgumentException("invalid bounded guest error encoding");
        validateUnicode(message);
        for (String frame : frames) {
            if (frame == null || frame.length() > MAX_FRAME_TEXT)
                throw new IllegalArgumentException("invalid guest trace frame length");
            validateUnicode(frame);
        }
    }

    /** Reject unpaired UTF-16 surrogates instead of allowing JSON/binary divergence. */
    private static void validateUnicode(String source) {
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == source.length() || !Character.isLowSurrogate(source.charAt(i + 1)))
                    throw new IllegalArgumentException("invalid UTF-16 in guest error");
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid UTF-16 in guest error");
            }
        }
    }

    public static String text(String name, int code, String message, List<String> frames) {
        validate(name, code, message, frames);
        StringBuilder result = new StringBuilder(name).append(" (").append(code).append(")");
        if (!message.isEmpty()) result.append(": ").append(message);
        for (String frame : frames) result.append("\n  at ").append(frame);
        return result.toString();
    }

    public static String json(String name, int code, String message,
                              List<String> frames, boolean full) {
        validate(name, code, message, frames);
        StringBuilder result = new StringBuilder(128);
        result.append("{\"schema\":1,\"code\":").append(code)
                .append(",\"name\":");
        appendJsonString(result, name);
        if (full) {
            result.append(",\"message\":");
            appendJsonString(result, message);
            result.append(",\"stack\":[");
            for (int i = 0; i < frames.size(); i++) {
                if (i != 0) result.append(',');
                appendJsonString(result, frames.get(i));
            }
            result.append(']');
        }
        return result.append('}').toString();
    }

    private static void appendJsonString(StringBuilder result, String value) {
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20)
                        result.append("\\u")
                                .append(Character.forDigit((c >>> 12) & 15, 16))
                                .append(Character.forDigit((c >>> 8) & 15, 16))
                                .append(Character.forDigit((c >>> 4) & 15, 16))
                                .append(Character.forDigit(c & 15, 16));
                    else result.append(c);
                }
            }
        }
        result.append('"');
    }

    /** Valid MessagePack v1 map: schema/code/name/message/stack. */
    public static List<Long> messagePack(String name, int code, String message, List<String> frames) {
        return messagePack(name, code, message, frames, true);
    }

    public static List<Long> messagePack(String name, int code, String message, List<String> frames, boolean full) {
        validate(name, code, message, frames);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(full ? 0x85 : 0x83);
        writeMsgString(out, "schema");
        writeMsgInt(out, 1);
        writeMsgString(out, "code");
        writeMsgInt(out, code);
        writeMsgString(out, "name");
        writeMsgString(out, name);
        if (full) {
            writeMsgString(out, "message");
            writeMsgString(out, message);
            writeMsgString(out, "stack");
            if (frames.size() <= 15) out.write(0x90 | frames.size());
            else { out.write(0xdc); writeU16(out, frames.size()); }
            for (String frame : frames) writeMsgString(out, frame);
        }
        return unsignedBytes(out);
    }

    private static void writeMsgInt(ByteArrayOutputStream out, int value) {
        if (value <= 127) out.write(value);
        else if (value <= 0xff) { out.write(0xcc); out.write(value); }
        else if (value <= 0xffff) { out.write(0xcd); writeU16(out, value); }
        else { out.write(0xce); writeU32(out, value); }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8); out.write(value);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24); out.write(value >>> 16);
        out.write(value >>> 8); out.write(value);
    }

    private static void writeMsgString(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 31) out.write(0xa0 | bytes.length);
        else if (bytes.length <= 0xff) { out.write(0xd9); out.write(bytes.length); }
        else if (bytes.length <= 0xffff) { out.write(0xda); writeU16(out, bytes.length); }
        else { out.write(0xdb); writeU32(out, bytes.length); }
        out.writeBytes(bytes);
    }

    /** Wire-compatible with contracts/v1/guest-error.proto. */
    public static List<Long> protoBuf(String name, int code, String message, List<String> frames) {
        return protoBuf(name, code, message, frames, true);
    }

    public static List<Long> protoBuf(String name, int code, String message, List<String> frames, boolean full) {
        validate(name, code, message, frames);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, 8); writeVarint(out, 1);  // schema=1, field 1
        writeVarint(out, 16); writeVarint(out, code); // code, field 2
        writeProtoString(out, 3, name);
        if (full) {
            writeProtoString(out, 4, message);
            for (String frame : frames) writeProtoString(out, 5, frame);
        }
        return unsignedBytes(out);
    }

    private static void writeProtoString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static List<Long> unsignedBytes(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        List<Long> result = new ArrayList<>(bytes.length);
        for (byte b : bytes) result.add((long) (b & 0xff));
        return result;
    }
}
