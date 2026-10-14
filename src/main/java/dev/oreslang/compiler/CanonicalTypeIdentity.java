package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.io.ByteArrayOutputStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded canonical fingerprint for explicitly resolved type trees.
 *
 * The caller must supply the trusted compiler's nominal resolution table;
 * this class NEVER guesses whether an unqualified identifier is a type alias,
 * a local class, or an imported nominal type. Its output is not proof that
 * a source program was successfully type- or ownership-checked.
 */
public final class CanonicalTypeIdentity {
    private static final byte[] MAGIC = {'O', 'T', 'Y', 'P', 'E', 1};
    private static final int MAX_DEPTH = 96;
    private static final int MAX_NODES = 10000;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_FIELDS = 2048;
    private static final Set<String> PRIMITIVES = Set.of(
            "int", "long", "short", "byte", "float", "double", "bool",
            "String", "void", "Any", "char", "usize", "isize");
    private CanonicalTypeIdentity() { }

    public record Nominal(String providerUnit, String namespace, String symbol,
                          String providerInterfaceSha256) {
        public Nominal {
            if (providerUnit == null || providerUnit.isBlank()
                    || namespace == null || symbol == null || symbol.isBlank()
                    || providerInterfaceSha256 == null
                    || !providerInterfaceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("nominal identity requires a versioned provider");
            }
        }
    }

    public static final class TypeId {
        private final byte[] canonical;
        private final String sha256;
        private TypeId(byte[] data) {
            canonical = data.clone();
            sha256 = sha256(data);
        }
        public byte[] bytes() { return canonical.clone(); }
        public String sha256() { return sha256; }
    }

    private static final class Budget {
        int nodes;
    }

    public static TypeId of(Ast.TypeRef type, Map<String, Nominal> resolvedNominals,
                            List<String> genericBinders) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(resolvedNominals);
        Objects.requireNonNull(genericBinders);
        if (genericBinders.size() > MAX_FIELDS)
            throw new IllegalArgumentException("too many generic parameters");
        Map<String, Integer> binders = new HashMap<>();
        for (int i = 0; i < genericBinders.size(); i++) {
            String name = genericBinders.get(i);
            if (name == null || name.isBlank() || PRIMITIVES.contains(name)
                    || binders.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException("ambiguous generic parameter");
            }
        }
        Budget budget = new Budget();
        byte[] node = encodeNode(type, resolvedNominals, binders, budget, 0);
        if (node.length + MAGIC.length > MAX_BYTES) throw new IllegalArgumentException("type too large");
        byte[] result = new byte[MAGIC.length + node.length];
        System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
        System.arraycopy(node, 0, result, MAGIC.length, node.length);
        return new TypeId(result);
    }

    private static byte[] encodeNode(Ast.TypeRef type, Map<String, Nominal> resolved,
                                     Map<String, Integer> binders, Budget budget, int depth) {
        if (type == null || depth > MAX_DEPTH || ++budget.nodes > MAX_NODES)
            throw new IllegalArgumentException("type inference/recursion budget exceeded");
        String name = type.name();
        if (name == null || name.isBlank() || name.equals("Unknown")
                || name.equals("$infer$") || type.inferArguments()) {
            throw new IllegalArgumentException("type must be fully resolved");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            List<Ast.TypeRef> args = type.arguments();
            switch (name) {
                case "$record$", "$readonly_record$" -> {
                    out.writeByte(name.equals("$record$") ? 1 : 2);
                    Map<String, Ast.TypeRef> fields = type.recordMembers();
                    if (fields.size() > MAX_FIELDS) throw new IllegalArgumentException("too many record fields");
                    List<String> keys = new ArrayList<>(fields.keySet());
                    keys.sort(CanonicalTypeIdentity::compareUtf8);
                    out.writeInt(keys.size());
                    for (String key : keys) {
                        writeString(out, key);
                        writeChild(out, encodeNode(fields.get(key), resolved, binders, budget, depth + 1));
                    }
                }
                case "$union$" -> {
                    if (args.isEmpty() || args.size() > MAX_FIELDS)
                        throw new IllegalArgumentException("invalid union arity");
                    out.writeByte(3);
                    List<byte[]> children = new ArrayList<>();
                    for (Ast.TypeRef arg : args) {
                        children.add(encodeNode(arg, resolved, binders, budget, depth + 1));
                    }
                    children.sort(Arrays::compareUnsigned);
                    for (int i = 1; i < children.size(); i++) {
                        if (Arrays.equals(children.get(i - 1), children.get(i)))
                            throw new IllegalArgumentException("duplicate union case");
                    }
                    out.writeInt(children.size());
                    for (byte[] child : children) writeChild(out, child);
                }
                case "$tuple$", "Fnc", "$borrow$", "$borrow_mut$", "$not$" -> {
                    out.writeByte(switch (name) {
                        case "$tuple$" -> 4;
                        case "Fnc" -> 5;
                        case "$borrow$" -> 6;
                        case "$borrow_mut$" -> 7;
                        default -> 8;
                    });
                    if ((name.equals("$borrow$") || name.equals("$borrow_mut$") || name.equals("$not$"))
                            && args.size() != 1) throw new IllegalArgumentException("invalid unary type");
                    writeChildren(out, args, resolved, binders, budget, depth);
                }
                default -> {
                    if (PRIMITIVES.contains(name)) {
                        if (!args.isEmpty()) throw new IllegalArgumentException("primitive type has arguments");
                        out.writeByte(9);
                        writeString(out, name);
                    } else if (binders.containsKey(name)) {
                        if (!args.isEmpty()) throw new IllegalArgumentException("generic binder has arguments");
                        out.writeByte(10);
                        out.writeInt(binders.get(name));
                    } else {
                        if (name.startsWith("$"))
                            throw new IllegalArgumentException("unsupported compiler-only type tag");
                        Nominal symbol = resolved.get(name);
                        if (symbol == null) throw new IllegalArgumentException(
                                "unresolved nominal type: " + name);
                        out.writeByte(11);
                        writeString(out, symbol.providerUnit());
                        writeString(out, symbol.namespace());
                        writeString(out, symbol.symbol());
                        writeString(out, symbol.providerInterfaceSha256());
                        writeChildren(out, args, resolved, binders, budget, depth);
                    }
                }
            }
            out.flush();
            if (buffer.size() > MAX_BYTES) throw new IllegalArgumentException("type too large");
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeChildren(DataOutputStream out, List<Ast.TypeRef> children,
                                      Map<String, Nominal> resolved,
                                      Map<String, Integer> binders, Budget budget,
                                      int depth) throws IOException {
        if (children.size() > MAX_FIELDS) throw new IllegalArgumentException("too many type arguments");
        out.writeInt(children.size());
        for (Ast.TypeRef child : children) {
            writeChild(out, encodeNode(child, resolved, binders, budget, depth + 1));
        }
    }

    private static void writeChild(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("type child too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = strictUtf8(value);
        if (bytes.length > 65536) throw new IllegalArgumentException("type label too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer data = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("malformed type identifier", invalid);
        }
    }

    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(strictUtf8(a), strictUtf8(b));
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
