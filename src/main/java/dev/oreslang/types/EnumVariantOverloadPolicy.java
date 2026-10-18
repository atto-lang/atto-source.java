package dev.oreslang.types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-only reference policy for statically resolved enum/case overloads.
 * Not wired to TypeChecker, runtime dispatch or the source-language type grammar.
 * A caller must obtain EnumInfo from a trusted, resolved declaration registry.
 */
public final class EnumVariantOverloadPolicy {
    public static final int MAX_ARITY = 64;
    public static final int MAX_CANDIDATES = 128;
    public static final int MAX_CASES = 1024;
    public static final int MAX_TOTAL_CANDIDATES = (MAX_ARITY + 1) * MAX_CANDIDATES;

    private EnumVariantOverloadPolicy() { }

    public enum Domain { MODULE, INSTANCE, STATIC }
    public enum Payload { NULLARY, PRIMITIVE, FIXED_PRIMITIVE_TUPLE, COMPLEX }

    public record EnumInfo(String symbolId, Map<String, Payload> cases) {
        public EnumInfo {
            requireId(symbolId);
            Objects.requireNonNull(cases, "cases");
            if (cases.size() > MAX_CASES) throw new IllegalArgumentException("enum case limit");
            cases = Map.copyOf(cases);
            if (cases.isEmpty() || cases.size() > MAX_CASES) {
                throw new IllegalArgumentException("empty or oversized enum case set");
            }
            for (Map.Entry<String, Payload> item : cases.entrySet()) {
                requireName(item.getKey());
                Objects.requireNonNull(item.getValue(), "case payload");
            }
        }

        public boolean eligible() {
            return cases.values().stream().noneMatch(p -> p == Payload.COMPLEX);
        }
    }

    public sealed interface Parameter permits Primitive, EnumParameter { }

    public record Primitive(String id) implements Parameter {
        private static final Set<String> ALLOWED = Set.of(
                "bool", "int", "float", "string", "byte", "char", "i8", "i16",
                "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64");
        public Primitive {
            if (!ALLOWED.contains(id)) throw new IllegalArgumentException("unsupported primitive: " + id);
        }
    }

    /** A null caseId is the whole enum type, rather than a specific case. */
    public record EnumParameter(EnumInfo info, String caseId) implements Parameter {
        public EnumParameter {
            Objects.requireNonNull(info, "info");
            if (!info.eligible()) throw new IllegalArgumentException("enum payload is not overload-eligible");
            if (caseId != null && !info.cases().containsKey(caseId)) {
                throw new IllegalArgumentException("case not declared by enum");
            }
        }
        public static EnumParameter whole(EnumInfo info) { return new EnumParameter(info, null); }
        public static EnumParameter variant(EnumInfo info, String caseId) {
            Objects.requireNonNull(caseId, "caseId");
            return new EnumParameter(info, caseId);
        }
    }

    public record Signature(String ownerId, Domain domain, String name, List<Parameter> parameters) {
        public Signature {
            requireId(ownerId);
            Objects.requireNonNull(domain, "domain");
            requireName(name);
            Objects.requireNonNull(parameters, "parameters");
            if (parameters.size() > MAX_ARITY) throw new IllegalArgumentException("arity limit");
            parameters = List.copyOf(parameters);
            if (parameters.size() > MAX_ARITY) throw new IllegalArgumentException("arity changed during snapshot");
        }
        public int arity() { return parameters.size(); }
    }

    /**
     * Require every pair in a same-arity family to be disjoint or strictly
     * nested by declared enum-case refinement. Crossing overlap is forbidden.
     * Differences in ordinary non-enum parameters cannot form overload slots.
     */

    public static void admit(List<Signature> candidates) {
        validateFamily(snapshot(candidates));
    }

    /** A single bounded immutable snapshot prevents caller-list mutation between
     * admission and construction of the prepared compiler image. */
    private static List<Signature> snapshot(List<Signature> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || candidates.size() > MAX_TOTAL_CANDIDATES) {
            throw new IllegalArgumentException("empty or oversized callable family");
        }
        List<Signature> slots = List.copyOf(candidates);
        if (slots.isEmpty() || slots.size() > MAX_TOTAL_CANDIDATES) {
            throw new IllegalArgumentException("callable family changed during snapshot");
        }
        return slots;
    }

    private static void validateFamily(List<Signature> candidates) {
        Signature first = Objects.requireNonNull(candidates.get(0), "candidate");
        Map<String, EnumInfo> registry = new HashMap<>();
        Map<Integer, List<Signature>> byArity = new HashMap<>();
        for (Signature slot : candidates) {
            Objects.requireNonNull(slot, "candidate");
            if (!slot.ownerId().equals(first.ownerId()) || slot.domain() != first.domain()
                    || !slot.name().equals(first.name())) {
                throw new IllegalArgumentException("mixed callable family");
            }
            List<Signature> group = byArity.computeIfAbsent(slot.arity(), ignored -> new ArrayList<>());
            if (group.size() >= MAX_CANDIDATES) {
                throw new IllegalArgumentException("same-arity candidate limit");
            }
            group.add(slot);
            for (Parameter p : slot.parameters()) {
                if (p instanceof EnumParameter e) {
                    EnumInfo prior = registry.putIfAbsent(e.info().symbolId(), e.info());
                    if (prior != null && !prior.equals(e.info())) {
                        throw new IllegalArgumentException("conflicting enum descriptor for nominal ID");
                    }
                }
            }
        }
        // Pair comparisons are bounded *within* each arity, never a quadratic
        // walk across unrelated arities. No declaration-order tie breaker.
        for (List<Signature> group : byArity.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (!safePair(group.get(i), group.get(j))) {
                        throw new IllegalArgumentException(
                                "same-arity enum overloads overlap or have an invalid discriminator");
                    }
                }
            }
        }
    }

    /** Prepare a single immutable compiler family once, rather than revalidating
     * all declaration pairs at every call site. This is a host-only proof plan. */
    public static PreparedFamily prepare(List<Signature> candidates) {
        List<Signature> slots = snapshot(candidates);
        validateFamily(slots);
        return new PreparedFamily(slots);
    }

    /** Compatibility oracle: use {@link #prepare} once for production checker integration. */
    public static Signature select(List<Signature> candidates, List<Parameter> staticArguments) {
        return prepare(candidates).bind(staticArguments).signature();
    }

    public static final class PreparedFamily {
        private final Map<Integer, List<Signature>> byArity;
        private final Map<String, EnumInfo> registeredEnums;
        private final String schemaFingerprint;

        private PreparedFamily(List<Signature> checked) {
            Map<Integer, List<Signature>> groups = new HashMap<>();
            Map<String, EnumInfo> enums = new HashMap<>();
            for (Signature slot : checked) {
                groups.computeIfAbsent(slot.arity(), ignored -> new ArrayList<>()).add(slot);
                for (Parameter p : slot.parameters()) {
                    if (p instanceof EnumParameter e) enums.put(e.info().symbolId(), e.info());
                }
            }
            groups.replaceAll((arity, group) -> List.copyOf(group));
            byArity = Map.copyOf(groups);
            registeredEnums = Map.copyOf(enums);
            schemaFingerprint = fingerprint(checked, registeredEnums);
        }

        /** Non-authoritative cache/rebuild fingerprint, never an overload identity by itself. */
        public String schemaFingerprint() { return schemaFingerprint; }

        /** Statically bind a call without runtime enum-tag inspection. */
        public BoundTarget bind(List<Parameter> staticArguments) {
            Objects.requireNonNull(staticArguments, "staticArguments");
            if (staticArguments.size() > MAX_ARITY) throw new IllegalArgumentException("arity limit");
            List<Parameter> args = List.copyOf(staticArguments);
            for (Parameter p : args) {
                if (p instanceof EnumParameter e) {
                    EnumInfo registered = registeredEnums.get(e.info().symbolId());
                    if (registered != null && !registered.equals(e.info())) {
                        throw new IllegalArgumentException("stale/forged static enum proof");
                    }
                }
            }
            List<Signature> candidates = byArity.getOrDefault(args.size(), List.of());
            List<Signature> applicable = new ArrayList<>();
            for (Signature slot : candidates) {
                boolean matches = true;
                for (int i = 0; i < args.size(); i++) {
                    if (!subset(args.get(i), slot.parameters().get(i))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) applicable.add(slot);
            }
            if (applicable.isEmpty()) throw new IllegalArgumentException("no statically provable overload");
            Signature winner = null;
            for (Signature candidate : applicable) {
                boolean dominates = true;
                for (Signature other : applicable) {
                    if (!signatureSubset(candidate, other)) { dominates = false; break; }
                }
                if (dominates) {
                    if (winner != null) throw new IllegalArgumentException("ambiguous overload");
                    winner = candidate;
                }
            }
            if (winner == null) throw new IllegalArgumentException("ambiguous overload");
            return new BoundTarget(this, winner);
        }
    }

    /** Carries the selected declaration and its owning compiler family by identity.
     * Unrelated/hot-reloaded families cannot silently reuse this binding. */
    public static final class BoundTarget {
        private final PreparedFamily family;
        private final Signature signature;
        private final String slotSymbol;
        private BoundTarget(PreparedFamily family, Signature signature) {
            this.family = family;
            this.signature = signature;
            this.slotSymbol = symbol(signature);
        }
        public Signature signature() { return signature; }
        public String slotSymbol() { return slotSymbol; }
        public String schemaFingerprint() { return family.schemaFingerprint(); }
        public void requireLinkedTo(PreparedFamily current) {
            if (current != family) {
                throw new IllegalArgumentException("bound overload belongs to another compilation image");
            }
        }
    }


    /**
     * Host-only closed-world AOT linker proof. A compiler supplies one exact
     * implementation digest per previously admitted declaration. This cannot
     * compile an Oreslang guest: it verifies static relocation identity only.
     *
     * An image is tied to one PreparedFamily instance, not just its digest.
     * Publishing a name/arity dispatcher as an escape hatch is forbidden.
     */
    public static final class AotImage {
        private final PreparedFamily family;
        private final Map<String, String> implementations;

        private AotImage(PreparedFamily family, Map<String, String> implementations) {
            this.family = Objects.requireNonNull(family, "prepared compiler family");
            // A virtual receiver requires an exact-signature vtable or an explicit
            // sealed/final-receiver proof. A direct body relocation is unsound.
            for (List<Signature> group : family.byArity.values()) {
                for (Signature declaration : group) {
                    if (declaration.domain() == Domain.INSTANCE) {
                        throw new IllegalArgumentException("instance method requires virtual-slot AOT lowering");
                    }
                }
            }
            Objects.requireNonNull(implementations, "compiled implementations");
            Map<String, String> expected = new HashMap<>();
            for (List<Signature> arityGroup : family.byArity.values()) {
                for (Signature slot : arityGroup) {
                    String name = symbol(slot);
                    if (expected.putIfAbsent(name, "") != null) {
                        throw new IllegalArgumentException("AOT symbol collision");
                    }
                }
            }
            if (!implementations.keySet().equals(expected.keySet())) {
                throw new IllegalArgumentException("AOT link missing or extraneous definition");
            }
            // Snapshot both the key set and digests. Reject noncanonical hashes
            // before any relocation is published, including for unused slots.
            Map<String, String> snapshot = Map.copyOf(implementations);
            for (String digest : snapshot.values()) {
                if (!isCanonicalSha256(digest)) {
                    throw new IllegalArgumentException("AOT definition requires lowercase SHA-256");
                }
            }
            this.implementations = snapshot;
        }

        public DirectRelocation relocate(BoundTarget target) {
            Objects.requireNonNull(target, "bound compiler target");
            target.requireLinkedTo(family);
            String body = implementations.get(target.slotSymbol());
            if (body == null) {
                throw new IllegalArgumentException("unresolved AOT relocation");
            }
            return new DirectRelocation(this, target.slotSymbol(), body);
        }

        public void requireImage(DirectRelocation call) {
            if (call == null || call.image != this) {
                throw new IllegalArgumentException("relocation from another AOT image");
            }
        }

        public int definitionCount() { return implementations.size(); }
    }

    public static AotImage sealAotImage(PreparedFamily family, Map<String, String> compiledDefinitions) {
        return new AotImage(family, compiledDefinitions);
    }

    /** Opaque host compiler relocation; not constructible by source-language guests. */
    public static final class DirectRelocation {
        private final AotImage image;
        private final String targetSymbol;
        private final String exactBodyDigest;

        private DirectRelocation(AotImage image, String targetSymbol, String exactBodyDigest) {
            this.image = image;
            this.targetSymbol = targetSymbol;
            this.exactBodyDigest = exactBodyDigest;
        }

        public String targetSymbol() { return targetSymbol; }
        public String exactBodyDigest() { return exactBodyDigest; }
        public String schemaFingerprint() { return image.family.schemaFingerprint(); }
    }

    private static boolean isCanonicalSha256(String digest) {
        if (digest == null || digest.length() != 64) return false;
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String fingerprint(List<Signature> declarations, Map<String, EnumInfo> enums) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestField(digest, "ores-enum-variant-family-schema-v1");
            List<String> slots = declarations.stream().map(EnumVariantOverloadPolicy::symbol).sorted().toList();
            digestField(digest, Integer.toString(slots.size()));
            for (String slot : slots) digestField(digest, slot);
            List<EnumInfo> infos = enums.values().stream()
                    .sorted(Comparator.comparing(EnumInfo::symbolId)).toList();
            digestField(digest, Integer.toString(infos.size()));
            for (EnumInfo info : infos) {
                digestField(digest, info.symbolId());
                List<String> cases = info.cases().keySet().stream().sorted().toList();
                digestField(digest, Integer.toString(cases.size()));
                for (String caseId : cases) {
                    digestField(digest, caseId);
                    digestField(digest, info.cases().get(caseId).name());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossibleOnSupportedJdks) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnSupportedJdks);
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean safePair(Signature left, Signature right) {
        boolean anyDifference = false;
        boolean disjoint = false;
        boolean leftOnly = false;
        boolean rightOnly = false;
        for (int i = 0; i < left.arity(); i++) {
            Parameter a = left.parameters().get(i);
            Parameter b = right.parameters().get(i);
            if (a.equals(b)) continue;
            if (!(a instanceof EnumParameter ae) || !(b instanceof EnumParameter be)) {
                return false;
            }
            anyDifference = true;
            if (!ae.info().equals(be.info())) {
                if (ae.info().symbolId().equals(be.info().symbolId())) return false;
                disjoint = true;
            } else if (ae.caseId() == null) {
                rightOnly = true;
            } else if (be.caseId() == null) {
                leftOnly = true;
            } else {
                disjoint = true;
            }
        }
        return anyDifference && (disjoint || !(leftOnly && rightOnly));
    }

    private static boolean signatureSubset(Signature left, Signature right) {
        if (left.arity() != right.arity()) return false;
        for (int i = 0; i < left.arity(); i++) {
            if (!subset(left.parameters().get(i), right.parameters().get(i))) return false;
        }
        return true;
    }

    private static boolean subset(Parameter actual, Parameter formal) {
        if (actual.equals(formal)) return true;
        if (actual instanceof EnumParameter a && formal instanceof EnumParameter b) {
            return a.info().equals(b.info()) && a.caseId() != null && b.caseId() == null;
        }
        return false;
    }

    /** Deterministic, length-delimited UTF-8 symbol (not a source-call selector). */
    public static String symbol(Signature signature) {
        Objects.requireNonNull(signature, "signature");
        StringBuilder out = new StringBuilder("ores-enum-variant-slot-v1");
        field(out, signature.ownerId());
        field(out, signature.domain().name());
        field(out, signature.name());
        field(out, Integer.toString(signature.arity()));
        for (Parameter p : signature.parameters()) {
            if (p instanceof Primitive primitive) {
                field(out, "P"); field(out, primitive.id());
            } else if (p instanceof EnumParameter en) {
                field(out, en.caseId() == null ? "E" : "V");
                field(out, en.info().symbolId());
                if (en.caseId() != null) field(out, en.caseId());
            } else throw new IllegalArgumentException("unknown key");
        }
        return out.toString();
    }

    private static void field(StringBuilder out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.append('|').append(bytes.length).append(':');
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16))
                .append(Character.forDigit(b & 15, 16));
    }

    private static void requireId(String s) {
        requireName(s);
        if (!s.contains("::") || s.startsWith("::") || s.endsWith("::")) {
            throw new IllegalArgumentException("unqualified identity");
        }
    }
    private static void requireName(String s) {
        if (s == null || s.isBlank() || s.length() > 512) {
            throw new IllegalArgumentException("blank or oversized symbol");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) i++;
                else throw new IllegalArgumentException("malformed UTF-16 symbol");
            } else if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("control in symbol");
            }
        }
    }
}
