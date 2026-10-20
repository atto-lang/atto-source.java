package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, deterministic V3 SOURCE-CONTRACT fingerprint for tooling.
 *
 * This hash records parameter name, declaration order, raw type syntax,
 * mutability, structural classification, name-? syntax, effective omission,
 * and normalized closed defaults. It deliberately does not resolve
 * imported types, prove intrinsic Option identity, establish an ABI, or
 * authorize guest execution. A hot-reload loader must ALSO verify owner,
 * version, resolved nominal types and the executable backend ABI.
 *
 * Both @DefaultArgVals and per-formal @Default normalize to the same bytes.
 * All variable-width fields are UTF-8 length-prefixed; no delimiter or
 * Java toString() representation contributes to the canonical encoding.
 */
public final class V3SourceContractFingerprint {
    private V3SourceContractFingerprint() { }

    private static final String DOMAIN = "oreslang/v3/parameter-source-contract-preview";
    private static final int VERSION = 1;
    private static final int MAX_TYPE_DEPTH = 64;
    private static final int MAX_TYPE_NODES = 8192;
    private static final int MAX_FORMALS = 1024;

    private record TypeVisit(Ast.TypeRef value, int depth) { }

    /**
     * Run BEFORE policy/preview expansion, whose TypeRef recursion otherwise
     * executes before the fingerprint's existing depth check. This preflight
     * uses explicit heap-backed traversal to prevent generated nested types
     * from exhausting the Java stack in the source-contract hashing path.
     */
    private static void preflight(List<Ast.Param> parameters) {
        if (parameters.size() > MAX_FORMALS) {
            throw new IllegalArgumentException("v3 source-contract formal count exceeds limit");
        }
        ArrayDeque<TypeVisit> pending = new ArrayDeque<>();
        for (Ast.Param param : parameters) {
            Objects.requireNonNull(param, "formal");
            pending.addLast(new TypeVisit(Objects.requireNonNull(param.type(), "formal type"), 0));
        }
        int visited = 0;
        while (!pending.isEmpty()) {
            TypeVisit item = pending.removeLast();
            if (item.depth() > MAX_TYPE_DEPTH || ++visited > MAX_TYPE_NODES) {
                throw new IllegalArgumentException(
                        "v3 source-contract type depth/node budget exceeded");
            }
            // Account for the complete frontier before enqueueing children.
            // Otherwise one hostile wide TypeRef can create a huge queue
            // before visited reaches MAX_TYPE_NODES. This is bounded work,
            // not a nominal-type or source-authenticity proof.
            List<Ast.TypeRef> children = item.value().arguments();
            if ((long) visited + pending.size() + children.size() > MAX_TYPE_NODES) {
                throw new IllegalArgumentException(
                        "v3 source-contract type depth/node budget exceeded");
            }
            for (Ast.TypeRef nested : children) {
                pending.addLast(new TypeVisit(
                        Objects.requireNonNull(nested, "nested source-contract type"),
                        item.depth() + 1));
            }
        }
    }

    public static String sha256Hex(List<Ast.Param> parameters, List<Ast.Annotation> annotations) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(annotations, "annotations");
        preflight(parameters);
        List<V3DefaultArgValsPolicy.FormalPreview> checked =
                V3DefaultArgValsPolicy.validateDeclaration(parameters, annotations);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                string(out, DOMAIN);
                out.writeInt(VERSION);
                out.writeInt(parameters.size());
                for (int i = 0; i < parameters.size(); i++) {
                    Ast.Param param = parameters.get(i);
                    V3DefaultArgValsPolicy.FormalPreview formal = checked.get(i);
                    string(out, param.name());
                    type(out, param.type(), 0); // RAW staged syntax; no nominal identity assumption
                    out.writeBoolean(param.structural());
                    out.writeBoolean(param.mutable());
                    out.writeBoolean(param.argumentOmittable()); // preserve explicit name suffix
                    out.writeBoolean(formal.omittable()); // annotation can imply omission
                    out.writeBoolean(formal.optionTyped()); // unverified preview classification
                    DefaultArgumentPolicy.Literal value = formal.defaultValue();
                    out.writeBoolean(value != null);
                    if (value != null) {
                        // Fixed wire tags, never enum.ordinal(): a Java enum
                        // reorder must not silently change this contract.
                        switch (value.kind()) {
                            case INT -> {
                                out.writeByte(1);
                                out.writeLong((Long) value.value());
                            }
                            case BOOL -> {
                                out.writeByte(2);
                                out.writeBoolean((Boolean) value.value());
                            }
                            case STRING -> {
                                out.writeByte(3);
                                string(out, (String) value.value());
                            }
                            case NONE -> out.writeByte(4); // not an absent default
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("cannot compute v3 source preview fingerprint", e);
        }
    }

    private static void type(DataOutputStream out, Ast.TypeRef type, int depth) throws IOException {
        if (depth > MAX_TYPE_DEPTH) {
            throw new IllegalArgumentException("v3 type nesting exceeds fingerprint safety limit");
        }
        string(out, type.name());
        out.writeBoolean(type.inferArguments());
        out.writeInt(type.arguments().size());
        for (Ast.TypeRef nested : type.arguments()) {
            type(out, nested, depth + 1);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        // String#getBytes silently replaces malformed UTF-16. Reject it
        // instead: two distinct source strings must not hash to one byte form.
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired UTF-16 high surrogate in v3 contract");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired UTF-16 low surrogate in v3 contract");
            }
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
