package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One opaque fixed-size value per row of ONE inverted-class field column.
 *
 * The declaring field is SoA-separated from sibling fields; its structural
 * members stay packed AoS inside each element. This is a host reference codec,
 * not recursive source lowering or an interpreter-visible guest builtin.
 */
public final class InvertedRecordLayout {
    // Host-reference admission caps. These are denial-of-service guards, not
    // a committed language/interop ABI limit.
    static final int MAX_RECORD_MEMBERS = 1024;
    static final int MAX_RECORD_BYTES = 64 * 1024;

    public record Member(String name, InvertedColumnStore.Kind kind, int offset) { }

    private final List<Member> members;
    private final int bytes;

    private InvertedRecordLayout(List<Member> members, int bytes) {
        this.members = List.copyOf(members);
        this.bytes = bytes;
    }

    public List<Member> members() { return members; }
    public int bytes() { return bytes; }

    static InvertedColumnStore.Kind scalarKind(Ast.TypeRef type) {
        if (type == null || type.inferArguments() || !type.arguments().isEmpty()) return null;
        return switch (type.name()) {
            case "i32" -> InvertedColumnStore.Kind.I32;
            case "i64" -> InvertedColumnStore.Kind.I64;
            case "f32" -> InvertedColumnStore.Kind.F32;
            case "f64" -> InvertedColumnStore.Kind.F64;
            case "bool" -> InvertedColumnStore.Kind.BOOL;
            default -> null;
        };
    }

    public static InvertedRecordLayout fromType(Ast.TypeRef type) {
        Objects.requireNonNull(type, "record type");
        if (!type.isClosedAggregateType() || type.inferArguments()) {
            throw new IllegalArgumentException("expected a fixed-layout structural record type");
        }
        Map<String, Ast.TypeRef> recordMembers = type.aggregateMembers();
        if (recordMembers.size() > MAX_RECORD_MEMBERS) {
            throw new IllegalArgumentException("inverted record has too many members");
        }
        // Canonicalize again at the ABI boundary. Parser-built record types are
        // sorted already, but direct or rewritten ASTs must not be able to
        // perturb the byte layout of the same structural record.
        List<Map.Entry<String, Ast.TypeRef>> canonicalMembers =
                new ArrayList<>(recordMembers.entrySet());
        canonicalMembers.sort(Map.Entry.comparingByKey());

        List<Member> members = new ArrayList<>();
        int offset = 0;
        for (Map.Entry<String, Ast.TypeRef> entry : canonicalMembers) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "invalid inverted record member name '" + entry.getKey() + "'");
            }
            // A nested record is NOT implicitly expanded to grandchild
            // columns. A future nested fixed-layout codec may support it as
            // an opaque element, but this initial ABI deliberately fails closed.
            InvertedColumnStore.Kind kind = scalarKind(entry.getValue());
            if (kind == null) {
                throw new IllegalArgumentException("record field '" + entry.getKey()
                        + "' has no verified fixed-width member ABI; no recursive SoA lowering");
            }
            members.add(new Member(entry.getKey(), kind, offset));
            try {
                offset = Math.addExact(offset, kind.bytes());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("inverted record byte width overflow", overflow);
            }
            if (offset > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException("inverted record exceeds host-reference byte limit");
            }
        }
        if (offset == 0) throw new IllegalArgumentException("empty inverted value records are unsupported");
        return new InvertedRecordLayout(members, offset);
    }

    /** Copy a typed structural value into an independent, fixed-size AoS row. */
    public byte[] encode(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() != members.size()) {
            throw new IllegalArgumentException("inverted record requires exactly its declared members");
        }
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (Member member : members) {
            if (!map.containsKey(member.name())) {
                throw new IllegalArgumentException("missing inverted record member '" + member.name() + "'");
            }
            Object scalar = map.get(member.name());
            switch (member.kind()) {
                case I32 -> {
                    if (!(scalar instanceof Integer n)) throw invalid(member.name());
                    buffer.putInt(member.offset(), n);
                }
                case I64 -> {
                    if (!(scalar instanceof Long n)) throw invalid(member.name());
                    buffer.putLong(member.offset(), n);
                }
                case F32 -> {
                    if (!(scalar instanceof Float n)) throw invalid(member.name());
                    buffer.putFloat(member.offset(), n);
                }
                case F64 -> {
                    if (!(scalar instanceof Double n)) throw invalid(member.name());
                    buffer.putDouble(member.offset(), n);
                }
                case BOOL -> {
                    if (!(scalar instanceof Boolean n)) throw invalid(member.name());
                    buffer.put(member.offset(), (byte) (n ? 1 : 0));
                }
                case RECORD -> throw new IllegalStateException("recursive member layout is not admitted");
            }
        }
        return buffer.array();
    }

    private static boolean decodeCanonicalBool(byte encoded, String name) {
        if (encoded != 0 && encoded != 1) {
            throw new IllegalArgumentException("noncanonical boolean in inverted record member '" + name + "'");
        }
        return encoded == 1;
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("wrong type for inverted record member '" + name + "'");
    }

    /** An immutable detached value; no internal column or buffer escapes. */
    public Map<String, Object> decode(byte[] stored) {
        if (stored == null || stored.length != bytes) {
            throw new IllegalArgumentException("inverted record payload width mismatch");
        }
        ByteBuffer buffer = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN);
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        for (Member member : members) {
            Object decoded = switch (member.kind()) {
                case I32 -> buffer.getInt(member.offset());
                case I64 -> buffer.getLong(member.offset());
                case F32 -> buffer.getFloat(member.offset());
                case F64 -> buffer.getDouble(member.offset());
                case BOOL -> decodeCanonicalBool(buffer.get(member.offset()), member.name());
                case RECORD -> throw new IllegalStateException("recursive member layout is not admitted");
            };
            value.put(member.name(), decoded);
        }
        return Collections.unmodifiableMap(value);
    }
}
