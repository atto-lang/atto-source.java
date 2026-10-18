package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.TypeclassRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

final class TypeclassesAndEffectsFoundationTest {
    private static TypeclassRegistry.TypeRef t(String owner, String name, TypeclassRegistry.TypeRef... args) {
        return TypeclassRegistry.TypeRef.named(owner, name, args);
    }
    private static TypeclassRegistry.TypeRef v(String name) {
        return TypeclassRegistry.TypeRef.variable(name);
    }

    @Test void resolvesCoherentGenericAndConcreteDictionaries() {
        var tc = new TypeclassRegistry.Typeclass("algebra", "Show", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(tc);
        var ints = new TypeclassRegistry.Instance(tc, "algebra",
                List.of(t("core", "int")), "algebra.showInt");
        var lists = new TypeclassRegistry.Instance(tc, "algebra",
                List.of(t("core", "List", v("T"))), "algebra.showList");
        registry.register(ints);
        registry.register(lists);
        assertEquals(ints, registry.resolve(tc, List.of(t("core", "int"))));
        assertEquals(lists, registry.resolve(tc, List.of(t("core", "List", t("core", "int")))));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(tc, List.of(t("core", "bool"))));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(tc, List.of(v("T"))));
    }

    @Test void rejectsOverlapsAndOrphans() {
        var tc = new TypeclassRegistry.Typeclass("algebra", "Eq", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(tc);
        registry.register(new TypeclassRegistry.Instance(tc, "algebra",
                List.of(t("core", "List", v("X"))), "eqList"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(tc, "algebra",
                        List.of(t("core", "List", t("core", "int"))), "eqListInt")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(tc, "foreign",
                        List.of(t("core", "int")), "orphan")));
        registry.register(new TypeclassRegistry.Instance(tc, "mine",
                List.of(t("mine", "Box")), "ownType"));
    }

    @Test void repeatedVariableMatchesMustAgree() {
        var tc = new TypeclassRegistry.Typeclass("algebra", "Same", 2);
        var registry = new TypeclassRegistry.Registry();
        registry.define(tc);
        registry.register(new TypeclassRegistry.Instance(tc, "algebra", List.of(v("T"), v("T")), "same"));
        assertEquals("same", registry.resolve(tc,
                List.of(t("core", "int"), t("core", "int"))).dictionarySymbol());
        assertThrows(IllegalArgumentException.class, () ->
                registry.resolve(tc, List.of(t("core", "int"), t("core", "bool"))));
    }

    @Test void effectsReachFixedPointThroughRecursionAndFailClosed() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.pure("api.main", Set.of(),
                List.of(EffectContracts.Call.direct("api.helper"))));
        graph.add(EffectContracts.Callable.inferred("api.helper", Set.of(),
                List.of(EffectContracts.Call.direct("api.sink"), EffectContracts.Call.direct("api.main"))));
        graph.add(EffectContracts.Callable.inferred("api.sink", Set.of(IO), List.of()));
        var failure = assertThrows(IllegalArgumentException.class, graph::validate);
        assertTrue(failure.getMessage().contains("api.main -> api.helper -> api.sink"));
        var unknown = new EffectContracts.Graph();
        unknown.add(EffectContracts.Callable.pure("pure.f", Set.of(),
                List.of(EffectContracts.Call.direct("ffi.dynamic"))));
        assertTrue(assertThrows(IllegalArgumentException.class, unknown::validate)
                .getMessage().contains("UNKNOWN_CALL"));
    }

    @Test void trapHandlesOnlyOrdinaryThrowOnItsOwnEdge() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.trapping("trapped", Set.of(THROW, RAISE, PANIC), List.of()));
        graph.add(EffectContracts.Callable.inferred("caller", Set.of(),
                List.of(EffectContracts.Call.direct("trapped"))));
        var inferred = graph.infer();
        assertFalse(inferred.get("trapped").contains(THROW));
        assertEquals(Set.of(RAISE, PANIC), inferred.get("caller"));
        var another = new EffectContracts.Graph();
        another.add(EffectContracts.Callable.inferred("bad", Set.of(THROW), List.of()));
        another.add(EffectContracts.Callable.inferred("caller", Set.of(),
                List.of(EffectContracts.Call.direct("bad"))));
        assertTrue(another.infer().get("caller").contains(THROW));
    }
}
