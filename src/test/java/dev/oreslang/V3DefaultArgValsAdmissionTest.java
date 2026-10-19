package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DefaultArgumentPolicy;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.V3DefaultArgValsPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Staged parser/contract conformance. No guest-language execution is enabled.
 * Default Parser.parse continues to use v1/v2 language semantics.
 */
final class V3DefaultArgValsAdmissionTest {
    private static Ast.FunctionDecl function(String source) {
        return Parser.parseStagedV3(source).modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void legacyModeNeverSilentlyReinterpretsQuestionMarksOrAnnotations() {
        Ast.Param legacy = Parser.parse("""
                pub fnc f(int? a): void { return; }
                """).modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow().parameters().getFirst();
        assertTrue(legacy.optional());
        assertTrue(legacy.type().isOmittableFormal());
        assertFalse(legacy.argumentOmittable());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                @DefaultArgVals(a: 5)
                pub fnc f(int a): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc f(int a?): void { return; }
                """));
    }

    @Test
    void typeSuffixQuestionMeansOptionTypeButNotOmittableArgument() {
        Ast.Param formal = function("""
                pub fnc f(int? a): void { return; }
                """).parameters().getFirst();
        assertEquals("Option", formal.type().name());
        assertEquals("int", formal.type().arguments().getFirst().name());
        assertFalse(formal.optional());
        assertFalse(formal.argumentOmittable());
        assertEquals("Option", formal.calleeType().name());
        assertEquals("Option", formal.valueType().name());
        assertFalse(OptionalCallShapeBinder.stagedV3ParameterShapes(
                List.of(formal), List.of()).getFirst().optional());
    }

    @Test
    void omittableScalarDoesNotBecomeOptionAndRequiresDefault() {
        Ast.FunctionDecl function = function("""
                @DefaultArgVals(a: 5, b: "charlie")
                pub fnc run(int a?, string b?): void { return; }
                """);
        assertEquals(2, function.annotations().getFirst().namedDefaults().size());
        var previews = V3DefaultArgValsPolicy.validateDeclaration(
                function.parameters(), function.annotations());
        assertEquals(List.of("int", "string"),
                previews.stream().map(p -> p.bodyType().name()).toList());
        assertTrue(previews.stream().allMatch(V3DefaultArgValsPolicy.FormalPreview::omittable));
        assertTrue(previews.stream().noneMatch(V3DefaultArgValsPolicy.FormalPreview::optionTyped));
        assertEquals(DefaultArgumentPolicy.Literal.integer(5), previews.getFirst().defaultValue());
        assertEquals(DefaultArgumentPolicy.Literal.string("charlie"), previews.get(1).defaultValue());
        assertThrows(IllegalArgumentException.class, () -> function("""
                pub fnc run(int a?): void { return; }
                """));
    }

    @Test
    void defaultValuesAlsoMakeBareParametersOmittableWithoutNameSuffix() {
        Ast.FunctionDecl f = function("""
                @DefaultArgVals(a: 5)
                pub fnc run(int a, bool b): void { return; }
                """);
        var plan = OptionalCallShapeBinder.stagedV3ParameterShapes(f.parameters(), f.annotations());
        assertEquals(List.of(true, false), plan.stream()
                .map(OptionalCallShapeBinder.Parameter::optional).toList());
        assertEquals(List.of(-1, 0), OptionalCallShapeBinder.bind(plan,
                List.of(OptionalCallShapeBinder.Argument.named("b")))
                .sourceIndexByParameter());
    }

    @Test
    void suppliedOptionDefaultsPreserveUnderlyingAndOuterNoneSemantics() {
        var f = function("""
                @DefaultArgVals(a: 5, b: None)
                pub fnc run(int? a, string? b): void { return; }
                """);
        var data = V3DefaultArgValsPolicy.validateDeclaration(f.parameters(), f.annotations());
        assertEquals("Option", data.getFirst().bodyType().name());
        assertTrue(data.getFirst().optionTyped());
        assertEquals(DefaultArgumentPolicy.Literal.integer(5), data.getFirst().defaultValue());
        assertEquals(DefaultArgumentPolicy.Literal.none(), data.get(1).defaultValue());
        assertTrue(data.get(1).omittable());
    }

    @Test
    void combinedQuestionMarksHaveDifferentJobs() {
        var f = function("""
                pub fnc f(int? a?, string b): void { return; }
                """);
        var first = f.parameters().getFirst();
        assertEquals("Option", first.type().name());
        assertTrue(first.argumentOmittable());
        assertFalse(first.optional());
        assertEquals(List.of(true, false), OptionalCallShapeBinder
                .stagedV3ParameterShapes(f.parameters(), f.annotations()).stream()
                .map(OptionalCallShapeBinder.Parameter::optional).toList());
    }

    @Test
    void nestedOptionIsExplicitAndCannotUseAmbiguousInnerDefault() {
        assertEquals("Option", function("""
                pub fnc f(Option<int>? a?): void { return; }
                """).parameters().getFirst().type().arguments().getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(a: 5)
                pub fnc f(Option<int>? a): void { return; }
                """));
    }

    @Test
    void bothNamedAndPerFormalDefaultsOnSameSlotFail() {
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(a: 5)
                pub fnc f(@Default(7) int a): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(a: 5)
                @DefaultArgVals(b: 6)
                pub fnc f(int a, int b): void { return; }
                """));
    }

    @Test
    void rejectUnknownAndDuplicateNamedDefaults() {
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(unknown: 5)
                pub fnc f(int a): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(a: 5, a: 7)
                pub fnc f(int a): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> function("""
                @DefaultArgVals(a: 5)
                pub fnc f(int a, int a): void { return; }
                """));
    }

    @Test
    void rejectUnsafeOrMisTypedDefaults() {
        for (String source : List.of(
                "@DefaultArgVals(a: \"five\") fnc f(int a): void { return; }",
                "@DefaultArgVals(a: None) fnc f(int a): void { return; }",
                "@DefaultArgVals(a: 6) fnc f(string? a): void { return; }",
                "@DefaultArgVals(a: compute()) fnc f(int a): void { return; }",
                "@DefaultArgVals(a: 1+2) fnc f(int a): void { return; }",
                "@DefaultArgVals(a: 999999999999999999999999) fnc f(int a): void { return; }",
                "@DefaultArgVals(a=5) fnc f(int a): void { return; }",
                "@DefaultArgVals() fnc f(int a): void { return; }")) {
            assertThrows(IllegalArgumentException.class, () -> function(source), source);
        }
    }

    @Test
    void sourceReorderingAndExplicitOmitUseTheSameVerifiedBinder() {
        var f = function("""
                @DefaultArgVals(b: 9)
                fnc f(int a, int b?, bool c): void { return; }
                """);
        var shape = OptionalCallShapeBinder.stagedV3ParameterShapes(
                f.parameters(), f.annotations());
        var plan = OptionalCallShapeBinder.bind(shape, List.of(
                OptionalCallShapeBinder.Argument.named("c"),
                OptionalCallShapeBinder.Argument.namedOmission("b"),
                OptionalCallShapeBinder.Argument.named("a")));
        assertEquals(List.of(1, -1, 0), plan.sourceIndexByParameter());
        assertEquals(List.of(1), plan.explicitlyOmittedParameterIndices().stream()
                .map(i -> plan.omittedCallItemBindings().stream()
                        .filter(x -> x.parameterIndex() == i)
                        .findFirst().orElseThrow().callItemIndex()).toList());
    }

    @Test
    void noUnloweredV3MetadataCanReachInterpreterBypass() {
        Ast.Program program = Parser.parseStagedV3("""
                @DefaultArgVals(a: 5)
                pub fnc f(int a): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(program));

        Ast.Program suffixProgram = Parser.parseStagedV3("""
                pub fnc f(int? a?): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(suffixProgram));
    }

    @Test
    void malformedFabricatedAnnotationIsNeverAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.Annotation(
                "DefaultArgVals", List.of(Ast.TypeRef.intLiteral("3"))));
        assertThrows(IllegalArgumentException.class, () -> new Ast.Annotation(
                "DefaultArgVals", List.of(), List.of(
                        new Ast.NamedDefault("a", Ast.TypeRef.intLiteral("3")),
                        new Ast.NamedDefault("a", Ast.TypeRef.intLiteral("4")))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.NamedDefault("a", Ast.TypeRef.simple("unverified")));
    }

    @Test
    void defaultsSurviveModuleDeclarationASTAndStaticBinderPreview() {
        var fn = function("""
                define module Work as
                    @DefaultArgVals(count: 3)
                    pub fnc run(int count?, string label): void { return; }
                end
                """);
        assertEquals("DefaultArgVals", fn.annotations().getFirst().name());
        assertTrue(fn.parameters().getFirst().argumentOmittable());
        var shapes = OptionalCallShapeBinder.stagedV3ParameterShapes(
                fn.parameters(), fn.annotations());
        assertEquals(List.of(true, false), shapes.stream()
                .map(OptionalCallShapeBinder.Parameter::optional).toList());
    }

    @Test
    void namedDefaultsOnMethodsAndConstructorsAreStagedNotExecutable() {
        Ast.Program classProgram = Parser.parseStagedV3("""
                define class Box as
                    val int id;
                    @DefaultArgVals(id: 3)
                    constructor(int id?) {
                        self.id = id;
                    }
                    @DefaultArgVals(x: 4)
                    pub fnc answer(int x?): int {
                        return x;
                    }
                end
                """);
        Ast.ClassDecl klass = classProgram.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast).findFirst().orElseThrow();
        assertEquals("DefaultArgVals", klass.constructor().annotations().getFirst().name());
        assertTrue(klass.constructor().parameters().getFirst().argumentOmittable());
        assertEquals("DefaultArgVals", klass.methods().getFirst().annotations().getFirst().name());
        assertThrows(IllegalArgumentException.class, () ->
                OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(classProgram));
    }

    @Test
    void annotationIsNotAllowedOnModulesOrFields() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStagedV3("""
                @DefaultArgVals(a: 5)
                define module M as
                    pub fnc f(int a): void { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStagedV3("""
                define class Box as
                    @DefaultArgVals(a: 5)
                    val int a = 3;
                end
                """));
    }

    @Test
    void lambdaAndInterfaceNameOmissionRequiresDefaultOrOptionType() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStagedV3("""
                pub fnc f(): void {
                    val inner = (int x?) -> x;
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStagedV3("""
                define interface I as
                    fnc f(int x?): int;
                end
                """));
        assertDoesNotThrow(() -> Parser.parseStagedV3("""
                pub fnc f(): void {
                    val inner = (int? x?) -> x;
                    return;
                }
                """));
    }
}
