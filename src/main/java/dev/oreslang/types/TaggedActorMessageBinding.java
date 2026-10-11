package dev.oreslang.types;

import dev.oreslang.runtime.MessagePackActorContract;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-visible bridge from source `define enum` / `define pattern`
 * declarations into an exact, closed MessagePack v2 inbox contract.
 *
 * This is declaration/host transport plumbing, NOT yet lowering of guest
 * `actor.send` or `on Variant` match arms. IDs are local to the COMPLETE
 * fingerprinted domain; a differently ordered/revised domain is not wire
 * compatible even if two alternatives have the same payload shape.
 */
public final class TaggedActorMessageBinding {
    private static final String FINGERPRINT_VERSION = "oreslang.message.domain.v2\n";
    private final String declaredName;
    private final String fingerprint;
    private final MessagePackActorContract codec;
    private final Map<String, Integer> ordinals;
    private final Map<Integer, TaggedMessageContracts.Case> cases;

    private TaggedActorMessageBinding(String declaredName, String fingerprint,
                                       MessagePackActorContract codec,
                                       Map<String, Integer> ordinals,
                                       Map<Integer, TaggedMessageContracts.Case> cases) {
        this.declaredName = declaredName;
        this.fingerprint = fingerprint;
        this.codec = codec;
        this.ordinals = Map.copyOf(ordinals);
        this.cases = Map.copyOf(cases);
    }

    public static TaggedActorMessageBinding fromRegistry(
            TaggedMessageContracts.Registry registry, String domainName) {
        Objects.requireNonNull(registry, "compiler-owned registry");
        Objects.requireNonNull(domainName, "message pattern or enum name");
        TaggedMessageContracts.Domain domain = registry.requireDomain(domainName);
        if (domain.cases().isEmpty() || domain.cases().size() > MessagePackActorContract.MAX_VARIANTS) {
            throw new IllegalArgumentException("closed message domain requires 1-64 cases");
        }
        // Canonical fingerprint encodes identity, tag, shape and order; never
        // fall back to interpreting a message solely from its payload type.
        StringBuilder canonical = new StringBuilder(FINGERPRINT_VERSION)
                .append(domainName.length()).append(':').append(domainName).append('\n');
        ArrayList<MessagePackActorContract.Alternative> alternatives = new ArrayList<>();
        HashMap<String, Integer> byName = new HashMap<>();
        HashMap<Integer, TaggedMessageContracts.Case> byOrdinal = new HashMap<>();
        int ordinal = 0;
        for (TaggedMessageContracts.Case entry : domain.cases()) {
            Objects.requireNonNull(entry, "case");
            String qualified = entry.qualifiedName();
            if (entry.tag() < 1 || !qualified.matches("[A-Za-z_][A-Za-z_0-9]*\\.[A-Za-z_][A-Za-z_0-9]*")) {
                throw new IllegalArgumentException("invalid qualified enum case identity");
            }
            if (entry.payload() == null) {
                throw new IllegalArgumentException("valueless enum messages have no MessagePack v2 unit codec yet: "
                        + qualified);
            }
            byte[] digest = MessagePackCollectionCodec.shapeDigest(entry.payload());
            int id = ++ordinal;
            if (byName.putIfAbsent(qualified, id) != null) {
                throw new IllegalArgumentException("duplicate enum variant in message domain: " + qualified);
            }
            byOrdinal.put(id, entry);
            canonical.append(qualified.length()).append(':').append(qualified)
                    .append('#').append(entry.tag()).append('#')
                    .append(HexFormat.of().formatHex(digest)).append('\n');
            alternatives.add(new MessagePackActorContract.Alternative(id, entry.payload()));
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 provider unavailable", impossible);
        }
        // Include every enum-qualified declaration in the schema identity.
        // Any schema evolution or reorder must fail to decode old bytes until
        // an explicit versioned migration is implemented.
        String wireDomain = domainName + "#sha256=" + hash;
        MessagePackActorContract codec = new MessagePackActorContract(
                wireDomain, alternatives.toArray(MessagePackActorContract.Alternative[]::new));
        return new TaggedActorMessageBinding(domainName, hash, codec, byName, byOrdinal);
    }

    public String domainName() { return declaredName; }
    public String fingerprint() { return fingerprint; }
    public int variantCount() { return cases.size(); }
    public String wireDomain() { return declaredName + "#sha256=" + fingerprint; }
    public List<String> alternatives() {
        ArrayList<String> names = new ArrayList<>(cases.size());
        for (int i = 1; i <= cases.size(); i++) names.add(cases.get(i).qualifiedName());
        return List.copyOf(names);
    }
    public byte[] encode(String qualifiedEnumCase, Object payload) {
        Integer id = ordinals.get(qualifiedEnumCase);
        if (id == null) throw new IllegalArgumentException(
                "message case is not in the receiver's closed pattern: " + qualifiedEnumCase);
        return codec.encode(id, payload);
    }
    public TaggedMessage decode(byte[] encoded) {
        MessagePackActorContract.TaggedMessage data = codec.decode(encoded);
        TaggedMessageContracts.Case entry = cases.get(data.variantId());
        if (entry == null) throw new IllegalArgumentException("unknown message ordinal");
        return new TaggedMessage(entry.qualifiedName(), entry.tag(), data.value());
    }
    public record TaggedMessage(String qualifiedCase, int declaredTag, Object payload) {}
}
