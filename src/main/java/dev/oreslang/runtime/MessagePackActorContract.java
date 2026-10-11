package dev.oreslang.runtime;

import dev.oreslang.runtime.MessagePackCollectionCodec.Shape;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Closed host/compiler transport descriptor for MessagePack v2 actor mail.
 *
 * The caller-facing `variant` is a compiler-checked tagged alternative, NOT
 * an unchecked implicit runtime shape test. Distinct alternatives get distinct
 * wire schema IDs, even when their payload shapes are structurally identical.
 *
 * This is a host/runtime building block; source compiler lowering must derive
 * the closed list of variants from a declared actor message domain. It does not
 * replace ActorRuntime.send or introduce direct actor-method access.
 */
public final class MessagePackActorContract {
    public static final int MAX_VARIANTS = 64;
    // Native/Java actor contract descriptor parity. The underlying generic
    // MessagePack codec may accept larger shapes; actor mail cannot.
    public static final int MAX_DESCRIPTOR_BYTES = 4096;

    public record Alternative(int variantId, Shape shape) {
        public Alternative {
            if (variantId <= 0) throw new IllegalArgumentException("message variant ID must be positive");
            Objects.requireNonNull(shape, "shape");
        }
    }

    public record TaggedMessage(int variantId, Object value) { }

    private record Entry(int variantId, String schemaId, Shape shape) { }

    private final String domain;
    private final Map<Integer, Entry> byId;
    private final Map<String, Entry> bySchema;

    public MessagePackActorContract(String domain, Alternative... alternatives) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(alternatives, "alternatives");
        if (alternatives.length == 0 || alternatives.length > MAX_VARIANTS) {
            throw new IllegalArgumentException("closed message contract requires 1-64 variants");
        }
        Map<Integer, Entry> ids = new HashMap<>();
        Map<String, Entry> schemas = new HashMap<>();
        for (Alternative alternative : alternatives) {
            Objects.requireNonNull(alternative, "alternative");
            String schema = domain + "#variant=" + alternative.variantId();
            if (schema.length() > MessagePackCollectionCodec.MAX_SCHEMA_ID_BYTES
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(schema)
                    || schema.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new IllegalArgumentException("invalid closed actor message schema ID");
            }
            // Both Java and native actor mail pin the same bounded canonical
            // descriptor. Reject oversized/overdeep declarations here, before
            // accepting a compiled contract or any message payload.
            int length = descriptorLength(alternative.shape(), 0);
            if (length > MAX_DESCRIPTOR_BYTES) {
                throw new IllegalArgumentException("actor shape descriptor exceeds native limit");
            }
            MessagePackCollectionCodec.shapeDigest(alternative.shape());
            Entry entry = new Entry(alternative.variantId(), schema, alternative.shape());
            if (ids.putIfAbsent(alternative.variantId(), entry) != null
                    || schemas.putIfAbsent(schema, entry) != null) {
                throw new IllegalArgumentException("duplicate actor message variant ID");
            }
        }
        this.domain = domain;
        this.byId = Map.copyOf(ids);
        this.bySchema = Map.copyOf(schemas);
    }

    private static int descriptorLength(Shape shape, int depth) {
        if (depth > MessagePackCollectionCodec.MAX_DEPTH) {
            throw new IllegalArgumentException("actor shape descriptor exceeds depth limit");
        }
        if (shape instanceof MessagePackCollectionCodec.Scalar) return 1;
        MessagePackCollectionCodec.Sequence seq = (MessagePackCollectionCodec.Sequence) shape;
        int length = switch (seq.kind()) {
            case TUPLE, ARRAY, LIST -> 3; // T[...], A[...] or L[...]
            case FIXED_ARRAY, FIXED_LIST -> 4; // FA[...] or FL[...]
        };
        if (seq.kind() == MessagePackCollectionCodec.SequenceKind.TUPLE
                || seq.kind() == MessagePackCollectionCodec.SequenceKind.FIXED_ARRAY
                || seq.kind() == MessagePackCollectionCodec.SequenceKind.FIXED_LIST) {
            for (int i = 0; i < seq.slots().size(); ++i) {
                length += descriptorLength(seq.slots().get(i), depth + 1);
                if (i > 0) length++;
                if (length > MAX_DESCRIPTOR_BYTES)
                    throw new IllegalArgumentException("actor shape descriptor exceeds native limit");
            }
        } else {
            length += descriptorLength(seq.element(), depth + 1);
        }
        return length;
    }

    public String domain() { return domain; }
    public int variantCount() { return byId.size(); }

    public byte[] encode(int variantId, Object payload) {
        Entry entry = byId.get(variantId);
        if (entry == null) throw new IllegalArgumentException("undeclared message variant");
        return MessagePackCollectionCodec.encode(entry.schemaId(), entry.shape(), payload);
    }

    public TaggedMessage decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MessagePackCollectionCodec.MAX_BYTES) {
            throw new IllegalArgumentException("actor message exceeds byte budget");
        }
        // The input buffer belongs to the caller. Decode only a detached
        // snapshot: otherwise another thread can mutate the identity or
        // payload between the preliminary schema lookup and typed validation.
        byte[] snapshot = bytes.clone();
        String schema = peekCanonicalSchema(snapshot);
        Entry entry = bySchema.get(schema);
        if (entry == null) throw new IllegalArgumentException("actor message variant/schema mismatch");
        Object value = MessagePackCollectionCodec.decode(entry.schemaId(), entry.shape(), snapshot);
        return new TaggedMessage(entry.variantId(), value);
    }

    /**
     * Routing examines only the closed envelope's canonical schema field.
     * It NEVER authorizes a payload without subsequently checking its full
     * pinned SHA-256 shape digest and recursively typed MessagePack payload.
     */
    static String peekCanonicalSchema(byte[] bytes) {
        if (bytes.length < 5 || (bytes[0] & 255) != 0x94 || (bytes[1] & 255) != 2) {
            throw new IllegalArgumentException("invalid actor MessagePack v2 envelope");
        }
        int tag = bytes[2] & 255;
        int offset = 3;
        int length;
        if ((tag & 0xe0) == 0xa0) {
            length = tag & 31;
        } else if (tag == 0xd9) {
            if (bytes.length <= offset) throw new IllegalArgumentException("truncated schema ID");
            length = bytes[offset++] & 255;
            if (length <= 31) throw new IllegalArgumentException("noncanonical schema string");
        } else if (tag == 0xda) {
            if (bytes.length - offset < 2) throw new IllegalArgumentException("truncated schema ID");
            length = ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
            offset += 2;
            if (length <= 255) throw new IllegalArgumentException("noncanonical schema string");
        } else {
            throw new IllegalArgumentException("unsupported actor schema ID encoding");
        }
        if (length < 1 || length > MessagePackCollectionCodec.MAX_SCHEMA_ID_BYTES
                || length > bytes.length - offset) {
            throw new IllegalArgumentException("invalid actor schema length");
        }
        for (int i = 0; i < length; i++) {
            int octet = bytes[offset + i] & 255;
            if (octet < 33 || octet > 126) {
                throw new IllegalArgumentException("noncanonical actor schema ASCII");
            }
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
}
