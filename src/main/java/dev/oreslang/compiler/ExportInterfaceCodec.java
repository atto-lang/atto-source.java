package dev.oreslang.compiler;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical typed-interface envelope for future .oresi import contracts.
 * This is a data codec, NOT a validated linker or AST-to-interface adapter.
 */
public final class ExportInterfaceCodec {
    private ExportInterfaceCodec() { }
    private static final byte[] MAGIC = {'O','R','E','S','I',0,1};
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final int STRING_LIMIT = 65536;
    private static final int ENTRY_LIMIT = 100000;

    /** Explicit v1 wire numbers: enum declaration order must never alter the ABI. */
    public enum Kind {
        MODULE(0), FUNCTION(1), CLASS(2), INTERFACE(3), TYPE(4), BINDING(5);

        private final int wireTag;
        Kind(int wireTag) { this.wireTag = wireTag; }
        public int wireTag() { return wireTag; }

        static Kind fromWireTag(int wireTag) {
            for (Kind kind : values()) if (kind.wireTag == wireTag) return kind;
            throw new IllegalArgumentException("invalid export kind");
        }
    }

    private record Identity(String module, Kind kind, String name) { }

    public record Header(String namespace, String compilerVersion, String targetAbi,
                         String featurePolicy) {
        public Header {
            Objects.requireNonNull(namespace);
            if (compilerVersion == null || compilerVersion.isBlank()
                    || targetAbi == null || targetAbi.isBlank()
                    || featurePolicy == null || featurePolicy.isBlank())
                throw new IllegalArgumentException("missing compilation environment identity");
        }
    }

    public record Entry(String module, Kind kind, String name, String signature) {
        public Entry {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(signature);
            if (module == null || module.isBlank() || name == null || name.isBlank())
                throw new IllegalArgumentException("empty public symbol identity");
        }
    }

    public static final class InterfaceFile {
        private final Header header;
        private final List<Entry> entries;
        private final String sha256;
        private final byte[] bytes;

        private InterfaceFile(Header header, List<Entry> entries, byte[] data) {
            this.header = header;
            this.entries = List.copyOf(entries);
            this.bytes = data.clone();
            this.sha256 = hash(this.bytes);
        }

        public Header header() { return header; }
        public List<Entry> entries() { return entries; }
        public String sha256() { return sha256; }
        public byte[] bytes() { return bytes.clone(); }
    }

    private static byte[] utf8(String text) {
        try {
            ByteBuffer data = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text));
            byte[] out = new byte[data.remaining()];
            data.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("malformed Unicode", e);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("malformed UTF-8", e);
        }
    }

    private static void put(DataOutputStream out, String s) throws IOException {
        byte[] data = utf8(s);
        if (data.length > STRING_LIMIT) throw new IllegalArgumentException("interface string too large");
        out.writeInt(data.length);
        out.write(data);
    }

    private static String get(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > STRING_LIMIT) throw new IllegalArgumentException("bad interface string length");
        byte[] data = new byte[n];
        in.readFully(data);
        return decodeUtf8(data);
    }

    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(utf8(a), utf8(b));
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparing(Entry::module, ExportInterfaceCodec::compareUtf8)
            .thenComparing(e -> e.kind().wireTag())
            .thenComparing(Entry::name, ExportInterfaceCodec::compareUtf8)
            .thenComparing(Entry::signature, ExportInterfaceCodec::compareUtf8);

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InterfaceFile encode(Header header, List<Entry> exports) {
        Objects.requireNonNull(header);
        Objects.requireNonNull(exports);
        if (exports.size() > ENTRY_LIMIT) throw new IllegalArgumentException("too many exports");
        List<Entry> sorted = new ArrayList<>(exports);
        if (sorted.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("null export");
        sorted.sort(ORDER);
        Set<Entry> exact = new HashSet<>();
        Set<Identity> singletonNames = new HashSet<>();
        for (Entry e : sorted) {
            if (!exact.add(e)) throw new IllegalArgumentException("duplicate export");
            // Only functions may share their symbol name (for arity overloads).
            // A type/class/module/binding with two signatures is ambiguous
            // even if the complete records differ. Decode must reject it too.
            if (e.kind() != Kind.FUNCTION
                    && !singletonNames.add(new Identity(e.module(), e.kind(), e.name()))) {
                throw new IllegalArgumentException("conflicting exported symbol identity");
            }
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(MAGIC);
            put(out, header.namespace());
            put(out, header.compilerVersion());
            put(out, header.targetAbi());
            put(out, header.featurePolicy());
            out.writeInt(sorted.size());
            for (Entry e : sorted) {
                put(out, e.module());
                out.writeByte(e.kind().wireTag());
                put(out, e.name());
                put(out, e.signature());
                if (buffer.size() > LIMIT) throw new IllegalArgumentException("interface too large");
            }
            out.flush();
            byte[] data = buffer.toByteArray();
            if (data.length > LIMIT) throw new IllegalArgumentException("interface too large");
            return new InterfaceFile(header, sorted, data);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static InterfaceFile decode(byte[] data) {
        Objects.requireNonNull(data);
        if (data.length > LIMIT || data.length < MAGIC.length + 20)
            throw new IllegalArgumentException("invalid interface size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte[] prefix = new byte[MAGIC.length];
            in.readFully(prefix);
            if (!Arrays.equals(prefix, MAGIC))
                throw new IllegalArgumentException("unsupported .oresi version");
            Header header = new Header(get(in), get(in), get(in), get(in));
            int count = in.readInt();
            if (count < 0 || count > ENTRY_LIMIT)
                throw new IllegalArgumentException("invalid export count");
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String module = get(in);
                Kind kind = Kind.fromWireTag(in.readUnsignedByte());
                entries.add(new Entry(module, kind, get(in), get(in)));
            }
            if (in.read() != -1) throw new IllegalArgumentException("trailing interface bytes");
            InterfaceFile result = encode(header, entries);
            if (!Arrays.equals(result.bytes(), data))
                throw new IllegalArgumentException("noncanonical interface encoding");
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated interface", e);
        }
    }
}
