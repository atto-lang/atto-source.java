package dev.oreslang;

import dev.oreslang.types.ContextualEvidencePlanner;
import dev.oreslang.types.TypeclassRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ContextualEvidencePlannerTest {
    private static TypeclassRegistry.TypeRef type(
            String module, String name, TypeclassRegistry.TypeRef... arguments) {
        return TypeclassRegistry.TypeRef.named(module, name, arguments);
    }

    private static TypeclassRegistry.TypeRef var(String name) {
        return TypeclassRegistry.TypeRef.variable(name);
    }

    private static ContextualEvidencePlanner.Requirement need(
            String slot, TypeclassRegistry.Typeclass tc, TypeclassRegistry.TypeRef... arguments) {
        return new ContextualEvidencePlanner.Requirement(slot, tc, List.of(arguments));
    }

    @Test
    void resolvesOrderedConcreteAndGenericWitnessesWithoutRuntimeSearch() {
        var show = new TypeclassRegistry.Typeclass("algebra", "Show", 1);
        var eq = new TypeclassRegistry.Typeclass("algebra", "Eq", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(show);
        registry.define(eq);
        registry.register(new TypeclassRegistry.Instance(show, "algebra",
                List.of(type("core", "List", var("T"))), "algebra.showList"));
        registry.register(new TypeclassRegistry.Instance(eq, "algebra",
                List.of(type("core", "int")), "algebra.eqInt"));

        var planned = ContextualEvidencePlanner.plan(registry, List.of(
                need("print", show, type("core", "List", type("core", "int"))),
                need("compare", eq, type("core", "int"))), Map.of());

        assertEquals(List.of("algebra.showList", "algebra.eqInt"), planned.dictionarySymbols());
        assertEquals(type("core", "int"), planned.bindings().getFirst().substitutions().get("T"));
        assertTrue(planned.bindings().stream().noneMatch(
                ContextualEvidencePlanner.Binding::explicit));
        assertThrows(UnsupportedOperationException.class,
                () -> planned.bindings().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> planned.bindings().getFirst().substitutions().clear());
    }

    @Test
    void explicitDictionaryMustBeTheExactRegisteredCoherentWitness() {
        var show = new TypeclassRegistry.Typeclass("algebra", "Show", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(show);
        registry.register(new TypeclassRegistry.Instance(show, "algebra",
                List.of(type("core", "int")), "algebra.showInt"));
        var requirements = List.of(need("printer", show, type("core", "int")));

        var explicit = ContextualEvidencePlanner.plan(registry, requirements,
                Map.of("printer", "algebra.showInt"));
        assertTrue(explicit.bindings().getFirst().explicit());
        assertEquals("algebra.showInt", explicit.dictionarySymbols().getFirst());

        var forged = assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry, requirements,
                        Map.of("printer", "arbitrary.runtime.object")));
        assertTrue(forged.getMessage().contains("does not match coherent instance"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry, requirements,
                        Map.of("printer", "algebra.showInt", "unrelated", "anything")));
    }

    @Test
    void missingEvidenceFailsClosedRatherThanPerformingImplicitConversions() {
        var show = new TypeclassRegistry.Typeclass("algebra", "Show", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(show);
        registry.register(new TypeclassRegistry.Instance(show, "algebra",
                List.of(type("core", "int")), "algebra.showInt"));

        var missing = assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("printer", show, type("core", "string"))),
                        Map.of()));
        assertTrue(missing.getMessage().contains("missing typeclass instance"));
    }

    @Test
    void unresolvedGenericsAndDuplicateSlotsAreRejected() {
        var eq = new TypeclassRegistry.Typeclass("algebra", "Eq", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(eq);
        var unbound = assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("eq", eq, var("T"))), Map.of()));
        assertTrue(unbound.getMessage().contains("requires concrete types"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("eq", eq, type("core", "int")),
                                need("eq", eq, type("core", "bool"))), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("eq", eq)), Map.of()));
    }

    @Test
    void repeatedGenericVariableMustUnifyAndCannotInventEvidence() {
        var same = new TypeclassRegistry.Typeclass("algebra", "Same", 2);
        var registry = new TypeclassRegistry.Registry();
        registry.define(same);
        registry.register(new TypeclassRegistry.Instance(same, "algebra",
                List.of(var("T"), var("T")), "algebra.same"));
        assertEquals(List.of("algebra.same"),
                ContextualEvidencePlanner.plan(registry, List.of(need("same", same,
                        type("core", "int"), type("core", "int"))), Map.of()).dictionarySymbols());
        assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("same", same, type("core", "int"),
                                type("core", "bool"))), Map.of()));
    }

    @Test
    void namespaceAndTypeclassIdentityAreExact() {
        var left = new TypeclassRegistry.Typeclass("left", "Show", 1);
        var right = new TypeclassRegistry.Typeclass("right", "Show", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(left);
        registry.define(right);
        registry.register(new TypeclassRegistry.Instance(left, "left",
                List.of(type("core", "int")), "left.showInt"));
        registry.register(new TypeclassRegistry.Instance(right, "right",
                List.of(type("core", "int")), "right.showInt"));
        assertEquals(List.of("right.showInt", "left.showInt"),
                ContextualEvidencePlanner.plan(registry,
                        List.of(need("r", right, type("core", "int")),
                                need("l", left, type("core", "int"))),
                        Map.of()).dictionarySymbols());
    }

    @Test
    void evidenceSearchIsBoundedBeforeRegistryRecursion() {
        var tc = new TypeclassRegistry.Typeclass("algebra", "Show", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(tc);
        var nested = type("core", "int");
        for (int i = 0; i < 4100; i++) {
            nested = type("core", "Box", nested);
        }
        var giant = nested;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ContextualEvidencePlanner.plan(registry,
                        List.of(need("giant", tc, giant)), Map.of()));
        assertTrue(failure.getMessage().contains("static budget"));
    }

    @Test
    void registryCoherenceAndOrphanAdmissionCannotBeBypassed() {
        var tc = new TypeclassRegistry.Typeclass("algebra", "Eq", 1);
        var registry = new TypeclassRegistry.Registry();
        registry.define(tc);
        registry.register(new TypeclassRegistry.Instance(tc, "algebra",
                List.of(type("core", "List", var("T"))), "algebra.eqList"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(tc, "algebra",
                        List.of(type("core", "List", type("core", "int"))),
                        "algebra.eqListInt")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new TypeclassRegistry.Instance(tc, "other",
                        List.of(type("core", "int")), "other.eqInt")));
    }
}
