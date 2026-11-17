package dev.oreslang.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strict readback for compiler-owned AttoLang AOT descriptor prototypes.
 * This is NOT a guest reflection intrinsic, a loader, or an authorization API.
 * Rejects malformed or noncanonical tables before producing immutable descriptors.
 */
final class AotReflectionTableV1Reader {
    private static final byte[] MAGIC = "ATTOREF1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN = "attolang.aot-reflection/1\u0000".getBytes(StandardCharsets.UTF_8);
    private static final int HEADER_BYTES = 52;
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_TYPES = 1_024;
    private static final int MAX_FIELDS = 256;
    private static final int MAX_GENERICS = 64;
    private static final int MAX_TEXT_BYTES = 512;

    private AotReflectionTableV1Reader() { }

    record FieldDescriptor(String name, String resolvedType) { }
    record TypeDescriptor(String typeIdHex, String packageIdentity, String resolvedSymbol,
                          List<String> genericArguments, List<FieldDescriptor> publicFields) {
        TypeDescriptor {
            genericArguments = List.copyOf(genericArguments);
            publicFields = List.copyOf(publicFields);
        }
    }
    record Table(List<TypeDescriptor> types) {
        Table { types = List.copyOf(types); }
        Optional<TypeDescriptor> findByTypeId(String typeIdHex) {
            if (typeIdHex == null || typeIdHex.length() != 64) return Optional.empty();
            for (int i = 0; i < 64; i++) {
                char digit = typeIdHex.charAt(i);
                if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f'))) {
                    return Optional.empty();
                }
            }
            // Bounded binary lookup, with no stream/lambda allocation or dynamic dispatch.
            // Canonical lowercase hex has the same order as unsigned type-ID bytes.
            int low = 0;
            int high = types.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                TypeDescriptor candidate = types.get(middle);
                int relation = candidate.typeIdHex().compareTo(typeIdHex);
                if (relation == 0) return Optional.of(candidate);
                if (relation < 0) low = middle + 1;
                else high = middle - 1;
            }
            return Optional.empty();
        }
    }

    static Table decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES || encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("invalid descriptor table size");
        }
        // Snapshot caller input once: a racing mutation must not bypass digest verification.
        byte[] stable = encoded.clone();
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(stable));
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw invalid("invalid descriptor magic");
            if (input.readInt() != 1) throw invalid("unsupported descriptor version");
            int count = input.readInt();
            if (count < 0 || count > MAX_TYPES) throw invalid("invalid descriptor type count");
            int payloadBytes = input.readInt();
            if (payloadBytes < 0 || payloadBytes != stable.length - HEADER_BYTES) {
                throw invalid("invalid descriptor payload size");
            }
            byte[] expectedHash = new byte[32];
            input.readFully(expectedHash);
            byte[] payload = new byte[payloadBytes];
            input.readFully(payload);
            if (input.available() != 0 || !MessageDigest.isEqual(expectedHash, digest(payload))) {
                throw invalid("descriptor checksum mismatch");
            }
            DataInputStream reader = new DataInputStream(new ByteArrayInputStream(payload));
            List<TypeDescriptor> types = new ArrayList<>(count);
            byte[] previousId = null;
            for (int i = 0; i < count; i++) {
                byte[] id = new byte[32];
                reader.readFully(id);
                if (previousId != null && compareUnsigned(previousId, id) >= 0) {
                    throw invalid("descriptor entries out of canonical order or duplicated");
                }
                String pkg = readText(reader);
                String symbol = readText(reader);
                int genericCount = reader.readInt();
                if (genericCount < 0 || genericCount > MAX_GENERICS) throw invalid("invalid generic count");
                List<String> generics = new ArrayList<>(genericCount);
                for (int g = 0; g < genericCount; g++) generics.add(readText(reader));
                if (!MessageDigest.isEqual(id, identityHash(pkg, symbol, generics))) {
                    throw invalid("descriptor type identity mismatch");
                }
                int fieldCount = reader.readInt();
                if (fieldCount < 0 || fieldCount > MAX_FIELDS) throw invalid("invalid public field count");
                List<FieldDescriptor> fields = new ArrayList<>(fieldCount);
                Set<String> seen = new HashSet<>();
                for (int f = 0; f < fieldCount; f++) {
                    String name = readText(reader);
                    String resolvedType = readText(reader);
                    if (!seen.add(name)) throw invalid("duplicate public field");
                    fields.add(new FieldDescriptor(name, resolvedType));
                }
                types.add(new TypeDescriptor(HexFormat.of().formatHex(id), pkg, symbol, generics, fields));
                previousId = id;
            }
            if (reader.available() != 0) throw invalid("trailing descriptor payload data");
            return new Table(types);
        } catch (IOException failure) {
            throw new IllegalArgumentException("truncated descriptor table", failure);
        }
    }

    private static String readText(DataInputStream reader) throws IOException {
        int length = reader.readInt();
        if (length <= 0 || length > MAX_TEXT_BYTES || length > reader.available()) {
            throw invalid("invalid metadata text length");
        }
        byte[] utf8 = new byte[length];
        reader.readFully(utf8);
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("malformed metadata UTF-8", failure);
        }
        if (value.isBlank() || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw invalid("noncanonical metadata text");
        }
        for (int offset = 0; offset < value.length();) {
            int ch = value.codePointAt(offset);
            if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT ||
                    (ch >= 0xFDD0 && ch <= 0xFDEF) || (ch & 0xFFFE) == 0xFFFE) {
                throw invalid("unsafe metadata Unicode");
            }
            offset += Character.charCount(ch);
        }
        return value;
    }

    private static byte[] identityHash(String pkg, String symbol, List<String> args) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(DOMAIN);
            writeIdentityText(out, pkg);
            writeIdentityText(out, symbol);
            out.writeInt(args.size());
            for (String arg : args) writeIdentityText(out, arg);
            out.flush();
            return digest(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeIdentityText(DataOutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int result = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (result != 0) return result;
        }
        return Integer.compare(a.length, b.length);
    }

    private static IllegalArgumentException invalid(String explanation) {
        return new IllegalArgumentException(explanation);
    }
}
