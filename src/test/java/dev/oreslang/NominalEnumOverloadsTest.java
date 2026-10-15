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
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("alpha", List.of(new Ast.EnumDecl("Color",
                        List.of(new Ast.EnumCase("Red", null, 1))))),
                new Ast.ModuleDecl("beta", List.of(new Ast.EnumDecl("Color",
                        List.of(new Ast.EnumCase("Blue", null, 1)))))));
        Map<String, String> enums = NominalEnumOverloads.eligibleEnumSymbols(program);
        assertFalse(enums.containsKey("Color"));
        assertNotEquals(enums.get("alpha::Color"), enums.get("beta::Color"));
    }

    @Test
    void directEnumFncsAdmitOnlyPairwiseDistinctNominalSignatures() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(ENUMS + """
                fnc go(Color c): int { return 1; }
                fnc go(Mode m): int { return 2; }
                """)));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(ENUMS + """
                        fnc go(Color c): int { return 1; }
                        fnc go(Mode m): int { return 2; }
                        fnc go(Color again): int { return 3; }
                        """)));
        assertTrue(duplicate.getMessage().contains("arity")
                || duplicate.getMessage().contains("pairwise"),
                duplicate.getMessage());
    }

    @Test
    void sameArityEnumMethodsAreNotPrematurelyEnabledWithoutVtableBinding() {
        String declarations = """
                define enum Color as
                    Red
                end
                define enum Mode as
                    Ready: int
                end
                """;
        for (String methods : List.of(
                """
                pub pick(Color value): int { return 1; }
                pub pick(Mode value): int { return 2; }
                """,
                """
                pub static fnc pick(Color value): int { return 1; }
                pub static fnc pick(Mode value): int { return 2; }
                """)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(declarations
                            + "define class Dispatcher as\n" + methods + "end\n")));
            assertTrue(error.getMessage().contains("already has arity 1"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("not enabled"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("no structural or runtime overload lookup"),
                    error.getMessage());
        }
    }

    @Test
    void nominalEnumCasesAreNotOverloadTypesAndNonprimitivePayloadsCannotQualify() {
        var symbolTable = symbols();
        assertThrows(IllegalArgumentException.class,
                () -> signature("use", "Color.Red"));
        assertThrows(IllegalArgumentException.class,
                () -> signature("use", "Color.Blue"));
        assertEquals(PairStatus.SAME_SIGNATURE,
                NominalEnumOverloads.compare(signature("use", "Color"),
                        signature("use", "Color")));
        for (String payload : List.of("Any", "Unknown", "List<int>", "Map<int,int>",
                "Option<int>", "Color", "Fnc<int, int>")) {
            Ast.TypeRef unsafe = payload.equals("Color")
                    ? Ast.TypeRef.simple("Color")
                    : Ast.TypeRef.simple(payload);
            Ast.EnumDecl declaration = new Ast.EnumDecl("E",
                    List.of(new Ast.EnumCase("Case", unsafe, 1)));
            assertFalse(NominalEnumOverloads.eligibleDiscriminator(declaration),
                    payload);
        }
        assertNotNull(symbolTable.get("Color"));
    }

    @Test
    void incompleteDiscriminatorProofMustNotSelectAnOtherwiseUniqueTarget() {
        Signature colorColor = signature("go", "Color", "Color");
        Signature modeMode = signature("go", "Mode", "Mode");
        Map<String, String> enums = symbols();
        assertThrows(IllegalArgumentException.class, () -> NominalEnumOverloads.select(
                List.of(colorColor, modeMode), Map.of(0, enums.get("Color"))));
        assertThrows(IllegalArgumentException.class, () -> NominalEnumOverloads.select(
                List.of(colorColor, modeMode), Map.of(1, enums.get("Color"))));
        assertThrows(IllegalArgumentException.class, () -> NominalEnumOverloads.select(
                List.of(colorColor, modeMode), Map.of(0, enums.get("Color"), 1, enums.get("Mode"))));
        assertSame(colorColor, NominalEnumOverloads.select(
                List.of(colorColor, modeMode),
                Map.of(0, enums.get("Color"), 1, enums.get("Color"))));
        assertThrows(IllegalArgumentException.class, () -> NominalEnumOverloads.select(
                List.of(colorColor, modeMode),
                Map.of(0, enums.get("Color"), 1, enums.get("Color"), 2, "other")));
    }

    @Test
    void primitiveAliasIsNotASeparateOverloadSignature() {
        Signature upper = signature("go", "Color", "String");
        Signature lower = signature("go", "Color", "string");
        assertEquals(PairStatus.SAME_SIGNATURE, NominalEnumOverloads.compare(upper, lower));
    }

    @Test
    void compilerBindsEqualLookingCallsByIdentityNotStructuralEquality() {
        Ast.Program checked = TypeChecker.checkTypes(Parser.parse("""
                fnc step(int value): int { return value + 1; }
                fnc first(): int { return step(4); }
                fnc second(): int { return step(4); }
                pub routine main(): void {
                    stdio.stdout.write(first());
                    stdio.stdout.write(second());
                }
                """));
        List<Ast.CallExpr> repeated = checked.boundDirectCalls().keySet().stream()
                .filter(call -> call.callee() instanceof Ast.NameExpr named
                        && named.name().equals("step"))
                .toList();
        assertEquals(2, repeated.size());
        assertNotSame(repeated.get(0), repeated.get(1));
        assertEquals(repeated.get(0), repeated.get(1),
                "the AST record keys are structurally equal");
        assertSame(checked.boundDirectCalls().get(repeated.get(0)),
                checked.boundDirectCalls().get(repeated.get(1)));
        assertThrows(UnsupportedOperationException.class, () ->
                checked.boundDirectCalls().clear());
    }

    @Test
    void graalRuntimeUsesTheStaticallyBoundDirectCallTargets() throws Exception {
        String program = """
                fnc step(int value): int { return value + 1; }
                fnc first(): int { return step(4); }
                fnc second(): int { return step(4); }
                pub routine main(): void {
                    stdio.stdout.write(first());
                    stdio.stdout.write(second());
                }
                """;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                dev.oreslang.runtime.IsolatePolicy.developer()
                        .restrictedContextBuilder(dev.oreslang.runtime.ExecutionProfile.serverJit())
                        .out(output).build()) {
            context.eval(org.graalvm.polyglot.Source.newBuilder(
                    OresLanguage.ID, program, "nominal-static-bindings.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("55", output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void onlyProvenNominalEnumOwnersSupplyStaticArgumentProofs() {
        Ast.Program program = Parser.parse(ENUMS);
        var registry = dev.oreslang.types.TaggedMessageContracts.forTypes(program);
        var eligible = NominalEnumOverloads.eligibleEnumSymbols(program);
        assertEquals(eligible.get("Color"), NominalEnumOverloads.staticEnumArgumentSymbol(
                new dev.oreslang.types.Types.Named("Color.Red", List.of()), registry, eligible));
        assertEquals(eligible.get("Mode"), NominalEnumOverloads.staticEnumArgumentSymbol(
                new dev.oreslang.types.Types.Named("Mode", List.of()), registry, eligible));
        assertNull(NominalEnumOverloads.staticEnumArgumentSymbol(
                new dev.oreslang.types.Types.Named("Color.NotACase", List.of()), registry, eligible));
        assertNull(NominalEnumOverloads.staticEnumArgumentSymbol(
                new dev.oreslang.types.Types.Named("Unsupported", List.of()), registry, eligible));
        assertNull(NominalEnumOverloads.staticEnumArgumentSymbol(
                dev.oreslang.types.Types.Unknown.INSTANCE, registry, eligible));
        assertNull(NominalEnumOverloads.staticEnumArgumentSymbol(
                dev.oreslang.types.Types.unionOf(
                        new dev.oreslang.types.Types.Named("Color", List.of()),
                        new dev.oreslang.types.Types.Named("Mode", List.of())),
                registry, eligible));
    }

    @Test
    void immutableOverloadKeysCannotBypassSignatureValidation() {
        var color = new NominalEnumOverloads.Position(
                NominalEnumOverloads.Kind.ENUM, "unit::Color");
        assertThrows(IllegalArgumentException.class, () ->
                new Signature("go", java.util.Collections.nCopies(
                        NominalEnumOverloads.MAX_OVERLOAD_ARITY + 1, color)));
        assertThrows(IllegalArgumentException.class, () ->
                new Signature("  ", List.of(color)));
        assertThrows(IllegalArgumentException.class, () ->
                new NominalEnumOverloads.Position(NominalEnumOverloads.Kind.ENUM, ""));
        assertThrows(IllegalArgumentException.class, () ->
                new NominalEnumOverloads.Position(NominalEnumOverloads.Kind.ENUM, "Color"));
        assertThrows(IllegalArgumentException.class, () ->
                new NominalEnumOverloads.Position(NominalEnumOverloads.Kind.PRIMITIVE, "Any"));
        assertEquals(
                new NominalEnumOverloads.Position(NominalEnumOverloads.Kind.PRIMITIVE, "string"),
                new NominalEnumOverloads.Position(NominalEnumOverloads.Kind.PRIMITIVE, "String"));
    }

    @Test
    void overloadPolicyRejectsExcessiveSignatureAndCandidateCounts() {
        var enumIds = symbols();
        Ast.Param color = param("Color");
        assertEquals(NominalEnumOverloads.MAX_OVERLOAD_ARITY,
                NominalEnumOverloads.signature("go",
                        java.util.Collections.nCopies(
                                NominalEnumOverloads.MAX_OVERLOAD_ARITY, color),
                        enumIds).arity());
        IllegalArgumentException hugeArity = assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.signature("go",
                        java.util.Collections.nCopies(
                                NominalEnumOverloads.MAX_OVERLOAD_ARITY + 1, color),
                        enumIds));
        assertTrue(hugeArity.getMessage().contains("arity limit"));
        Signature valid = signature("go", "Color");
        IllegalArgumentException hugeFamily = assertThrows(IllegalArgumentException.class,
                () -> NominalEnumOverloads.select(
                        java.util.Collections.nCopies(
                                NominalEnumOverloads.MAX_SAME_ARITY_CANDIDATES + 1, valid),
                        Map.of(0, enumIds.get("Color"))));
        assertTrue(hugeFamily.getMessage().contains("candidate limit"));
    }
}
