package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Preliminary v2 interface envelope: commits to exact imported provider
 * identities, without treating lexical TypeRef strings as resolved type IDs.
 *
 * A trusted compiler driver must verify that every provider fingerprint
 * matches a type-checked, trusted, noncyclic provider interface. Decode is
 * structural validation, not authorization to link native code or reuse caches.
 */
public final class ExportDependencyManifest {
    private static final byte[] MAGIC = {'O','R','E','S','I',0,2};
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private static final int MAX_STRING = 65536;
    private static final int MAX_IMPORTS = 10000;
    private static final String HEX64 = "[0-9a-f]{64}";
    private ExportDependencyManifest() { }

    public record Binding(Ast.ImportDecl imported, String providerUnit,
                          String providerNamespace, String providerInterfaceSha256) {
        public Binding {
            Objects.requireNonNull(imported, "imported");
            Objects.requireNonNull(providerUnit, "providerUnit");
            Objects.requireNonNull(providerNamespace, "providerNamespace");
            Objects.requireNonNull(providerInterfaceSha256, "providerInterfaceSha256");
            if (providerUnit.isBlank() || imported.path() == null || imported.path().isBlank()) {
                throw new IllegalArgumentException("missing provider unit or import path");
            }
            if (!providerInterfaceSha256.matches(HEX64)) {
                throw new IllegalArgumentException("provider ABI must be lowercase SHA-256");
            }
            if (imported.wildcard() && !imported.names().isEmpty()) {
                throw new IllegalArgumentException("wildcard cannot also name imports");
            }
            if (!imported.wildcard() && imported.names().isEmpty()) {
                throw new IllegalArgumentException("named import must contain symbols");
            }
            if (new HashSet<>(imported.names()).size() != imported.names().size()) {
                throw new IllegalArgumentException("duplicate imported symbol");
            }
        }
    }

    public static final class Manifest {
        private final String sourceUnit;
        private final ExportInterfaceCodec.InterfaceFile publicApi;
        private final List<Binding> bindings;
        private final byte[] encoded;
        private final String digest;

        private Manifest(String unit, ExportInterfaceCodec.InterfaceFile api,
                         List<Binding> bindings, byte[] bytes) {
            this.sourceUnit = unit;
            this.publicApi = api;
            this.bindings = List.copyOf(bindings);
            this.encoded = bytes.clone();
            this.digest = sha256(bytes);
        }
        public String sourceUnit() { return sourceUnit; }
        public ExportInterfaceCodec.InterfaceFile publicApi() { return publicApi; }
        public List<Binding> bindings() { return bindings; }
        public byte[] bytes() { return encoded.clone(); }
        public String sha256() { return digest; }
    }

    private static final Comparator<Binding> ORDER = Comparator
            .comparing(ExportDependencyManifest::sortKey, (a,b) ->
                    Arrays.compareUnsigned(a,b));

    /** Require an exact one-to-one, unambiguous binding for each parsed import. */
    public static Manifest collect(Ast.Program checked, String sourceUnit,
                                   ExportInterfaceCodec.Header header,
                                   List<Binding> resolved) {
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(resolved, "resolved");
        if (resolved.size() > MAX_IMPORTS) throw new IllegalArgumentException("too many imports");
        Set<Ast.ImportDecl> expected = new HashSet<>(checked.imports());
        if (expected.size() != checked.imports().size()) {
            throw new IllegalArgumentException("duplicate source import declaration");
        }
        Set<Ast.ImportDecl> actual = new HashSet<>();
        for (Binding binding : resolved) {
            Objects.requireNonNull(binding, "resolved binding");
            if (!actual.add(binding.imported())) {
                throw new IllegalArgumentException("duplicate or ambiguous import binding");
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("missing or extraneous resolved import bindings");
        }
        // V1 collector deliberately refuses imports. Strip only AFTER every
        // import has been explicitly committed; the manifest carries them in
        // the v2 envelope. This is not a substitute for typed linking.
        Ast.Program localDeclarations = new Ast.Program(
                checked.namespace(), List.of(), checked.modules());
        ExportInterfaceCodec.InterfaceFile api =
                ExportInterfaceCollector.collect(localDeclarations, header);
        return pack(sourceUnit, api, resolved);
    }

    public static Manifest decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length > MAX_BYTES || data.length < MAGIC.length + 16) {
            throw new IllegalArgumentException("invalid v2 interface length");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte[] prefix = new byte[MAGIC.length];
            in.readFully(prefix);
            if (!Arrays.equals(prefix, MAGIC)) throw new IllegalArgumentException("unsupported v2 schema");
            String unit = get(in);
            int apiLength = in.readInt();
            if (apiLength < 1 || apiLength > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("invalid embedded interface length");
            }
            byte[] apiBytes = new byte[apiLength];
            in.readFully(apiBytes);
            ExportInterfaceCodec.InterfaceFile api = ExportInterfaceCodec.decode(apiBytes);
            int count = in.readInt();
            if (count < 0 || count > MAX_IMPORTS) throw new IllegalArgumentException("invalid import count");
            List<Binding> resolved = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Ast.ImportKind kind = Ast.ImportKind.valueOf(get(in));
                boolean wildcard = in.readBoolean();
                String namespace = get(in);
                String importPath = get(in);
                int nameCount = in.readInt();
                if (nameCount < 0 || nameCount > MAX_IMPORTS)
                    throw new IllegalArgumentException("invalid imported name count");
                List<String> names = new ArrayList<>(nameCount);
                for (int j = 0; j < nameCount; j++) names.add(get(in));
                Ast.ImportDecl source = new Ast.ImportDecl(kind, names, wildcard,
                        namespace.isEmpty() ? null : namespace, importPath);
                resolved.add(new Binding(source, get(in), get(in), get(in)));
            }
            if (in.read() != -1) throw new IllegalArgumentException("trailing v2 interface data");
            Manifest result = pack(unit, api, resolved);
            if (!Arrays.equals(result.encoded, data)) {
                throw new IllegalArgumentException("noncanonical v2 interface");
            }
            return result;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid v2 export manifest", invalid);
        }
    }

    private static Manifest pack(String sourceUnit, ExportInterfaceCodec.InterfaceFile api,
                                 List<Binding> resolved) {
        Objects.requireNonNull(api, "publicApi");
        if (sourceUnit == null || sourceUnit.isBlank())
            throw new IllegalArgumentException("source unit required");
        if (resolved.size() > MAX_IMPORTS) throw new IllegalArgumentException("too many imports");
        List<Binding> sorted = new ArrayList<>(resolved);
        if (sorted.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("null import");
        sorted.sort(ORDER);
        Set<Ast.ImportDecl> distinct = new HashSet<>();
        for (Binding binding : sorted) {
            if (!distinct.add(binding.imported()))
                throw new IllegalArgumentException("duplicate import declaration");
            if (sourceUnit.equals(binding.providerUnit()))
                throw new IllegalArgumentException("self import cannot be exported");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream stream = new DataOutputStream(out);
            stream.write(MAGIC);
            put(stream, sourceUnit);
            byte[] apiBytes = api.bytes();
            stream.writeInt(apiBytes.length);
            stream.write(apiBytes);
            stream.writeInt(sorted.size());
            for (Binding binding : sorted) {
                Ast.ImportDecl imported = binding.imported();
                put(stream, imported.kind().name());
                stream.writeBoolean(imported.wildcard());
                put(stream, imported.namespace() == null ? "" : imported.namespace());
                put(stream, imported.path());
                List<String> names = new ArrayList<>(imported.names());
                names.sort(ExportDependencyManifest::compareUtf8);
                stream.writeInt(names.size());
                for (String name : names) put(stream, name);
                put(stream, binding.providerUnit());
                put(stream, binding.providerNamespace());
                put(stream, binding.providerInterfaceSha256());
                if (out.size() > MAX_BYTES) throw new IllegalArgumentException("v2 export manifest too large");
            }
            stream.flush();
            byte[] encoded = out.toByteArray();
            if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("v2 export manifest too large");
            return new Manifest(sourceUnit, api, sorted, encoded);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sortKey(Binding binding) {
        // Use the same length-delimited framing as wire serialization, so
        // independently sorted import declarations have deterministic order.
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            Ast.ImportDecl imp = binding.imported();
            put(data, imp.kind().name());
            data.writeBoolean(imp.wildcard());
            put(data, imp.namespace() == null ? "" : imp.namespace());
            put(data, imp.path());
            List<String> names = new ArrayList<>(imp.names());
            names.sort(ExportDependencyManifest::compareUtf8);
            data.writeInt(names.size());
            for (String name : names) put(data, name);
            put(data, binding.providerUnit());
            put(data, binding.providerNamespace());
            put(data, binding.providerInterfaceSha256());
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void put(DataOutputStream out, String value) throws IOException {
        byte[] bytes = encodeUtf8(value);
        if (bytes.length > MAX_STRING) throw new IllegalArgumentException("v2 string budget exceeded");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String get(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_STRING) throw new IllegalArgumentException("invalid v2 string length");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid UTF-8", invalid);
        }
    }

    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer data = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[data.remaining()];
            data.get(result);
            return result;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("malformed Unicode", invalid);
        }
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(encodeUtf8(left), encodeUtf8(right));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
