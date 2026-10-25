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
    void nestedInferenceHoleIsRejectedRatherThanAcceptedAsNominalType() {
        Ast.TypeRef nested = new Ast.TypeRef("Array", List.of(type("_")), false);
        Ast.TypeRef deeplyNested = new Ast.TypeRef(
                "Option", List.of(new Ast.TypeRef("Array", List.of(type("_")), false)), false);
        Ast.TypeRef synthetic = new Ast.TypeRef(
                "Result", List.of(type("int"), Ast.TypeRef.inferred()), false);
        for (Ast.TypeRef value : List.of(nested, deeplyNested, synthetic)) {
            for (Ast.TypeRef argument : List.of(value, Ast.TypeRef.namedParameter("A", value))) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> GenericCallArguments.bind(
                                List.of("A"), List.of(argument), "foo"));
                assertTrue(error.getMessage().contains("nested inference hole"), error.getMessage());
            }
        }
        // The legitimate top-level hole still requests inference.
        GenericCallArguments.Bound bare = GenericCallArguments.bind(
                List.of("A"), List.of(type("_")), "foo");
        assertEquals(java.util.Set.of("A"), bare.inferenceHoles());
        assertTrue(bare.explicitTypes().isEmpty());
    }

    @Test
    void sharedDefaultResolutionHonorsInferenceAndExplicitBindings() {
        List<String> slots = List.of("A", "B", "C", "D");
        List<Ast.GenericParameter> specs = List.of(
                new Ast.GenericParameter("A", null),
                new Ast.GenericParameter("B", type("int")),
                new Ast.GenericParameter("C", type("B")),
                new Ast.GenericParameter("D",
                        new Ast.TypeRef("Array", List.of(type("C")), false)));
        java.util.Map<String, Ast.TypeRef> bound = new java.util.HashMap<>();
        bound.put("A", type("bool"));
        GenericCallArguments.applyDefaults(
                slots, specs, bound, java.util.Set.of(),
                ref -> ref, GenericCallArgumentsTest::substitute,
                ref -> GenericCallArguments.containsUnresolvedAstGeneric(ref, java.util.Set.copyOf(slots)),
                "test function");
        assertEquals(type("bool"), bound.get("A"));
        assertEquals(type("int"), bound.get("B"));
        assertEquals(type("int"), bound.get("C"));
        assertEquals(new Ast.TypeRef("Array", List.of(type("int")), false), bound.get("D"));

        java.util.Map<String, Ast.TypeRef> explicit = new java.util.HashMap<>();
        explicit.put("A", type("bool"));
        explicit.put("B", type("string"));
        // B stays explicit; C's requested inference must not use its default.
        // D's dependent default therefore cannot resolve C and must fail.
        IllegalArgumentException hole = assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.applyDefaults(
                        slots, specs, explicit, java.util.Set.of("C"),
                        ref -> ref, GenericCallArgumentsTest::substitute,
                        ref -> GenericCallArguments.containsUnresolvedAstGeneric(
                                ref, java.util.Set.copyOf(slots)),
                        "test function"));
        assertTrue(hole.getMessage().contains("cannot resolve dependent default"), hole.getMessage());
        assertEquals(type("string"), explicit.get("B"));
        assertFalse(explicit.containsKey("C"), "explicit inference holes must not receive defaults");
    }

    @Test
    void dependentDefaultFailureDoesNotPublishPartialBindings() {
        List<String> generics = List.of("A", "B", "C");
        List<Ast.GenericParameter> specs = List.of(
                new Ast.GenericParameter("A", null),
                new Ast.GenericParameter("B", type("int")),
                new Ast.GenericParameter("C", type("A")));
        java.util.Map<String, Ast.TypeRef> bindings = new java.util.LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.applyDefaults(
                        generics, specs, bindings, java.util.Set.of(),
                        ref -> ref, GenericCallArgumentsTest::substitute,
                        ref -> GenericCallArguments.containsUnresolvedAstGeneric(
                                ref, java.util.Set.copyOf(generics)),
                        "atomic call"));
        assertTrue(bindings.isEmpty(), "unresolved C must not publish B");
    }

    @Test
    void finalSlotAdmissionUsesStableDiagnosticsAndRejectsNull() {
        java.util.Map<String, Ast.TypeRef> bindings = new java.util.HashMap<>();
        bindings.put("A", type("int"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.requireBound(
                        List.of("C", "B", "A"), bindings, "foo"));
        assertTrue(error.getMessage().contains("[B, C]"), error.getMessage());
        bindings.put("B", null);
        assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.requireBound(List.of("A", "B"), bindings, "foo"));
        bindings.put("B", type("bool"));
        assertDoesNotThrow(() -> GenericCallArguments.requireBound(
                List.of("A", "B"), bindings, "foo"));
    }

    @Test
    void standaloneOwnershipRejectsUninferrableGenericHole() {
        String source = """
                fnc choose<A, B=int>(A input): int { return 7; }
                fnc use(): int { return choose<int, _>(7); }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(dev.oreslang.parser.Parser.parse(source)));
        assertTrue(error.getMessage().contains("cannot infer generic parameter"),
                error.getMessage());
    }

    @Test
    void sharedDefaultResolutionFailsOnUnboundDependentGeneric() {
        List<String> slots = List.of("A", "B");
        List<Ast.GenericParameter> specs = List.of(
                new Ast.GenericParameter("A", null),
                new Ast.GenericParameter("B", type("A")));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.applyDefaults(
                        slots, specs, new java.util.HashMap<>(), java.util.Set.of(),
                        ref -> ref, GenericCallArgumentsTest::substitute,
                        ref -> GenericCallArguments.containsUnresolvedAstGeneric(ref, java.util.Set.copyOf(slots)),
                        "test function"));
        assertTrue(exception.getMessage().contains("cannot resolve dependent default"),
                exception.getMessage());
    }

    private static Ast.TypeRef substitute(
            Ast.TypeRef ref, java.util.Map<String, Ast.TypeRef> bindings) {
        Ast.TypeRef replacement = bindings.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty()) return replacement;
        return new Ast.TypeRef(ref.name(),
                ref.arguments().stream().map(arg -> substitute(arg, bindings)).toList(),
                ref.inferArguments());
    }

    @Test
    void allFiveFactorialNamedArgumentPermutationsHaveIdenticalBindings() {
        List<String> names = List.of("A", "B", "C", "D", "E");
        List<Ast.TypeRef> values = List.of(
                named("A", "int"), named("B", "string"), named("C", "bool"),
                named("D", "float"), named("E", "Symbol"));
        int[] count = {0};
        assertEveryPermutation(names, values, 0, count);
        assertEquals(120, count[0]);
    }

    private static void assertEveryPermutation(
            List<String> names, List<Ast.TypeRef> values, int index, int[] count) {
        if (index == values.size()) {
            GenericCallArguments.Bound bound =
                    GenericCallArguments.bind(names, values, "five-parameter function");
            for (int i = 0; i < names.size(); i++) {
                assertEquals(type(List.of("int", "string", "bool", "float", "Symbol").get(i)),
                        bound.explicitTypes().get(names.get(i)));
            }
            assertTrue(bound.inferenceHoles().isEmpty());
            count[0]++;
            return;
        }
        for (int i = index; i < values.size(); i++) {
            java.util.ArrayList<Ast.TypeRef> shuffled = new java.util.ArrayList<>(values);
            Ast.TypeRef tmp = shuffled.get(index);
            shuffled.set(index, shuffled.get(i));
            shuffled.set(i, tmp);
            assertEveryPermutation(names, shuffled, index + 1, count);
        }
    }
}
