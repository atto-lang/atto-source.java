package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class GenericCallArgumentsOrderingHardeningTest {
    private static Ast.TypeRef type(String name) {
        return Ast.TypeRef.simple(name);
    }

    private static Ast.TypeRef named(String name, String value) {
        return Ast.TypeRef.namedParameter(name, type(value));
    }

    @Test
    void namedTypeArgumentsRetainLexicalOrderWithoutChangingSlots() {
        var bound = GenericCallArguments.bind(
                List.of("foo", "bar", "T"),
                List.of(named("T", "bool"), named("foo", "string"), named("bar", "int")),
                "function choose");
        assertEquals(List.of("T", "foo", "bar"),
                new ArrayList<>(bound.explicitTypes().keySet()));
        assertEquals(type("string"), bound.explicitTypes().get("foo"));
        assertEquals(type("int"), bound.explicitTypes().get("bar"));
        assertEquals(type("bool"), bound.explicitTypes().get("T"));

        assertThrows(UnsupportedOperationException.class,
                () -> bound.explicitTypes().put("foo", type("bool")));
        assertThrows(UnsupportedOperationException.class,
                () -> bound.inferenceHoles().add("foo"));
    }

    @Test
    void positionalPrefixThenNamedSuffixKeepsOriginalBindingOrder() {
        var bound = GenericCallArguments.bind(
                List.of("foo", "bar", "T"),
                List.of(type("string"), named("T", "bool"), named("bar", "int")),
                "routine choose");
        assertEquals(List.of("foo", "T", "bar"),
                new ArrayList<>(bound.explicitTypes().keySet()));
        assertEquals(type("string"), bound.explicitTypes().get("foo"));
        assertEquals(type("int"), bound.explicitTypes().get("bar"));
        assertEquals(type("bool"), bound.explicitTypes().get("T"));
    }

    @Test
    void inferenceOnlySlotsRetainRequestOrderAndCannotBecomeDefaults() {
        var bound = GenericCallArguments.bind(
                List.of("foo", "bar", "T"),
                List.of(named("T", "_"), named("foo", "string"), named("bar", "_")),
                "method choose");
        assertEquals(List.of("foo"), new ArrayList<>(bound.explicitTypes().keySet()));
        assertEquals(List.of("T", "bar"), new ArrayList<>(bound.inferenceHoles()));
        assertEquals(Set.of("T", "bar"), bound.inferenceHoles());
    }

    @Test
    void namesAreCaseSensitiveAndCannotClaimPositionalSlotsTwice() {
        for (var arguments : List.<List<Ast.TypeRef>>of(
                List.of(named("Foo", "int")),
                List.of(named("foo", "string"), named("foo", "int")),
                List.of(type("string"), named("foo", "int")),
                List.of(named("bar", "int"), type("string")))) {
            assertThrows(IllegalArgumentException.class, () ->
                    GenericCallArguments.bind(List.of("foo", "bar"), arguments, "fnc choose"));
        }
    }

    @Test
    void differentlyOrderedCallSitesHaveIdenticalDeclarationBindings() {
        var one = GenericCallArguments.bind(
                List.of("foo", "bar", "T"),
                List.of(named("foo", "string"), named("bar", "int"), named("T", "bool")),
                "choose");
        var two = GenericCallArguments.bind(
                List.of("foo", "bar", "T"),
                List.of(named("T", "bool"), named("foo", "string"), named("bar", "int")),
                "choose");
        assertEquals(Map.copyOf(one.explicitTypes()), Map.copyOf(two.explicitTypes()));
        assertNotEquals(new ArrayList<>(one.explicitTypes().keySet()),
                new ArrayList<>(two.explicitTypes().keySet()),
                "diagnostic traversal retains source order, not arbitrary Map.copyOf order");
    }
}
