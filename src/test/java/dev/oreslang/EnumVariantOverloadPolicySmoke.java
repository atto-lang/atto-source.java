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
        // Arity differences always remain separate.
        EnumVariantOverloadPolicy.admit(List.of(sig(n), sig(n, n), sig(n, n, n)));
        System.out.println("PASS: " + checks + " release-active enum overload policy checks (host-only)");
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
