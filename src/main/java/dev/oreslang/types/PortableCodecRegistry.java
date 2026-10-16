package dev.oreslang.types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-side schema identity and format admission for portable DATA codecs.
 *
 * <p>This registry is intentionally NOT a user-implementable Sendable trait,
 * serializer dispatch table, runtime codec, or actor mailbox authorization.
 * The frontend must build shapes only from fully resolved, owned, data-only
 * types; the runtime must separately prove that an encoder/decoder for the
 * pinned descriptor actually exists before permitting transport. In
 * particular, registering a descriptor cannot enable Option/Result DATA
 * transport or manufacture a supervisor CONTROL capability.
 *
 * <p>Package-private registration keeps the table out of the guest/host API.
 * A shape fingerprint is a schema commitment, NOT a signature or MAC.
 */
public final class PortableCodecRegistry {
    private PortableCodecRegistry() { }

    public enum Format { JSON, MESSAGEPACK_V2, PROTOBUF }
    public enum Purpose { NETWORK_DATA, ACTOR_DATA }
    public enum ScalarKind { BOOL, I64, F64, UTF8, BYTES }
    public enum SequenceKind { LIST, FIXED_ARRAY, FIXED_LIST }

    public sealed interface Shape permits Scalar, Sequence, ObjectShape { }

    public record Scalar(ScalarKind kind) implements Shape {
        public Scalar { Objects.requireNonNull(kind); }
    }

    /** A variable-length sequence uses exactLength=-1; fixed forms use >=0. */
    public record Sequence(SequenceKind kind, Shape element, int exactLength) implements Shape {
        public Sequence {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(element);
            if ((kind == SequenceKind.LIST && exactLength != -1)
                    || (kind != SequenceKind.LIST && (exactLength < 0 || exactLength > 1_000_000))) {
                throw new IllegalArgumentException("invalid fixed/variable sequence length");
            }
        }
    }

    /** protobufTag=0 for JSON/MessagePack, positive explicit field tag for Protobuf. */
    public record Field(String name, int protobufTag, Shape shape) {
        public Field {
            Objects.requireNonNull(name);
            Objects.requireNonNull(shape);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid portable record field name: " + name);
            }
        }
    }

    public record ObjectShape(List<Field> fields) implements Shape {
        public ObjectShape {
            fields = List.copyOf(fields);
            if (fields.size() > 256) {
                throw new IllegalArgumentException("portable record exceeds 256 fields");
            }
        }
    }

    /** A trusted, immutable compiler descriptor; it carries no runtime rights. */
    public static final class Descriptor {
        private final Format format;
        private final Purpose purpose;
        private final String resolvedType;
        private final String schemaId;
        private final int version;
        private final String canonicalShape;
        private final String fingerprintHex;

        private Descriptor(Format format, Purpose purpose, String resolvedType,
                           String schemaId, int version, String canonicalShape) {
            this.format = format;
            this.purpose = purpose;
            this.resolvedType = resolvedType;
            this.schemaId = schemaId;
            this.version = version;
            this.canonicalShape = canonicalShape;
            // Deliberate domain separation: this is NOT the MessagePack v2
            // envelope's established shape hash and cannot replace it.
            String commitment = "oreslang.codec.registry.v1\n" + format + "\n"
                    + purpose + "\n" + resolvedType + "\n" + schemaId
                    + "\n" + version + "\n" + canonicalShape;
            this.fingerprintHex = sha256(commitment);
        }

        public Format format() { return format; }
        public Purpose purpose() { return purpose; }
        public String resolvedType() { return resolvedType; }
        public String schemaId() { return schemaId; }
        public int version() { return version; }
        public String canonicalShape() { return canonicalShape; }
        public String fingerprintHex() { return fingerprintHex; }
    }

    private record TypeKey(Format format, Purpose purpose, String type, int version) { }
    private record SchemaKey(Format format, Purpose purpose, String schemaId, int version) { }

    /**
     * Per-compilation registration; no global guest-writable registry and no
     * decoding lookup by an incoming wire-selected type name. Registration
     * and pinned lookup are serialized so parallel module compilation cannot
     * partially publish conflicting identities.
     */
    public static final class Registry {
        private final Map<TypeKey, Descriptor> byType = new HashMap<>();
        private final Map<SchemaKey, Descriptor> bySchema = new HashMap<>();

        /**
         * For trusted compiler integration only. Must be called after source
         * resolution and ownership analysis, not by a userland trait method.
         */
        synchronized Descriptor registerCompilerDescriptor(Format format, Purpose purpose,
                                              String resolvedType, String schemaId,
                                              int version, Shape shape) {
            Objects.requireNonNull(format);
            Objects.requireNonNull(purpose);
            Objects.requireNonNull(shape);
            requireQualifiedIdentity(resolvedType, "type");
            requireQualifiedIdentity(schemaId, "schema");
            if (version < 1) throw new IllegalArgumentException("schema version must be positive");
            if (purpose == Purpose.ACTOR_DATA && format != Format.MESSAGEPACK_V2) {
                throw new IllegalArgumentException("actor DATA only admits its pinned MessagePack v2 profile");
            }
            if (format == Format.PROTOBUF && !(shape instanceof ObjectShape)) {
                throw new IllegalArgumentException("Protobuf top-level schema must be a tagged record");
            }
            String canonical = canonicalShape(shape, format);
            Descriptor proposal = new Descriptor(format, purpose, resolvedType, schemaId,
                    version, canonical);
            TypeKey typeKey = new TypeKey(format, purpose, resolvedType, version);
            SchemaKey schemaKey = new SchemaKey(format, purpose, schemaId, version);
            Descriptor typePrevious = byType.get(typeKey);
            Descriptor schemaPrevious = bySchema.get(schemaKey);
            if (typePrevious != null && !sameContract(typePrevious, proposal)) {
                throw new IllegalArgumentException("type/format/version codec contract collision");
            }
            if (schemaPrevious != null && !sameContract(schemaPrevious, proposal)) {
                throw new IllegalArgumentException("schema identity/version codec contract collision");
            }
            // Never partially publish either index on a failed registration.
            Descriptor selected = typePrevious != null ? typePrevious
                    : schemaPrevious != null ? schemaPrevious : proposal;
            byType.putIfAbsent(typeKey, selected);
            bySchema.putIfAbsent(schemaKey, selected);
            return selected;
        }

        /** The caller supplies compiler-pinned type identity, never bytes from the wire. */
        synchronized Descriptor requirePinned(Format format, Purpose purpose,
                                 String resolvedType, int version) {
            Descriptor found = byType.get(new TypeKey(format, purpose, resolvedType, version));
            if (found == null) {
                throw new IllegalArgumentException("no pinned compiler codec descriptor");
            }
            return found;
        }

        public synchronized int size() { return byType.size(); }
    }

    private static boolean sameContract(Descriptor a, Descriptor b) {
        return a.format == b.format && a.purpose == b.purpose
                && a.version == b.version
                && a.resolvedType.equals(b.resolvedType)
                && a.schemaId.equals(b.schemaId)
                && a.canonicalShape.equals(b.canonicalShape);
    }

    private static void requireQualifiedIdentity(String identity, String what) {
        if (identity == null || identity.length() > 512
                || !identity.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("compiler " + what + " identity must be fully qualified");
        }
    }

    private static String canonicalShape(Shape shape, Format format) {
        StringBuilder out = new StringBuilder();
        walk(shape, format, new IdentityHashMap<>(), new int[1], 0, out);
        if (out.length() > 100_000) {
            throw new IllegalArgumentException("portable schema exceeds descriptor size limit");
        }
        return out.toString();
    }

    private static void walk(Shape shape, Format format,
                             IdentityHashMap<Shape, Boolean> visiting,
                             int[] nodes, int depth, StringBuilder out) {
        if (depth > 64 || ++nodes[0] > 4_096) {
            throw new IllegalArgumentException("portable schema exceeds depth/node budget");
        }
        if (visiting.put(shape, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic portable schema");
        }
        try {
            if (shape instanceof Scalar scalar) {
                if (scalar.kind == ScalarKind.BYTES && format != Format.PROTOBUF) {
                    throw new IllegalArgumentException("BYTES needs an implemented, format-specific encoding");
                }
                out.append("S(").append(scalar.kind).append(')');
            } else if (shape instanceof Sequence sequence) {
                if (format == Format.PROTOBUF && sequence.kind != SequenceKind.LIST) {
                    throw new IllegalArgumentException("fixed sequences require a Protobuf mapping profile");
                }
                out.append("Q(").append(sequence.kind).append(',')
                        .append(sequence.exactLength).append(',');
                walk(sequence.element, format, visiting, nodes, depth + 1, out);
                out.append(')');
            } else if (shape instanceof ObjectShape object) {
                if (format == Format.MESSAGEPACK_V2) {
                    // The existing native portable v2 subset has no record
                    // lowering. Never silently pretend it does.
                    throw new IllegalArgumentException("MessagePack v2 record codec is not yet verified");
                }
                List<Field> sorted = new ArrayList<>(object.fields);
                sorted.sort(Comparator.comparing(Field::name));
                Set<String> names = new HashSet<>();
                Set<Integer> tags = new HashSet<>();
                out.append("R(").append(sorted.size()).append(':');
                for (Field field : sorted) {
                    if (!names.add(field.name)) {
                        throw new IllegalArgumentException("duplicate portable record key: " + field.name);
                    }
                    int tag = field.protobufTag;
                    if (format == Format.PROTOBUF) {
                        if (tag <= 0 || tag > 536_870_911 || (tag >= 19_000 && tag <= 19_999)
                                || !tags.add(tag)) {
                            throw new IllegalArgumentException("invalid/duplicate Protobuf field tag");
                        }
                    } else if (tag != 0) {
                        throw new IllegalArgumentException("JSON/MessagePack fields cannot carry Protobuf tags");
                    }
                    out.append(field.name.length()).append('#').append(field.name)
                            .append('@').append(tag).append('=');
                    walk(field.shape, format, visiting, nodes, depth + 1, out);
                    out.append(';');
                }
                out.append(')');
            } else {
                throw new IllegalArgumentException("unknown compiler wire shape");
            }
        } finally {
            visiting.remove(shape);
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required", missing);
        }
    }
}
