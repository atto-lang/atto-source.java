package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-side, source-free prototype for a deterministic AttoLang AOT type table.
 * Only trusted, already-resolved compiler symbols may be submitted. In particular,
 * this is NOT a parser intrinsic, guest API, or proof of guest/native execution.
 * It never scans Java classes, exposes method pointers, or retains private fields.
 */
final class AotReflectionTableV1 {
    private static final byte[] MAGIC = "ATTOREF1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN = "attolang.aot-reflection/1\u0000".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_TYPES = 1_024;
    private static final int MAX_FIELDS = 256;
    private static final int MAX_GENERICS = 64;
    private static final int MAX_TEXT_BYTES = 512;

    private AotReflectionTableV1() { }

    enum Visibility { PUBLIC, PRIVATE }

    record Field(String name, String resolvedType, Visibility visibility) { }

    record ResolvedType(String packageIdentity, String resolvedSymbol,
                        List<String> genericArguments, List<Field> fields) {
        ResolvedType {
            // Reject attacker-sized collections BEFORE copying them.
            if (genericArguments == null || fields == null) {
                throw new IllegalArgumentException("null resolved type collection");
            }
            if (genericArguments.size() > MAX_GENERICS || fields.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("type exceeds metadata bounds");
            }
            try {
                genericArguments = List.copyOf(genericArguments);
                fields = List.copyOf(fields);
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("null resolved type element", e);
            }
        }
    }

    record Encoded(byte[] bytes, String sha256Hex) {
        Encoded { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    static Encoded encode(List<ResolvedType> resolvedTypes) {
        if (resolvedTypes == null || resolvedTypes.size() > MAX_TYPES) {
            throw new IllegalArgumentException("invalid reflection type count");
        }
        List<Entry> entries = new ArrayList<>(resolvedTypes.size());
        Map<String, byte[]> hashes = new HashMap<>();
        for (ResolvedType type : resolvedTypes) {
            if (type == null) throw new IllegalArgumentException("null type");
            if (type.genericArguments().size() > MAX_GENERICS || type.fields().size() > MAX_FIELDS) {
                throw new IllegalArgumentException("type exceeds metadata bounds");
            }
            byte[] identity = serializeIdentity(type);
            byte[] id = digest(identity);
            String key = HexFormat.of().formatHex(id);
            byte[] old = hashes.putIfAbsent(key, identity);
            if (old != null) {
                throw new IllegalArgumentException(Arrays.equals(old, identity)
                        ? "duplicate resolved type identity" : "type identity digest collision");
            }
            List<Field> publicFields = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Field field : type.fields()) {
                if (field == null || field.visibility() == null) {
                    throw new IllegalArgumentException("unresolved field metadata");
                }
                String fieldName = checkedText(field.name());
                checkedText(field.resolvedType());
                if (!seen.add(fieldName)) throw new IllegalArgumentException("duplicate field name");
                if (field.visibility() == Visibility.PUBLIC) publicFields.add(field);
            }
            entries.add(new Entry(id, type, List.copyOf(publicFields)));
        }
        // Unsigned byte-wise ordering provides an implementation-independent table order.
        entries.sort(Comparator.comparing(Entry::typeId, AotReflectionTableV1::compareUnsigned));
        try {
            BoundedOutputStream payloadBuffer = new BoundedOutputStream(MAX_BYTES);
            DataOutputStream out = new DataOutputStream(payloadBuffer);
            for (Entry entry : entries) {
                out.write(entry.typeId());
                writeText(out, entry.type().packageIdentity());
                writeText(out, entry.type().resolvedSymbol());
                out.writeInt(entry.type().genericArguments().size());
                for (String arg : entry.type().genericArguments()) writeText(out, arg);
                out.writeInt(entry.publicFields().size());
                for (Field field : entry.publicFields()) {
                    writeText(out, field.name());
                    writeText(out, field.resolvedType());
                }
            }
            out.flush();
            byte[] payload = payloadBuffer.toByteArray();
            BoundedOutputStream fullBuffer = new BoundedOutputStream(MAX_BYTES);
            DataOutputStream header = new DataOutputStream(fullBuffer);
            header.write(MAGIC);
            header.writeInt(1);
            header.writeInt(entries.size());
            header.writeInt(payload.length);
            header.write(digest(payload));
            header.write(payload);
            header.flush();
            byte[] result = fullBuffer.toByteArray();
            return new Encoded(result, HexFormat.of().formatHex(digest(result)));
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException(impossibleForMemoryStream);
        }
    }

    private record Entry(byte[] typeId, ResolvedType type, List<Field> publicFields) { }

    private static byte[] serializeIdentity(ResolvedType type) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(DOMAIN);
            writeText(out, type.packageIdentity());
            writeText(out, type.resolvedSymbol());
            out.writeInt(type.genericArguments().size());
            for (String arg : type.genericArguments()) writeText(out, arg);
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException(impossibleForMemoryStream);
        }
    }

    private static String checkedText(String text) {
        if (text == null || text.isBlank() || !Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("empty or noncanonical resolved metadata text");
        }
        // Validate Unicode scalar values, not UTF-16 code units: bidi isolates,
        // zero-width marks and noncharacters must never enter signed/AOT metadata.
        for (int i = 0; i < text.length();) {
            char ch = text.charAt(i);
            if (Character.isLowSurrogate(ch) ||
                    (Character.isHighSurrogate(ch) &&
                     (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))))) {
                throw new IllegalArgumentException("malformed UTF-16 metadata text");
            }
            int codePoint = text.codePointAt(i);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT ||
                    (codePoint >= 0xFDD0 && codePoint <= 0xFDEF) ||
                    (codePoint & 0xFFFE) == 0xFFFE) {
                throw new IllegalArgumentException("unsafe metadata text");
            }
            i += Character.charCount(codePoint);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("metadata text exceeds bound");
        }
        return text;
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = checkedText(value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int max;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private BoundedOutputStream(int max) { this.max = max; }
        @Override public void write(int value) {
            requireSpace(1);
            output.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) {
            if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException("invalid byte slice");
            }
            requireSpace(length);
            output.write(bytes, offset, length);
        }
        private void requireSpace(int length) {
            if (length > max - output.size()) throw new IllegalArgumentException("AOT metadata exceeds byte budget");
        }
        private byte[] toByteArray() { return output.toByteArray(); }
    }
}
