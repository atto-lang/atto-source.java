package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalEnumOverloads;
import dev.oreslang.types.NominalEnumOverloads.PairStatus;
import dev.oreslang.types.NominalEnumOverloads.Signature;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Closed-world, zero-runtime-type-lookup nominal enum overload policy. */
final class NominalEnumOverloadsTest {
    private static final String ENUMS = """
            define enum Color as
                Red
                Blue
            end
            define enum Mode as
                Fast: int
                Slow: Tuple[bool, string]
            end
            define enum OtherColor as
                Red
                Blue
            end
            define enum Unsupported as
                Entry: List<int>
            end
            """;

    private static Ast.Param param(String type) {
        return new Ast.Param(Ast.TypeRef.simple(type), "value");
    }

    private static Map<String, String> symbols() {
        return NominalEnumOverloads.eligibleEnumSymbols(Parser.parse(ENUMS));
    }

    private static Signature signature(String name, String... typeNames) {
        return NominalEnumOverloads.signature(name,
                java.util.Arrays.stream(typeNames).map(NominalEnumOverloadsTest::param).toList(),
                symbols());
    }

    @Test
    void primitiveOnlyProductEnumsAreEligibleButCollectionsAreNot() {
        Ast.Program program = Parser.parse(ENUMS);
        Map<String, String> registered = symbols();
        assertTrue(registered.containsKey("Color"));
        assertTrue(registered.containsKey("Mode"));
        assertTrue(registered.containsKey("OtherColor"));
        assertFalse(registered.containsKey("Unsupported"));
        assertNotEquals(registered.get("Color"), registered.get("OtherColor"));
        assertThrows(IllegalArgumentException.class,
                () -> signature("use", "Unsupported"));
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.signature("use",
                        List.of(new Ast.Param(Ast.TypeRef.simple("Any"), "a")), registered));
        assertEquals(4, program.modules().getFirst().declarations().size());
    }

    @Test
    void arityFirstAndNominalEnumIdentityOnly() {
        assertEquals(PairStatus.DISTINCT_ARITY,
                NominalEnumOverloads.compare(signature("go", "Color"),
                        signature("go", "Mode", "int")));
        assertEquals(PairStatus.DISTINCT_ENUMS,
                NominalEnumOverloads.compare(signature("go", "Color", "int"),
                        signature("go", "Mode", "int")));
        assertEquals(PairStatus.SAME_SIGNATURE,
                NominalEnumOverloads.compare(signature("go", "Color"),
                        signature("go", "Color")));
        assertEquals(PairStatus.INVALID_SAME_ARITY,
                NominalEnumOverloads.compare(signature("go", "int"),
                        signature("go", "string")));
        assertEquals(PairStatus.INVALID_SAME_ARITY,
                NominalEnumOverloads.compare(signature("go", "Color", "int"),
                        signature("go", "Mode", "string")));
        assertEquals(PairStatus.INVALID_SAME_ARITY,
                NominalEnumOverloads.compare(signature("go", "Color"),
                        signature("go", "int")));
    }

    @Test
    void selectsOnlyByStaticNominalEnumProofWithoutPayloadComparison() {
        Signature color = signature("go", "Color", "int");
        Signature mode = signature("go", "Mode", "int");
        Signature other = signature("go", "OtherColor", "int");
        String colorSymbol = symbols().get("Color");
        String modeSymbol = symbols().get("Mode");
        assertSame(mode, NominalEnumOverloads.select(
                List.of(color, mode, other), Map.of(0, modeSymbol)));
        assertSame(color, NominalEnumOverloads.select(
                List.of(color, mode, other), Map.of(0, colorSymbol)));
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.select(List.of(color, mode, other), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.select(
                        List.of(color, mode, other), Map.of(0, "unknown::Mode")));
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.select(List.of(color, color), Map.of(0, colorSymbol)));
    }

    @Test
    void structuralAndBorrowedParametersNeverEnterSameArityCandidateLookup() {
        Map<String, String> enums = symbols();
        Ast.Param structural = new Ast.Param(Ast.TypeRef.simple("Color"), "x", true, false);
        Ast.Param borrowed = param("Color");
        Ast.Param mutable = new Ast.Param(Ast.TypeRef.simple("Color"), "x", false, true);
        for (Ast.Param parameter : List.of(structural, mutable,
                new Ast.Param(Ast.TypeRef.borrowed(borrowed.type(), false), "x"),
                new Ast.Param(Ast.TypeRef.simple("Unknown"), "x"),
                new Ast.Param(Ast.TypeRef.simple("SomeClass"), "x"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> NominalEnumOverloads.signature("go", List.of(parameter), enums));
        }
    }

    @Test
    void repeatedEnumSpellingAcrossModulesIsNotMistakenForOneSymbol() {
        Ast.Program program = Parser.parse("""
                module alpha as
                    define enum Color as
                        Red
                    end
                end
                module beta as
                    define enum Color as
                        Blue
                    end
                end
                """);
        Map<String, String> enums = NominalEnumOverloads.eligibleEnumSymbols(program);
        assertFalse(enums.containsKey("Color"));
        assertNotEquals(enums.get("alpha::Color"), enums.get("beta::Color"));
    }

    @Test
    void productionCompilerContinuesRejectingSameArityWhileRuntimeSelectorIsArityOnly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(ENUMS + """
                        fnc go(Color c): int { return 1; }
                        fnc go(Mode m): int { return 2; }
                        """)));
        assertTrue(failure.getMessage().contains("arity"),
                failure.getMessage());
    }
}
