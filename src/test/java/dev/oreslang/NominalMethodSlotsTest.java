package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalEnumOverloads;
import dev.oreslang.types.NominalMethodSlots;
import dev.oreslang.types.NominalMethodSlots.Domain;
import dev.oreslang.types.NominalMethodSlots.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NominalMethodSlotsTest {
    private static final String ENUMS = """
            define enum Color as
                Red
                Blue
            end
            define enum Mode as
                Red
                Blue
            end
            """;

    private static Ast.ClassDecl owner(Ast.Program parsed) {
        return parsed.modules().stream().flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast).findFirst().orElseThrow();
    }

    private static Map<Slot, Ast.MethodDecl> prove(String classes) {
        Ast.Program parsed = Parser.parse(ENUMS + classes);
        return NominalMethodSlots.proveLocalSameArityFamilies(parsed, owner(parsed));
    }

    @Test
    void equalShapedEnumValuesStillCreateDistinctStaticMethodSlots() {
        var slots = prove("""
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                    pub static fnc choose(Color value): int { return 3; }
                    pub static fnc choose(Mode value): int { return 4; }
                end
                """);
        assertEquals(4, slots.size());
        assertEquals(2, slots.keySet().stream().filter(k -> k.domain() == Domain.INSTANCE).count());
        assertEquals(2, slots.keySet().stream().filter(k -> k.domain() == Domain.STATIC).count());
        assertEquals(4, slots.keySet().stream()
                .map(slot -> slot.linkSymbol("root.Picker")).distinct().count());
        assertThrows(UnsupportedOperationException.class, slots::clear);
    }

    @Test
    void distinctArityAndDistinctMethodNamesDoNotEnterTypedProof() {
        assertTrue(prove("""
                define class Picker as
                    pub choose(): int { return 1; }
                    pub choose(Color value): int { return 2; }
                    pub other(Mode value): int { return 3; }
                end
                """).isEmpty());
    }

    @Test
    void primitiveOnlyAndMixedPrimitiveDifferencesAreNeverDiscriminators() {
        for (String methods : List.of(
                """
                pub choose(int value): int { return 1; }
                pub choose(string value): int { return 2; }
                """,
                """
                pub choose(Color value, int count): int { return 1; }
                pub choose(Mode value, string count): int { return 2; }
                """,
                """
                pub static fnc choose(int value): int { return 1; }
                pub static fnc choose(bool value): int { return 2; }
                """)) {
            assertThrows(IllegalArgumentException.class,
                    () -> prove("define class Picker as\n" + methods + "end\n"));
        }
    }

    @Test
    void sameEnumTypeWithDifferentCasesAndReturnTypesCannotOverload() {
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Color other): string { return "two"; }
                end
                """));
    }

    @Test
    void genericOwnerAndUnresolvedGenericMethodsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Picker<T> as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> prove("""
                define class Picker as
                    pub choose<T>(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """));
    }

    @Test
    void forgedOwnerFromDifferentProgramCannotSupplyMethodProofs() {
        Ast.Program registered = Parser.parse(ENUMS + """
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """);
        Ast.Program replacement = Parser.parse(ENUMS + """
                define class Picker as
                    pub choose(Color value): int { return 7; }
                    pub choose(Mode value): int { return 8; }
                end
                """);
        assertThrows(IllegalArgumentException.class, () ->
                NominalMethodSlots.proveLocalSameArityFamilies(registered, owner(replacement)));
        assertEquals(2, NominalMethodSlots.proveLocalSameArityFamilies(
                registered, owner(registered)).size());
    }

    @Test
    void repeatedCanonicalClassOwnerCannotEnterMethodProof() {
        Ast.Program parsed = Parser.parse(ENUMS + """
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """);
        Ast.ClassDecl klass = owner(parsed);
        Ast.Program duplicated = new Ast.Program(List.of(
                new Ast.ModuleDecl("alpha", List.of(klass, klass))));
        assertThrows(IllegalArgumentException.class, () ->
                NominalMethodSlots.proveLocalSameArityFamilies(duplicated, klass));
    }

    @Test
    void declarationOrderCannotChangeAnyLinkSymbol() {
        var first = prove("""
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """);
        var reversed = prove("""
                define class Picker as
                    pub choose(Mode value): int { return 2; }
                    pub choose(Color value): int { return 1; }
                end
                """);
        assertEquals(first.keySet(), reversed.keySet());
        assertEquals(first.keySet().stream().map(s -> s.linkSymbol("root.Picker")).collect(java.util.stream.Collectors.toSet()),
                reversed.keySet().stream().map(s -> s.linkSymbol("root.Picker")).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void methodCandidateAndArityBudgetsRejectPathologicalFamilies() {
        StringBuilder many = new StringBuilder(ENUMS).append("define class Picker as\n");
        for (int i = 0; i <= NominalEnumOverloads.MAX_SAME_ARITY_CANDIDATES; i++) {
            many.append("pub choose(Color value): int { return 1; }\n");
        }
        many.append("end\n");
        Ast.Program candidateOverflow = Parser.parse(many.toString());
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                () -> NominalMethodSlots.proveLocalSameArityFamilies(
                        candidateOverflow, owner(candidateOverflow)));
        assertTrue(tooMany.getMessage().contains("candidate bound"), tooMany.getMessage());

        StringBuilder large = new StringBuilder(ENUMS).append("define class Picker as\n");
        for (String enumName : List.of("Color", "Mode")) {
            large.append("pub choose(");
            for (int i = 0; i <= NominalEnumOverloads.MAX_OVERLOAD_ARITY; i++) {
                if (i > 0) large.append(", ");
                large.append(enumName).append(" p").append(i);
            }
            large.append("): int { return 1; }\n");
        }
        large.append("end\n");
        Ast.Program arityOverflow = Parser.parse(large.toString());
        IllegalArgumentException tooWide = assertThrows(IllegalArgumentException.class,
                () -> NominalMethodSlots.proveLocalSameArityFamilies(
                        arityOverflow, owner(arityOverflow)));
        assertTrue(tooWide.getMessage().contains("arity limit"), tooWide.getMessage());
    }

    @Test
    void forgedOrMalformedSlotNamesDoNotProduceCollidingAbiSymbols() {
        var color = new NominalEnumOverloads.Position(
                NominalEnumOverloads.Kind.ENUM, "root::Color");
        Slot a = new Slot(Domain.INSTANCE, "get", List.of(color));
        Slot b = new Slot(Domain.STATIC, "get", List.of(color));
        assertNotEquals(a.linkSymbol("root.Picker"), b.linkSymbol("root.Picker"));
        assertNotEquals(a.linkSymbol("root.Picker"), a.linkSymbol("root.Other"));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(Domain.INSTANCE, " ", List.of(color)));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(Domain.INSTANCE, "bad" + (char) 0xD800, List.of(color)));
        assertThrows(IllegalArgumentException.class, () ->
                a.linkSymbol("bad" + (char) 0xDFFF));
        assertThrows(IllegalArgumentException.class, () ->
                new Slot(Domain.INSTANCE, "oversize", java.util.Collections.nCopies(
                        NominalEnumOverloads.MAX_OVERLOAD_ARITY + 1, color)));
    }

    @Test
    void sourceCompilerStillRejectsSameArityEnumMethodsUntilVtablesAreBound() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Picker as
                    pub choose(Color value): int { return 1; }
                    pub choose(Mode value): int { return 2; }
                end
                """);
        assertEquals(2, NominalMethodSlots.proveLocalSameArityFamilies(program, owner(program)).size());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
        assertTrue(denied.getMessage().contains("not enabled"), denied.getMessage());
    }
}
