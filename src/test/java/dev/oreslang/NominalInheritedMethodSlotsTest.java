package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalInheritedMethodSlots;
import dev.oreslang.types.NominalMethodSlots.Domain;
import dev.oreslang.types.NominalMethodSlots.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NominalInheritedMethodSlotsTest {
    private static final String ENUMS = """
            define enum Color as Red Blue end
            define enum Mode as Fast Slow end
            """;

    private static Map<Slot, NominalInheritedMethodSlots.Target> prove(
            String classes, String root, Domain domain, String method, int arity) {
        Ast.Program program = Parser.parse(ENUMS + classes);
        return NominalInheritedMethodSlots.prove(program, root, domain, method, arity);
    }

    @Test
    void inheritedDistinctEnumMethodPreservesBothNominalSlots() {
        var slots = prove("""
                define class Base as
                    pub pick(Color x): int { return 10; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 20; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(2, slots.size());
        assertEquals(2, slots.values().stream().map(t -> t.owner().name()).distinct().count());
        assertEquals(2, slots.keySet().stream()
                .map(s -> s.linkSymbol("root.Child")).distinct().count());
    }

    @Test
    void overridingOneEnumSlotCannotShadowOtherOverloads() {
        var slots = prove("""
                define class Base as
                    pub pick(Color x): int { return 10; }
                    pub pick(Mode x): int { return 20; }
                end
                define class Child extends Base as
                    pub pick(Color x): int { return 30; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(2, slots.size());
        assertEquals("Child", slots.entrySet().stream()
                .filter(e -> e.getKey().positions().getFirst().id().endsWith("::Color"))
                .findFirst().orElseThrow().getValue().owner().name());
        assertEquals("Base", slots.entrySet().stream()
                .filter(e -> e.getKey().positions().getFirst().id().endsWith("::Mode"))
                .findFirst().orElseThrow().getValue().owner().name());
        assertThrows(UnsupportedOperationException.class, slots::clear);
    }

    @Test
    void staticAndInstanceFamiliesAreIndependent() {
        String source = """
                define class Base as
                    pub pick(Color x): int { return 10; }
                    pub static fnc pick(Color x): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 20; }
                    pub static fnc pick(Mode x): int { return 21; }
                end
                """;
        var instance = prove(source, "Child", Domain.INSTANCE, "pick", 1);
        var statics = prove(source, "Child", Domain.STATIC, "pick", 1);
        assertEquals(2, instance.size());
        assertEquals(2, statics.size());
        assertNotEquals(instance.keySet(), statics.keySet());
    }

    @Test
    void incompatibleReturnEffectVisibilityAndAbstractOverridesFailClosed() {
        for (String replacement : List.of(
                "pub pick(Color x): string { return \"bad\"; }",
                "private pick(Color x): int { return 2; }",
                "pub async pick(Color x): int { return 2; }",
                "pub abstract pick(Color x): int;")) {
            String source = """
                    define abstract class Base as
                        pub pick(Color x): int { return 1; }
                    end
                    define abstract class Child extends Base as
                    """ + replacement + "\nend\n";
            assertThrows(IllegalArgumentException.class,
                    () -> prove(source, "Child", Domain.INSTANCE, "pick", 1));
        }
    }

    @Test
    void diamondRequiresExplicitCompatibleOverrideForSameSlot() {
        String parents = """
                define class Left as
                    pub pick(Color x): int { return 1; }
                end
                define class Right as
                    pub pick(Color x): int { return 2; }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> prove(parents + """
                        define class Child extends Left, Right as
                        end
                        """, "Child", Domain.INSTANCE, "pick", 1));
        var resolved = prove(parents + """
                define class Child extends Left, Right as
                    pub pick(Color x): int { return 3; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(1, resolved.size());
        assertEquals("Child", resolved.values().iterator().next().owner().name());
    }

    @Test
    void trueDiamondOfSameDeclarationIsNotAmbiguous() {
        var resolved = prove("""
                define class Base as
                    pub pick(Color x): int { return 1; }
                end
                define class Left extends Base as
                end
                define class Right extends Base as
                end
                define class Diamond extends Left, Right as
                end
                """, "Diamond", Domain.INSTANCE, "pick", 1);
        assertEquals(1, resolved.size());
        assertEquals("Base", resolved.values().iterator().next().owner().name());
    }

    @Test
    void inheritedPrimitiveOrUnknownCannotBecomeImplicitDynamicDispatch() {
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Base as
                    pub pick(int x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 2; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1));
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Base as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Any x): int { return 2; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1));
    }

    @Test
    void unresolvedParentsAndGenericOwnersDoNotInventNominalIdentities() {
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Child extends Missing as
                    pub pick(Color x): int { return 1; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1));
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Base<T> as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base<int> as
                end
                """, "Child", Domain.INSTANCE, "pick", 1));
    }

    @Test
    void proofDoesNotEnableUnsafeExecutableMethodDispatch() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 1; }
                    pub pick(Mode x): int { return 2; }
                end
                """);
        assertEquals(2, NominalInheritedMethodSlots.prove(
                program, "Base", Domain.INSTANCE, "pick", 1).size());
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
    }
}
