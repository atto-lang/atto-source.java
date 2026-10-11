package dev.oreslang.types;

import dev.oreslang.runtime.TaggedMessageCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiler declaration bridge. The actual wire format is owned solely
 * by TaggedMessageCodec; do not introduce a second variant envelope here. */
public final class TaggedActorMessageBinding {
    private final String domain;
    private final TaggedMessageCodec wire;
    private final Map<String, TaggedMessageContracts.Case> names;
    private final List<String> alternatives;

    private TaggedActorMessageBinding(String domain, TaggedMessageCodec wire,
            Map<String, TaggedMessageContracts.Case> names, List<String> alternatives) {
        this.domain = domain;
        this.wire = wire;
        this.names = Map.copyOf(names);
        this.alternatives = List.copyOf(alternatives);
    }

    public static TaggedActorMessageBinding fromRegistry(
            TaggedMessageContracts.Registry registry, String name) {
        Objects.requireNonNull(registry);
        TaggedMessageContracts.Domain d = registry.requireDomain(name);
        var names = new java.util.LinkedHashMap<String, TaggedMessageContracts.Case>();
        for (var c : d.cases()) {
            if (names.putIfAbsent(c.qualifiedName(), c) != null)
                throw new IllegalArgumentException("duplicate tagged variant");
        }
        return new TaggedActorMessageBinding(d.name(), new TaggedMessageCodec(d),
                names, new ArrayList<>(names.keySet()));
    }

    public String domainName() { return domain; }
    public int variantCount() { return alternatives.size(); }
    public List<String> alternatives() { return alternatives; }

    public byte[] encode(String name, Object value) {
        var c = names.get(name);
        if (c == null) throw new IllegalArgumentException("case is outside compiled pattern");
        return wire.encode(new TaggedMessageCodec.TaggedValue(
                c.owner(), c.name(), c.tag(), value));
    }

    public TaggedMessage decode(byte[] encoded) {
        var found = wire.decode(encoded);
        var c = names.get(found.qualifiedName());
        if (c == null || c.tag() != found.tag())
            throw new IllegalArgumentException("case is outside compiled pattern");
        return new TaggedMessage(found.qualifiedName(), found.tag(), found.payload());
    }

    public record TaggedMessage(String qualifiedCase, int declaredTag, Object payload) {}
}
