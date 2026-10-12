package dev.oreslang.runtime;

import dev.oreslang.types.TaggedMessageContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executable v2 wire adapter for a closed compiler-bound enum/pattern domain.
 *
 * Uses the existing canonical MessagePack v2 shape-bound codec per variant:
 * [2, schemaIdForDomainAndCase, SHA256(tuple-shape), [enumName, tag, payload]].
 * The domain is fixed by the receiver's compiled code; wire bytes cannot
 * introduce new enum constructors or select a host class.
 *
 * This is a first, bounded implementation. Native LLVM still needs identical
 * source-schema lowering and production message integration.
 */
public final class TaggedMessageCodec implements MessagePackCollectionCodec.MailboxWireCodec {
    public record TaggedValue(String enumName, String variant, int tag, Object payload) {
        public TaggedValue {
            Objects.requireNonNull(enumName);
            Objects.requireNonNull(variant);
            if (tag < 1) throw new IllegalArgumentException("invalid enum tag");
        }
        public String qualifiedName() { return enumName + "." + variant; }
    }

    private record CaseCodec(TaggedMessageContracts.Case source,
                             String schemaId,
                             MessagePackCollectionCodec.Shape shape) { }
    private final Map<String, CaseCodec> byName;
    private final Map<String, CaseCodec> bySchema;
    private final String closedDomainIdentity;

    /** Compiler-issued nominal wire identity used to typecheck whole actor
     * pipeline edges; guest payloads do not choose or modify this identity. */
    public String closedDomainIdentity() {
        return closedDomainIdentity;
    }

    public TaggedMessageCodec(TaggedMessageContracts.Domain domain) {
        Objects.requireNonNull(domain);
        if (domain.cases().isEmpty() || domain.cases().size() > 4096) {
            throw new IllegalArgumentException("tagged actor domain must have 1..4096 cases");
        }
        Map<String, CaseCodec> entries = new HashMap<>();
        Map<String, CaseCodec> schemas = new HashMap<>();
        StringBuilder fingerprint = new StringBuilder("oreslang.tagged.domain.v1/");
        fingerprint.append(domain.name()).append('/');
        domain.cases().stream().map(TaggedMessageContracts.Case::qualifiedName)
                .sorted().forEach(n -> fingerprint.append(n.length()).append(':').append(n).append(';'));
        String domainHash = digestHex(fingerprint.toString());
        // Route admission must compare the FULL compiler-owned nominal
        // contract, not only its case names. Otherwise two separately linked
        // actors could agree on "Signal.Data" while disagreeing on the tag or
        // payload schema. This fingerprint is deliberately distinct from
        // domainHash: changing the v2 wire schema IDs would break native
        // compatibility and the established golden vectors.
        StringBuilder routeFingerprint = new StringBuilder(
                "oreslang.actor.pipeline.closed-domain.v1/");
        routeFingerprint.append(domainHash).append('/');
        domain.cases().stream()
                .sorted(java.util.Comparator.comparing(
                        TaggedMessageContracts.Case::qualifiedName))
                .forEach(source -> {
                    String qualified = source.qualifiedName();
                    routeFingerprint.append(qualified.length()).append(':')
                            .append(qualified).append('#').append(source.tag())
                            .append('#');
                    if (source.payload() == null) {
                        routeFingerprint.append("no-payload");
                    } else {
                        routeFingerprint.append(java.util.HexFormat.of().formatHex(
                                MessagePackCollectionCodec.shapeDigest(source.payload())));
                    }
                    routeFingerprint.append(';');
                });
        closedDomainIdentity = digestHex(routeFingerprint.toString());
        for (TaggedMessageContracts.Case source : domain.cases()) {
            var tuple = source.payload() == null
                    ? MessagePackCollectionCodec.tuple(
                        MessagePackCollectionCodec.string(),
                        MessagePackCollectionCodec.int64())
                    : MessagePackCollectionCodec.tuple(
                        MessagePackCollectionCodec.string(),
                        MessagePackCollectionCodec.int64(),
                        source.payload());
            String variantIdentity = source.qualifiedName() + "#" + source.tag();
            String id = "oreslang.enum." + domainHash + "." + digestHex(variantIdentity);
            CaseCodec cc = new CaseCodec(source, id, tuple);
            if (entries.putIfAbsent(source.qualifiedName(), cc) != null) {
                throw new IllegalArgumentException("duplicate case in pattern: " + source.qualifiedName());
            }
            // Validate all trusted compiled descriptors at registration.
            // Untrusted frames must never select a decoder by trial/error.
            new MessagePackCollectionCodec.MailboxSchema(id, tuple);
            if (schemas.putIfAbsent(id, cc) != null) {
                throw new IllegalArgumentException("duplicate tagged schema identity");
            }
        }
        byName = Map.copyOf(entries);
        bySchema = Map.copyOf(schemas);
    }

    private static String digestHex(String input) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    @Override
    public byte[] encode(Object value) {
        if (!(value instanceof TaggedValue tagged)) {
            throw new IllegalArgumentException("typed actor mailbox requires enum-tagged value");
        }
        CaseCodec cc = byName.get(tagged.qualifiedName());
        if (cc == null || cc.source().tag() != tagged.tag()) {
            throw new IllegalArgumentException("enum variant is not allowed in actor pattern: "
                    + tagged.qualifiedName());
        }
        if (cc.source().payload() == null) {
            if (tagged.payload() != null) {
                throw new IllegalArgumentException("payloadless enum case cannot carry data");
            }
            return MessagePackCollectionCodec.encode(
                    cc.schemaId(), cc.shape(), List.of(tagged.enumName(), (long) tagged.tag()));
        }
        if (tagged.payload() == null) {
            throw new IllegalArgumentException("enum case requires serializable payload");
        }
        return MessagePackCollectionCodec.encode(cc.schemaId(), cc.shape(),
                List.of(tagged.enumName(), (long) tagged.tag(), tagged.payload()));
    }

    @Override
    public TaggedValue decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "wire message");
        if (bytes.length > MessagePackCollectionCodec.MAX_BYTES) {
            throw new IllegalArgumentException("tagged message exceeds wire byte budget");
        }
        // The sender still owns its bytes. Route and decode only one bounded
        // immutable snapshot, preventing cross-alternative mutable-buffer races.
        byte[] snapshot = bytes.clone();
        String schema = MessagePackActorContract.peekCanonicalSchema(snapshot);
        CaseCodec cc = bySchema.get(schema);
        if (cc == null) {
            throw new IllegalArgumentException("unknown tagged schema in closed actor pattern");
        }
        // Exactly ONE recursive typed decode, regardless of domain cardinality.
        Object decoded = MessagePackCollectionCodec.decode(
                cc.schemaId(), cc.shape(), snapshot);
        if (!(decoded instanceof List<?> value)
                || value.size() != (cc.source().payload() == null ? 2 : 3)
                || !Objects.equals(value.get(0), cc.source().owner())
                || !(value.get(1) instanceof Long tag)
                || tag.longValue() != cc.source().tag()) {
            throw new IllegalArgumentException("enum type/tag mismatch in binary payload");
        }
        return new TaggedValue(cc.source().owner(), cc.source().name(),
                cc.source().tag(), value.size() == 3 ? value.get(2) : null);

    }
}
