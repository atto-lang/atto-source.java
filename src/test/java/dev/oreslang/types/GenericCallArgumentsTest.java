package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GenericCallArgumentsTest {
    private static Ast.TypeRef type(String name) {
        return new Ast.TypeRef(name, List.of(), false);
    }

    private static Ast.TypeRef named(String name, String value) {
        return Ast.TypeRef.namedParameter(name, type(value));
    }

    @Test
    void namesAreMatchedByIdentityNotArgumentOrder() {
        GenericCallArguments.Bound bound = GenericCallArguments.bind(
                List.of("A", "B", "C", "D", "E"),
                List.of(named("D", "bool"), named("A", "int"), named("C", "bool")),
                "function foo");
        assertEquals(type("int"), bound.explicitTypes().get("A"));
        assertEquals(type("bool"), bound.explicitTypes().get("C"));
        assertEquals(type("bool"), bound.explicitTypes().get("D"));
        assertFalse(bound.explicitTypes().containsKey("B"));
        assertTrue(bound.inferenceHoles().isEmpty());
    }

    @Test
    void mixedPositionalArgumentsAndHolesKeepTheirSlots() {
        GenericCallArguments.Bound bound = GenericCallArguments.bind(
                List.of("A", "B", "C", "D"),
                List.of(type("int"), type("_"), type("bool"), named("D", "string")),
                "function foo");
        assertEquals(type("int"), bound.explicitTypes().get("A"));
        assertEquals(type("bool"), bound.explicitTypes().get("C"));
        assertEquals(type("string"), bound.explicitTypes().get("D"));
        assertEquals(java.util.Set.of("B"), bound.inferenceHoles());
        assertFalse(bound.explicitTypes().containsKey("B"));
    }

    @Test
    void emptyArgumentsPreserveInferenceAndDoNotFabricateAny() {
        GenericCallArguments.Bound bound = GenericCallArguments.bind(
                List.of("A", "B"), List.of(), "foo");
        assertTrue(bound.explicitTypes().isEmpty());
        assertTrue(bound.inferenceHoles().isEmpty());
    }

    @Test
    void malformedCallsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(named("X", "int")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(named("A", "int"), named("A", "bool")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(type("int"), named("A", "bool")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(named("B", "bool"), type("int")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(type("int"), type("bool"), type("string")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "A"), List.of(type("int")), "foo"));
        assertThrows(IllegalArgumentException.class, () -> GenericCallArguments.bind(
                List.of("A", "B"), List.of(named("a", "int")), "foo"));
    }

    @Test
    void nestedUnderscoreIsNotAnInferenceHole() {
        Ast.TypeRef nested = new Ast.TypeRef("Array", List.of(type("_")), false);
        GenericCallArguments.Bound bound = GenericCallArguments.bind(
                List.of("A"), List.of(nested), "foo");
        assertEquals(nested, bound.explicitTypes().get("A"));
        assertTrue(bound.inferenceHoles().isEmpty());
    }
}
