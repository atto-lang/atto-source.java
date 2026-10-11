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
    private final List<CaseCodec> cases;

    public TaggedMessageCodec(TaggedMessageContracts.Domain domain) {
        Objects.requireNonNull(domain);
        if (domain.cases().isEmpty() || domain.cases().size() > 4096) {
            throw new IllegalArgumentException("tagged actor domain must have 1..4096 cases");
        }
        Map<String, CaseCodec> entries = new HashMap<>();
        ArrayList<CaseCodec> ordered = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder("oreslang.tagged.domain.v1/");
        fingerprint.append(domain.name()).append('/');
        domain.cases().stream().map(TaggedMessageContracts.Case::qualifiedName)
                .sorted().forEach(n -> fingerprint.append(n.length()).append(':').append(n).append(';'));
        String domainHash = digestHex(fingerprint.toString());
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
            ordered.add(cc);
        }
        byName = Map.copyOf(entries);
        cases = List.copyOf(ordered);
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
        Objects.requireNonNull(bytes);
        // We deliberately do not derive a codec from any untrusted embedded
        // type name. Only compiled-in alternatives can ever be tried.
        for (CaseCodec cc : cases) {
            final Object decoded;
            try {
                decoded = MessagePackCollectionCodec.decode(cc.schemaId(), cc.shape(), bytes);
            } catch (IllegalArgumentException rejectedCase) {
                continue;
            }
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
        throw new IllegalArgumentException(
                "unknown, noncanonical or incompatible enum message in this actor pattern");
    }
}
