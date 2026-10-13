package dev.oreslang;

import dev.oreslang.ast.TypedOverloadResolver;
import dev.oreslang.ast.TypedOverloadResolver.Candidate;
import dev.oreslang.ast.TypedOverloadResolver.Domain;
import dev.oreslang.ast.TypedOverloadResolver.Signature;
import dev.oreslang.ast.TypedOverloadResolver.TypeKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class TypedOverloadResolverTest {
    private static TypeKey primitive(String name) { return TypeKey.primitive(name); }
    private static Candidate<String> candidate(String label, TypeKey... params) {
        return new Candidate<>(label,
                new Signature(Domain.MODULE, "library.pick", List.of(params)));
    }

    @Test
    void exactPrimitiveOverloadsBeatNumericConversionsRegardlessOfDeclarationOrder() {
        Candidate<String> floating = candidate("float", primitive("float"));
        Candidate<String> integer = candidate("int", primitive("int"));
        for (List<Candidate<String>> order : List.of(List.of(floating, integer), List.of(integer, floating))) {
            assertEquals("int", TypedOverloadResolver.select(
                    Domain.MODULE, "library.pick", List.of(primitive("int")), order).declaration());
            assertEquals("float", TypedOverloadResolver.select(
                    Domain.MODULE, "library.pick", List.of(primitive("float")), order).declaration());
        }
    }

    @Test
    void numericPromotionRequiresOneUniqueBestCandidate() {
        Candidate<String> onlyFloat = candidate("float", primitive("float"));
        assertEquals("float", TypedOverloadResolver.select(
                Domain.MODULE, "library.pick", List.of(primitive("int")),
                List.of(onlyFloat)).declaration());

        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class,
                () -> TypedOverloadResolver.select(
                        Domain.MODULE, "library.pick", List.of(primitive("int")),
                        List.of(candidate("float", primitive("float")),
                                candidate("decimal", primitive("decimal")))));
        assertTrue(ambiguous.getMessage().contains("ambiguous overload"));
    }

    @Test
    void incomparableMultiParameterCandidatesAreAmbiguousNotSourceOrdered() {
        Candidate<String> left = candidate("left", primitive("int"), primitive("float"));
        Candidate<String> right = candidate("right", primitive("float"), primitive("int"));
        for (List<Candidate<String>> order : List.of(List.of(left, right), List.of(right, left))) {
            IllegalArgumentException ambiguous = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypedOverloadResolver.select(
                            Domain.MODULE, "library.pick",
                            List.of(primitive("int"), primitive("int")), order));
            assertTrue(ambiguous.getMessage().contains("ambiguous overload"));
        }
    }

    @Test
    void nominalEnumTypeIdentitiesCannotBeConfusedAcrossModulesOrVariants() {
        TypeKey colors = TypeKey.nominalEnum("graphics.Color");
        TypeKey unrelated = TypeKey.nominalEnum("weather.Color");
        TypeKey directions = TypeKey.nominalEnum("graphics.Direction");
        List<Candidate<String>> candidates = List.of(
                candidate("graphics color", colors),
                candidate("weather color", unrelated),
                candidate("direction", directions));
        assertEquals("graphics color", TypedOverloadResolver.select(
                Domain.MODULE, "library.pick", List.of(colors), candidates).declaration());
        assertEquals("weather color", TypedOverloadResolver.select(
                Domain.MODULE, "library.pick", List.of(unrelated), candidates).declaration());
        assertEquals("direction", TypedOverloadResolver.select(
                Domain.MODULE, "library.pick", List.of(directions), candidates).declaration());
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum("Color"));
        assertThrows(IllegalArgumentException.class, () -> TypeKey.nominalEnum("Color."));
    }

    @Test
    void dynamicArgumentsAndErasedGenericTypeKeysAreNotAdmitted() {
        List<Candidate<String>> candidates = List.of(candidate("int", primitive("int")));
        for (TypeKey argument : List.of(TypeKey.any(), TypeKey.unknown())) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> TypedOverloadResolver.select(
                            Domain.MODULE, "library.pick", List.of(argument), candidates));
            assertTrue(denied.getMessage().contains("narrow or cast"));
        }
        for (TypeKey dynamic : List.of(TypeKey.any(), TypeKey.unknown())) {
            assertThrows(IllegalArgumentException.class, () ->
                    new Signature(Domain.MODULE, "library.pick", List.of(dynamic)));
        }
        assertThrows(IllegalArgumentException.class, () -> primitive("Option<int>"));
        assertThrows(IllegalArgumentException.class, () -> primitive("Result<String, int>"));
    }

    @Test
    void boolAliasesHaveOneIdentityAndDuplicateOverloadsFail() {
        assertEquals(primitive("bool"), primitive("boolean"));
        Signature first = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("boolean")));
        Signature second = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("bool")));
        assertEquals(first.symbolId(), second.symbolId());
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> TypedOverloadResolver.select(Domain.MODULE, "library.pick",
                        List.of(primitive("bool")),
                        List.of(new Candidate<>("first", first),
                                new Candidate<>("second", second))));
        assertTrue(duplicate.getMessage().contains("duplicate typed overload"));
    }

    @Test
    void callableDomainsAndArityAreNeverInterchanged() {
        Signature instance = new Signature(Domain.INSTANCE, "Picker.select",
                List.of(primitive("int")));
        Signature statik = new Signature(Domain.STATIC, "Picker.select",
                List.of(primitive("int")));
        Signature two = new Signature(Domain.MODULE, "library.pick",
                List.of(primitive("int"), primitive("int")));
        assertNotEquals(instance.symbolId(), statik.symbolId());
        assertNotEquals(two.symbolId(), candidate("one", primitive("int")).signature().symbolId());
        assertThrows(IllegalArgumentException.class, () -> TypedOverloadResolver.select(
                Domain.STATIC, "Picker.select", List.of(primitive("int")),
                List.of(new Candidate<>("instance", instance))));
    }

    @Test
    void mangledSymbolIsInjectiveForDelimitersAndUnicode() {
        Signature a = new Signature(Domain.MODULE, "m.foo$bar",
                List.of(TypeKey.nominalEnum("m.Color")));
        Signature b = new Signature(Domain.MODULE, "m.foo",
                List.of(TypeKey.nominalEnum("bar$m.Color")));
        Signature c = new Signature(Domain.MODULE, "m.色",
                List.of(TypeKey.nominalEnum("m.颜色")));
        assertNotEquals(a.symbolId(), b.symbolId());
        assertNotEquals(a.symbolId(), c.symbolId());
        assertEquals(c.symbolId(), c.symbolId());
        assertTrue(a.symbolId().startsWith("$overload$v1$"));
    }

    @Test
    void randomizedCandidateOrderCannotInfluenceWinnerOrTie() {
        Candidate<String> exact = candidate("exact", primitive("int"), primitive("int"));
        Candidate<String> widened = candidate("widened", primitive("float"), primitive("float"));
        Candidate<String> other = candidate("other", primitive("decimal"), primitive("float"));
        ArrayList<Candidate<String>> all = new ArrayList<>(List.of(exact, widened, other));
        Random random = new Random(4831L);
        for (int repetition = 0; repetition < 100; repetition++) {
            java.util.Collections.shuffle(all, random);
            assertEquals("exact", TypedOverloadResolver.select(
                    Domain.MODULE, "library.pick",
                    List.of(primitive("int"), primitive("int")), all).declaration());
        }
    }

    @Test
    void returnTypeAndEffectCannotSelectAnOverload() {
        // The signature intentionally has no result, fnc/routine tag,
        // async/actor effect, source location or declaration-order field.
        Signature first = candidate("first", primitive("int")).signature();
        Signature second = candidate("second", primitive("int")).signature();
        assertEquals(first, second);
    }
}
