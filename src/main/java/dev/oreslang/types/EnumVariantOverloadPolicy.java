package dev.oreslang.types;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private EnumVariantOverloadPolicy() { }

    public enum Domain { MODULE, INSTANCE, STATIC }
    public enum Payload { NULLARY, PRIMITIVE, FIXED_PRIMITIVE_TUPLE, COMPLEX }

    public record EnumInfo(String symbolId, Map<String, Payload> cases) {
        public EnumInfo {
            requireId(symbolId);
            Objects.requireNonNull(cases, "cases");
            if (cases.isEmpty()) throw new IllegalArgumentException("empty enum");
            for (Map.Entry<String, Payload> item : cases.entrySet()) {
                requireName(item.getKey());
                Objects.requireNonNull(item.getValue(), "case payload");
            }
            cases = Map.copyOf(cases);
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
            if (parameters.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("null parameter");
            }
            parameters = List.copyOf(parameters);
        }
        public int arity() { return parameters.size(); }
    }

    /**
     * Require every pair in a same-arity family to be disjoint or strictly
     * nested by declared enum-case refinement. Crossing overlap is forbidden.
     * Differences in ordinary non-enum parameters cannot form overload slots.
     */
    public static void admit(List<Signature> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) throw new IllegalArgumentException("empty family");
        List<Signature> slots = List.copyOf(candidates);
        Signature first = slots.get(0);
        Map<String, EnumInfo> registry = new HashMap<>();
        Map<Integer, Integer> countsByArity = new HashMap<>();
        for (Signature slot : slots) {
            int count = countsByArity.merge(slot.arity(), 1, Integer::sum);
            if (count > MAX_CANDIDATES) throw new IllegalArgumentException("same-arity candidate limit");
            if (!slot.ownerId().equals(first.ownerId()) || slot.domain() != first.domain()
                    || !slot.name().equals(first.name())) {
                throw new IllegalArgumentException("mixed callable family");
            }
            for (Parameter p : slot.parameters()) {
                if (p instanceof EnumParameter e) {
                    EnumInfo prior = registry.putIfAbsent(e.info().symbolId(), e.info());
                    if (prior != null && !prior.equals(e.info())) {
                        throw new IllegalArgumentException("conflicting enum descriptor for nominal ID");
                    }
                }
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).arity() == slots.get(j).arity()
                        && !safePair(slots.get(i), slots.get(j))) {
                    throw new IllegalArgumentException("same-arity enum overloads overlap or have an invalid discriminator");
                }
            }
        }
    }

    public static Signature select(List<Signature> candidates, List<Parameter> staticArguments) {
        admit(candidates);
        Objects.requireNonNull(staticArguments, "staticArguments");
        List<Parameter> args = List.copyOf(staticArguments);
        if (args.size() > MAX_ARITY) throw new IllegalArgumentException("arity limit");
        Map<String, EnumInfo> declared = new HashMap<>();
        for (Signature slot : candidates) {
            for (Parameter p : slot.parameters()) {
                if (p instanceof EnumParameter e) declared.put(e.info().symbolId(), e.info());
            }
        }
        for (Parameter p : args) {
            if (p instanceof EnumParameter e) {
                EnumInfo info = declared.get(e.info().symbolId());
                if (info != null && !info.equals(e.info())) {
                    throw new IllegalArgumentException("stale/forged static enum proof");
                }
            }
        }
        List<Signature> applicable = new ArrayList<>();
        for (Signature slot : candidates) {
            if (slot.arity() != args.size()) continue;
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
        return winner;
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
