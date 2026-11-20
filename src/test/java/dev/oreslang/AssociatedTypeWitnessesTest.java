package dev.oreslang;

import dev.oreslang.types.AssociatedTypeWitnesses;
import dev.oreslang.types.TypeclassRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-helper proof tests. These are NOT parsed .ores or guest AOT tests.
 */
final class AssociatedTypeWitnessesTest {
    private static TypeclassRegistry.TypeRef named(String module, String name,
                                                    TypeclassRegistry.TypeRef... args) {
        return TypeclassRegistry.TypeRef.named(module, name, args);
    }
    private static TypeclassRegistry.TypeRef variable(String name) {
        return TypeclassRegistry.TypeRef.variable(name);
    }

    @Test void projectsGenericItemAndErrorWithGroundWitness() {
        var protocol = new TypeclassRegistry.Typeclass("std", "IteratorLike", 1);
        var registry = new AssociatedTypeWitnesses();
        registry.define(protocol, Set.of("item", "error"));
        var instance = new TypeclassRegistry.Instance(protocol, "std",
                List.of(named("core", "List", variable("T"))), "std.list_iterator");
        registry.register(instance, Map.of(
                "item", variable("T"),
                "error", named("core", "ReadError", variable("T"))));
        var projected = registry.resolve(protocol,
                List.of(named("core", "List", named("core", "int"))));
        assertEquals(instance, projected.witness());
        assertEquals(named("core", "int"), projected.type("item"));
        assertEquals(named("core", "ReadError", named("core", "int")), projected.type("error"));
        assertThrows(IllegalArgumentException.class, () -> projected.type("unlisted"));
        assertThrows(UnsupportedOperationException.class, () -> projected.types().put("item", variable("X")));
    }

    @Test void rejectsUnknownMissingAndUnboundTypeEquationsAtomically() {
        var protocol = new TypeclassRegistry.Typeclass("std", "Decode", 1);
        var registry = new AssociatedTypeWitnesses();
        registry.define(protocol, Set.of("decoded", "error"));
        var instance = new TypeclassRegistry.Instance(protocol, "std",
                List.of(named("std", "Bytes", variable("T"))), "std.decode_bytes");
        assertThrows(IllegalArgumentException.class, () -> registry.register(instance,
                Map.of("decoded", variable("T"))));
        assertThrows(IllegalArgumentException.class, () -> registry.register(instance,
                Map.of("decoded", variable("T"), "error", variable("Z"))));
        assertThrows(IllegalArgumentException.class, () -> registry.register(instance,
                Map.of("decoded", variable("T"), "error", named("core", "Error"),
                        "surprise", named("core", "int"))));
        registry.register(instance, Map.of("decoded", variable("T"),
                "error", named("core", "Error")));
        assertEquals(named("core", "bool"), registry.resolve(protocol,
                List.of(named("std", "Bytes", named("core", "bool")))).type("decoded"));
    }

    @Test void coherenceRejectsOverlapsEvenWhenProjectedTypesDiffer() {
        var protocol = new TypeclassRegistry.Typeclass("logic", "Own", 1);
        var registry = new AssociatedTypeWitnesses();
        registry.define(protocol, Set.of("associated"));
        registry.register(new TypeclassRegistry.Instance(protocol, "logic",
                List.of(named("core", "Vec", variable("T"))), "logic.vec"),
                Map.of("associated", variable("T")));
        var overlap = new TypeclassRegistry.Instance(protocol, "logic",
                List.of(named("core", "Vec", named("core", "int"))), "logic.vec_int");
        assertThrows(IllegalArgumentException.class, () -> registry.register(overlap,
                Map.of("associated", named("core", "bool"))));
        assertEquals(named("core", "int"), registry.resolve(protocol,
                List.of(named("core", "Vec", named("core", "int")))).type("associated"));
    }

    @Test void orphanDuplicateDictionaryAndOpenWitnessSearchFailClosed() {
        var protocol = new TypeclassRegistry.Typeclass("algebra", "Identity", 1);
        var registry = new AssociatedTypeWitnesses();
        registry.define(protocol, Set.of("result"));
        var first = new TypeclassRegistry.Instance(protocol, "algebra",
                List.of(named("core", "int")), "algebra.same_symbol");
        registry.register(first, Map.of("result", named("core", "int")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(protocol, "algebra",
                        List.of(named("core", "bool")), "algebra.same_symbol"),
                Map.of("result", named("core", "bool"))));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(protocol, "foreign",
                        List.of(named("core", "bool")), "foreign.unowned"),
                Map.of("result", named("core", "bool"))));
        assertThrows(IllegalArgumentException.class, () ->
                registry.resolve(protocol, List.of(named("core", "bool"))));
        assertThrows(IllegalArgumentException.class, () ->
                registry.resolve(protocol, List.of(variable("T"))));
    }

    @Test void resolutionIsDeterministicUnderDisjointWitnessImportOrder() {
        var protocol = new TypeclassRegistry.Typeclass("types", "Collection", 1);
        var integer = new TypeclassRegistry.Instance(protocol, "types",
                List.of(named("core", "int")), "types.int_collection");
        var booleanType = new TypeclassRegistry.Instance(protocol, "types",
                List.of(named("core", "bool")), "types.bool_collection");
        var first = new AssociatedTypeWitnesses();
        var second = new AssociatedTypeWitnesses();
        for (var registry : List.of(first, second)) registry.define(protocol, Set.of("item"));
        first.register(integer, Map.of("item", named("core", "int")));
        first.register(booleanType, Map.of("item", named("core", "bool")));
        second.register(booleanType, Map.of("item", named("core", "bool")));
        second.register(integer, Map.of("item", named("core", "int")));
        for (var type : List.of(named("core", "int"), named("core", "bool"))) {
            assertEquals(first.resolve(protocol, List.of(type)), second.resolve(protocol, List.of(type)));
        }
    }

    @Test void repeatedGenericVariableBindsOneConsistentType() {
        var protocol = new TypeclassRegistry.Typeclass("types", "PairSame", 2);
        var registry = new AssociatedTypeWitnesses();
        registry.define(protocol, Set.of("item"));
        registry.register(new TypeclassRegistry.Instance(protocol, "types",
                List.of(variable("T"), variable("T")), "types.same_pair"),
                Map.of("item", variable("T")));
        assertEquals(named("core", "int"), registry.resolve(protocol,
                List.of(named("core", "int"), named("core", "int"))).type("item"));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(protocol,
                List.of(named("core", "int"), named("core", "bool"))));
    }
    @Test void delimiterCollisionDoesNotMergeUnrelatedProtocols() {
        var a = new TypeclassRegistry.Typeclass("a.b", "c", 1);
        var b = new TypeclassRegistry.Typeclass("a", "b.c", 1);
        assertEquals(a.qualifiedName(), b.qualifiedName(),
                "display strings are intentionally ambiguous and must never be registry keys");
        var registry = new AssociatedTypeWitnesses();
        registry.define(a, Set.of("item"));
        registry.define(b, Set.of("item"));
        registry.register(new TypeclassRegistry.Instance(a, "a.b",
                List.of(named("core", "int")), "a_b_c_int"),
                Map.of("item", named("core", "int")));
        registry.register(new TypeclassRegistry.Instance(b, "a",
                List.of(named("core", "bool")), "a_bc_bool"),
                Map.of("item", named("core", "bool")));
        assertEquals(named("core", "int"),
                registry.resolve(a, List.of(named("core", "int"))).type("item"));
        assertEquals(named("core", "bool"),
                registry.resolve(b, List.of(named("core", "bool"))).type("item"));
        assertThrows(IllegalArgumentException.class, () ->
                registry.resolve(a, List.of(named("core", "bool"))));
        assertThrows(IllegalArgumentException.class, () ->
                TypeclassRegistry.TypeRef.named("", "unqualified"));
    }

    @Test void distinctNominalHeadsWithEmbeddedNamespaceDelimitersDoNotOverlap() {
        var protocol = new TypeclassRegistry.Typeclass("m", "NominalHead", 1);
        var witnesses = new AssociatedTypeWitnesses();
        witnesses.define(protocol, Set.of("item"));
        var left = named("a::b", "c");
        var right = named("a", "b::c");
        witnesses.register(new TypeclassRegistry.Instance(protocol, "m",
                List.of(left), "m.left"), Map.of("item", left));
        witnesses.register(new TypeclassRegistry.Instance(protocol, "m",
                List.of(right), "m.right"), Map.of("item", right));
        assertEquals(left, witnesses.resolve(protocol, List.of(left)).type("item"));
        assertEquals(right, witnesses.resolve(protocol, List.of(right)).type("item"));
        assertThrows(IllegalArgumentException.class, () ->
                witnesses.resolve(protocol, List.of(named("a", "other"))));
    }

}
