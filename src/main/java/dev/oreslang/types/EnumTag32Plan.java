package dev.oreslang.types;

import dev.oreslang.ast.EnumAstIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-only nullary-enum AOT value layout, NOT executable enum admission.
 *
 * The native value is a positive 32-bit wire tag in a statically proven
 * nominal type. A tag NEVER chooses a callable overload at runtime.
 * Declaration/case identity selects overloads; this layout only determines
 * emitted constants and proves explicit tag comparisons/matches.
 */
public final class EnumTag32Plan {
    public static final int MAX_CASES = 1024;

    public record CaseTag(String name, int tag) {
        public CaseTag {
            EnumAstIdentity.requireSimple(name);
            if (tag <= 0) throw new IllegalArgumentException("positive i32 enum tag required");
        }
    }

    private final String qualifiedEnumId;
    private final boolean exported;
    private final Map<String, Integer> tagsByCase;
    private final Set<Integer> validTags;
    private final String schemaFingerprint;

    /** Compatibility-only private layout; source exports must be passed explicitly. */
    public EnumTag32Plan(String qualifiedEnumId, List<CaseTag> declaredCases) {
        this(qualifiedEnumId, declaredCases, false);
    }

    public EnumTag32Plan(String qualifiedEnumId, List<CaseTag> declaredCases,
                         boolean exported) {
        requireQualifiedId(qualifiedEnumId);
        Objects.requireNonNull(declaredCases, "declaredCases");
        if (declaredCases.isEmpty() || declaredCases.size() > MAX_CASES) {
            throw new IllegalArgumentException("nullary AOT enum case budget exceeded");
        }
        List<CaseTag> cases = List.copyOf(declaredCases);
        LinkedHashMap<String, Integer> tags = new LinkedHashMap<>();
        int prior = 0;
        for (CaseTag caseTag : cases) {
            if (caseTag.tag() <= prior || tags.putIfAbsent(caseTag.name(), caseTag.tag()) != null) {
                throw new IllegalArgumentException("duplicate case or non-increasing enum tag");
            }
            prior = caseTag.tag();
        }
        this.qualifiedEnumId = qualifiedEnumId;
        this.exported = exported;
        this.tagsByCase = java.util.Collections.unmodifiableMap(tags);
        this.validTags = Set.copyOf(tags.values());
        this.schemaFingerprint = fingerprint(qualifiedEnumId, exported, cases);
    }

    public String qualifiedEnumId() { return qualifiedEnumId; }
    public boolean exported() { return exported; }
    public String schemaFingerprint() { return schemaFingerprint; }
    public int caseCount() { return tagsByCase.size(); }

    /** Compile-time constant intended for native LLVM i32/Graal lowering. */
    public int constantTag(String verifiedCaseName) {
        Integer tag = tagsByCase.get(verifiedCaseName);
        if (tag == null) throw new IllegalArgumentException("unknown enum case");
        return tag;
    }

    /** Runtime validation of an already chosen nominal envelope; NOT overload selection. */
    public boolean containsWireTag(int tag) {
        if (tag <= 0) return false;
        return validTags.contains(tag);
    }

    /** Reject cached AOT objects built for an old enum payload/tag schema. */
    public void requireExactSchema(String compiledFingerprint) {
        if (!schemaFingerprint.equals(compiledFingerprint)) {
            throw new IllegalArgumentException("stale enum AOT wire layout");
        }
    }

    private static void requireQualifiedId(String identity) {
        if (identity == null || identity.isEmpty()) {
            throw new IllegalArgumentException("missing resolved enum ID");
        }
        if (identity.getBytes(StandardCharsets.UTF_8).length > EnumAstIdentity.MAX_ID_BYTES) {
            throw new IllegalArgumentException("resolved enum ID exceeds canonical ABI byte limit");
        }
        // IDs are resolved by the source compiler; lexical validation here
        // is necessary but never sufficient to certify that provenance.
        String[] segments = identity.split("\\.", -1);
        if (segments.length < 2 || segments.length > 16) {
            throw new IllegalArgumentException("enum AOT ID requires 2..16 components");
        }
        for (String part : segments) EnumAstIdentity.requireSimple(part);
    }

    private static String fingerprint(String identity, boolean exported,
                                      List<CaseTag> cases) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            addField(hash, "ores-nullary-enum-tag32-schema-v2");
            addField(hash, identity);
            addField(hash, exported ? "public" : "private");
            addField(hash, Integer.toString(cases.size()));
            for (CaseTag entry : cases) {
                addField(hash, entry.name());
                hash.update(ByteBuffer.allocate(4).putInt(entry.tag()).array());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 required for AOT schema", absent);
        }
    }

    private static void addField(MessageDigest hash, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
