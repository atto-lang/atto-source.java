package dev.oreslang;

import dev.oreslang.types.EnumVariantOverloadPolicy;
import dev.oreslang.types.EnumVariantOverloadPolicy.Domain;
import dev.oreslang.types.EnumVariantOverloadPolicy.EnumInfo;
import dev.oreslang.types.EnumVariantOverloadPolicy.EnumParameter;
import dev.oreslang.types.EnumVariantOverloadPolicy.Parameter;
import dev.oreslang.types.EnumVariantOverloadPolicy.Payload;
import dev.oreslang.types.EnumVariantOverloadPolicy.Primitive;
import dev.oreslang.types.EnumVariantOverloadPolicy.Signature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Standalone release-active host policy smoke; not guest/compiler integration evidence. */
public final class EnumVariantOverloadPolicySmoke {
    private static int checks;
    private EnumVariantOverloadPolicySmoke() { }

    public static void main(String[] ignored) {
        EnumInfo foo = new EnumInfo("file::Foo", Map.of("A", Payload.NULLARY,
                "B", Payload.NULLARY, "C", Payload.PRIMITIVE));
        EnumInfo bar = new EnumInfo("file::Bar", Map.of("A", Payload.NULLARY,
                "C", Payload.FIXED_PRIMITIVE_TUPLE));
        EnumParameter f = EnumParameter.whole(foo);
        EnumParameter a = EnumParameter.variant(foo, "A");
        EnumParameter b = EnumParameter.variant(foo, "B");
        EnumParameter c = EnumParameter.variant(foo, "C");
        EnumParameter g = EnumParameter.whole(bar);
        Primitive n = new Primitive("int");
        Signature general = sig(f, n);
        Signature specializedA = sig(a, n);
        Signature specializedB = sig(b, n);
        Signature unrelated = sig(g, n);
        List<Signature> family = List.of(general, specializedA, specializedB, unrelated);
        EnumVariantOverloadPolicy.admit(family);
        var compiled = EnumVariantOverloadPolicy.prepare(family);
        List<Signature> mutableDeclarations = new ArrayList<>(family);
        var frozenPlan = EnumVariantOverloadPolicy.prepare(mutableDeclarations);
        mutableDeclarations.clear();
        eq(specializedA, frozenPlan.bind(List.of(a, n)).signature());
        var target = compiled.bind(List.of(a, n));
        eq(specializedA, target.signature());
        eq(EnumVariantOverloadPolicy.symbol(specializedA), target.slotSymbol());
        eq(compiled.schemaFingerprint(), target.schemaFingerprint());
        target.requireLinkedTo(compiled);
        rejected(() -> target.requireLinkedTo(EnumVariantOverloadPolicy.prepare(family)));
        List<Signature> permuted = new ArrayList<>(family);
        Collections.reverse(permuted);
        eq(compiled.schemaFingerprint(), EnumVariantOverloadPolicy.prepare(permuted).schemaFingerprint());
        eq(compiled.schemaFingerprint(), EnumVariantOverloadPolicy.prepare(List.of(
                general, specializedB, unrelated, specializedA)).schemaFingerprint());
        EnumInfo changedPayload = new EnumInfo("file::Foo", Map.of("A", Payload.PRIMITIVE,
                "B", Payload.NULLARY, "C", Payload.PRIMITIVE));
        check(!compiled.schemaFingerprint().equals(EnumVariantOverloadPolicy.prepare(List.of(
                sig(EnumParameter.whole(changedPayload), n))).schemaFingerprint()));
        EnumInfo changedCaseSet = new EnumInfo("file::Foo", Map.of("A", Payload.NULLARY,
                "B", Payload.NULLARY, "C", Payload.PRIMITIVE, "D", Payload.NULLARY));
        check(!compiled.schemaFingerprint().equals(EnumVariantOverloadPolicy.prepare(List.of(
                sig(EnumParameter.whole(changedCaseSet), n))).schemaFingerprint()));
        rejected(() -> compiled.bind(List.of(EnumParameter.whole(changedCaseSet), n)));
        eq(unrelated, compiled.bind(List.of(g, n)).signature());
        eq(specializedA, EnumVariantOverloadPolicy.select(family, List.of(a, n)));
        eq(specializedB, EnumVariantOverloadPolicy.select(family, List.of(b, n)));
        eq(general, EnumVariantOverloadPolicy.select(family, List.of(c, n)));
        eq(general, EnumVariantOverloadPolicy.select(family, List.of(f, n)));
        eq(unrelated, EnumVariantOverloadPolicy.select(family, List.of(g, n)));
        eq(general, EnumVariantOverloadPolicy.select(List.of(general), List.of(a, n)));
        rejected(() -> EnumVariantOverloadPolicy.select(List.of(specializedA, specializedB), List.of(f, n)));
        rejected(() -> EnumVariantOverloadPolicy.select(family, List.of(new Primitive("bool"), n)));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(general, general)));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(n), sig(new Primitive("bool")))));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(a, f), sig(f, b))));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(a, n), sig(b, new Primitive("bool")))));
        rejected(() -> new Primitive("Any"));
        rejected(() -> new Primitive("record{x:int}"));
        rejected(() -> EnumParameter.variant(foo, "Missing"));
        rejected(() -> new EnumInfo("Foo", Map.of("A", Payload.NULLARY)));
        rejected(() -> new EnumInfo("file::\uD800", Map.of("A", Payload.NULLARY)));
        rejected(() -> EnumParameter.whole(new EnumInfo("file::Foo", Map.of("A", Payload.COMPLEX))));
        Map<String, Payload> tooManyCases = new java.util.HashMap<>();
        for (int i = 0; i < EnumVariantOverloadPolicy.MAX_CASES + 1; i++) {
            tooManyCases.put("Case" + i, Payload.NULLARY);
        }
        rejected(() -> new EnumInfo("file::TooMany", tooManyCases));
        rejected(() -> EnumVariantOverloadPolicy.prepare(Collections.nCopies(
                EnumVariantOverloadPolicy.MAX_TOTAL_CANDIDATES + 1, sig(n))));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(f), sig(EnumParameter.whole(
                new EnumInfo("file::Foo", Map.of("A", Payload.NULLARY)))))));
        rejected(() -> EnumVariantOverloadPolicy.select(List.of(general), List.of(
                EnumParameter.whole(new EnumInfo("file::Foo", Map.of("A", Payload.NULLARY))), n)));
        rejected(() -> new Signature("file::module", Domain.MODULE, "f",
                Collections.nCopies(65, n)));
        rejected(() -> EnumVariantOverloadPolicy.admit(Collections.nCopies(129, sig(n))));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(f),
                new Signature("file::Other", Domain.MODULE, "f", List.of(f)))));
        String keyA = EnumVariantOverloadPolicy.symbol(specializedA);
        eq("ores-enum-variant-slot-v1|12:66696c653a3a6d6f64756c65|6:4d4f44554c45|1:66"
                + "|1:32|1:56|9:66696c653a3a466f6f|1:41|1:50|3:696e74", keyA);
        String keyB = EnumVariantOverloadPolicy.symbol(specializedB);
        check(!keyA.equals(keyB));
        check(!EnumVariantOverloadPolicy.symbol(general).equals(keyA));
        check(!EnumVariantOverloadPolicy.symbol(sig(a)).equals(EnumVariantOverloadPolicy.symbol(sig(f))));
        // Reordering declarations or renaming other enum cases never selects a different overload.
        List<Signature> reverse = new ArrayList<>(family);
        Collections.reverse(reverse);
        for (Parameter actual : List.of(a, b, c, f, g)) {
            eq(EnumVariantOverloadPolicy.select(family, List.of(actual, n)),
                    EnumVariantOverloadPolicy.select(reverse, List.of(actual, n)));
        }
        // Product-domain tests: nested chains permitted; incomparable overlaps rejected.
        EnumVariantOverloadPolicy.admit(List.of(sig(f, f), sig(a, f), sig(a, b)));
        eq(sig(a, b), EnumVariantOverloadPolicy.select(List.of(sig(f, f), sig(a, f), sig(a, b)),
                List.of(a, b)));
        EnumVariantOverloadPolicy.admit(List.of(sig(a, b), sig(c, f)));
        rejected(() -> EnumVariantOverloadPolicy.admit(List.of(sig(a, f), sig(f, b))));
        // Limits are PER ARITY, not per callable name: 129 total across 65 arities is legal.
        List<Signature> acrossArities = new ArrayList<>();
        acrossArities.add(sig());
        for (int size = 1; size <= 64; size++) {
            List<Parameter> argsA = new ArrayList<>();
            List<Parameter> argsB = new ArrayList<>();
            argsA.add(a);
            argsB.add(b);
            for (int pos = 1; pos < size; pos++) {
                argsA.add(n);
                argsB.add(n);
            }
            acrossArities.add(new Signature("file::module", Domain.MODULE, "f", argsA));
            acrossArities.add(new Signature("file::module", Domain.MODULE, "f", argsB));
        }
        eq(129, acrossArities.size());
        EnumVariantOverloadPolicy.admit(acrossArities);
        // Arity differences always remain separate.
        EnumVariantOverloadPolicy.admit(List.of(sig(n), sig(n, n), sig(n, n, n)));
        exhaustivePairOracle(List.of(f, a, b, c, g,
                EnumParameter.variant(bar, "A"), EnumParameter.variant(bar, "C"),
                n, new Primitive("bool")));
        System.out.println("PASS: " + checks + " release-active enum overload policy checks (host-only)");
    }

    /** Independently compare admission to finite cartesian-product case sets. */
    private static void exhaustivePairOracle(List<Parameter> types) {
        List<Signature> signatures = new ArrayList<>();
        for (Parameter x : types) {
            for (Parameter y : types) signatures.add(sig(x, y));
        }
        for (int i = 0; i < signatures.size(); i++) {
            for (int j = i + 1; j < signatures.size(); j++) {
                Signature x = signatures.get(i);
                Signature y = signatures.get(j);
                boolean expected = oraclePair(x, y);
                boolean admitted;
                try {
                    EnumVariantOverloadPolicy.admit(List.of(x, y));
                    admitted = true;
                } catch (IllegalArgumentException e) {
                    admitted = false;
                }
                eq(expected, admitted);
            }
        }
    }

    private static boolean oraclePair(Signature a, Signature b) {
        for (int i = 0; i < a.arity(); i++) {
            Parameter x = a.parameters().get(i);
            Parameter y = b.parameters().get(i);
            if ((x instanceof Primitive || y instanceof Primitive) && !x.equals(y)) {
                return false; // ordinary scalar differences cannot discriminate
            }
        }
        Set<String> left = product(a);
        Set<String> right = product(b);
        if (left.equals(right)) return false;
        Set<String> overlap = new HashSet<>(left);
        overlap.retainAll(right);
        return overlap.isEmpty() || left.containsAll(right) || right.containsAll(left);
    }

    private static Set<String> product(Signature s) {
        Set<String> items = Set.of("");
        for (Parameter p : s.parameters()) {
            Set<String> next = new HashSet<>();
            for (String prefix : items) {
                for (String member : atoms(p)) next.add(prefix + "/" + member);
            }
            items = next;
        }
        return items;
    }

    private static Set<String> atoms(Parameter p) {
        if (p instanceof Primitive primitive) return Set.of("P:" + primitive.id());
        EnumParameter e = (EnumParameter) p;
        if (e.caseId() != null) return Set.of(e.info().symbolId() + ":" + e.caseId());
        Set<String> cases = new HashSet<>();
        for (String v : e.info().cases().keySet()) cases.add(e.info().symbolId() + ":" + v);
        return cases;
    }

    private static Signature sig(Parameter... parameters) {
        return new Signature("file::module", Domain.MODULE, "f", List.of(parameters));
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("failed check " + checks);
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }

    private static void rejected(Runnable action) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection for check " + checks);
    }
}
