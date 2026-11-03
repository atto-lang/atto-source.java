package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Source-free, host-only regression harness. Not a compiled AttoLang guest test. */
public final class AotReflectionTableV1ReaderSmoke {
    private static final byte[] DOMAIN = "attolang.aot-reflection/1\u0000".getBytes(StandardCharsets.UTF_8);
    private static int checks;
    private AotReflectionTableV1ReaderSmoke() { }

    private static void good(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("failed reader check " + checks);
    }

    private static void deny(byte[] bytes) {
        checks++;
        try {
            AotReflectionTableV1Reader.decode(bytes);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid descriptor accepted at check " + checks);
    }

    private static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] entry(String name, List<String[]> fields) throws IOException {
        ByteArrayOutputStream identityBytes = new ByteArrayOutputStream();
        DataOutputStream identity = new DataOutputStream(identityBytes);
        identity.write(DOMAIN);
        writeText(identity, "pkg@revision");
        writeText(identity, name);
        identity.writeInt(0);
        ByteArrayOutputStream rowBytes = new ByteArrayOutputStream();
        DataOutputStream row = new DataOutputStream(rowBytes);
        row.write(hash(identityBytes.toByteArray()));
        writeText(row, "pkg@revision");
        writeText(row, name);
        row.writeInt(0);
        row.writeInt(fields.size());
        for (String[] f : fields) {
            writeText(row, f[0]);
            writeText(row, f[1]);
        }
        return rowBytes.toByteArray();
    }

    private static byte[] envelope(byte[] payload, int entries) throws IOException {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outBytes);
        out.write("ATTOREF1".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(1);
        out.writeInt(entries);
        out.writeInt(payload.length);
        out.write(hash(payload));
        out.write(payload);
        return outBytes.toByteArray();
    }

    private static byte[] valid() throws IOException {
        return envelope(entry("Person", List.<String[]>of(new String[]{"visible", "core.int"})), 1);
    }

    private static byte[] mutate(byte[] original, int position, int value) {
        byte[] bad = original.clone();
        bad[position] = (byte) value;
        return bad;
    }

    private static byte[] withInt(byte[] original, int position, int value) {
        byte[] bad = original.clone();
        bad[position] = (byte) (value >>> 24);
        bad[position + 1] = (byte) (value >>> 16);
        bad[position + 2] = (byte) (value >>> 8);
        bad[position + 3] = (byte) value;
        return bad;
    }

    private static byte[] repaired(byte[] bad) {
        byte[] result = bad.clone();
        byte[] digest = hash(Arrays.copyOfRange(result, 52, result.length));
        System.arraycopy(digest, 0, result, 20, digest.length);
        return result;
    }

    public static void main(String[] args) throws Exception {
        byte[] encoded = valid();
        var table = AotReflectionTableV1Reader.decode(encoded);
        good(table.types().size() == 1);
        var type = table.types().get(0);
        good(type.resolvedSymbol().equals("Person"));
        good(type.packageIdentity().equals("pkg@revision"));
        good(type.genericArguments().isEmpty());
        good(type.publicFields().size() == 1);
        good(type.publicFields().get(0).name().equals("visible"));
        good(type.publicFields().get(0).resolvedType().equals("core.int"));
        good(table.findByTypeId(type.typeIdHex()).orElseThrow().equals(type));
        good(table.findByTypeId("0".repeat(64)).isEmpty());
        good(table.findByTypeId("\u2066" + type.typeIdHex()).isEmpty());
        try { table.types().clear(); throw new AssertionError("mutable type list"); }
        catch (UnsupportedOperationException expected) { checks++; }
        try { type.publicFields().clear(); throw new AssertionError("mutable field list"); }
        catch (UnsupportedOperationException expected) { checks++; }
        encoded[52] = 0;
        good(table.types().get(0).equals(type));
        encoded = valid();
        deny(null);
        deny(new byte[0]);
        deny(new byte[1_048_577]);
        deny(Arrays.copyOf(encoded, 51));
        deny(Arrays.copyOf(encoded, encoded.length - 1));
        deny(mutate(encoded, 0, 'X'));
        deny(withInt(encoded, 8, 2));
        deny(withInt(encoded, 12, -1));
        deny(withInt(encoded, 12, 1_025));
        deny(withInt(encoded, 12, 2));
        deny(withInt(encoded, 16, -1));
        deny(withInt(encoded, 16, Integer.MAX_VALUE));
        deny(mutate(encoded, 20, 0));
        deny(repaired(mutate(encoded, 52, 0))); // type ID forged despite valid payload digest
        // Structure offsets in the one-row, zero-generics fixture.
        int pkgLen = "pkg@revision".getBytes(StandardCharsets.UTF_8).length;
        int nameLen = "Person".getBytes(StandardCharsets.UTF_8).length;
        int genericOffset = 52 + 32 + 4 + pkgLen + 4 + nameLen;
        int fieldCountOffset = genericOffset + 4;
        deny(repaired(withInt(encoded, genericOffset, -1)));
        deny(repaired(withInt(encoded, genericOffset, 65)));
        deny(repaired(withInt(encoded, fieldCountOffset, -1)));
        deny(repaired(withInt(encoded, fieldCountOffset, 257)));
        int publicNameLenOffset = fieldCountOffset + 4;
        deny(repaired(withInt(encoded, publicNameLenOffset, Integer.MAX_VALUE)));
        deny(repaired(withInt(encoded, publicNameLenOffset, -1)));
        deny(repaired(mutate(encoded, publicNameLenOffset + 4, 0xFF))); // malformed UTF-8
        deny(envelope(entry("Person", List.<String[]>of(new String[]{"visible\u2066", "core.int"})), 1));
        deny(envelope(entry("Person", List.<String[]>of(new String[]{"visible", "core.int"}, new String[]{"visible", "core.int"})), 1));
        deny(envelope(entry("Person", List.of()), 0)); // extra trailing record
        deny(envelope(new byte[]{1, 2, 3}, 0)); // unconsumed bytes
        byte[] row1 = entry("Person", List.of());
        byte[] row2 = entry("Other", List.of());
        byte[] canonical = new byte[row1.length + row2.length];
        int order = Arrays.compareUnsigned(Arrays.copyOfRange(row1, 0, 32), Arrays.copyOfRange(row2, 0, 32));
        byte[] first = order < 0 ? row1 : row2;
        byte[] second = order < 0 ? row2 : row1;
        System.arraycopy(first, 0, canonical, 0, first.length);
        System.arraycopy(second, 0, canonical, first.length, second.length);
        var twoTypes = AotReflectionTableV1Reader.decode(envelope(canonical, 2));
        good(twoTypes.types().size() == 2);
        good(twoTypes.findByTypeId(twoTypes.types().get(0).typeIdHex()).isPresent());
        good(twoTypes.findByTypeId(twoTypes.types().get(1).typeIdHex()).isPresent());
        byte[] noncanonical = new byte[canonical.length];
        System.arraycopy(second, 0, noncanonical, 0, second.length);
        System.arraycopy(first, 0, noncanonical, second.length, first.length);
        deny(envelope(noncanonical, 2));
        byte[] duplicate = new byte[row1.length * 2];
        System.arraycopy(row1, 0, duplicate, 0, row1.length);
        System.arraycopy(row1, 0, duplicate, row1.length, row1.length);
        deny(envelope(duplicate, 2));
        good(AotReflectionTableV1Reader.decode(envelope(new byte[0], 0)).types().isEmpty());
        Random random = new Random(0xA770);
        for (int i = 0; i < 256; i++) {
            byte[] bad = valid();
            int offset = random.nextInt(bad.length);
            bad[offset] ^= (byte) (1 << random.nextInt(8));
            deny(bad);
        }
        System.out.println("HOST_ONLY_AOT_DESCRIPTOR_READER_PASS checks=" + checks);
    }
}
