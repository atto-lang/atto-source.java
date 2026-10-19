package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DefaultArgumentPolicy;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.V3DefaultArgValsPolicy;
import dev.oreslang.types.V3CallLoweringPreview;
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
        assertTrue(formal.type().isStagedV3Option());
        assertEquals("$v3_option_sugar$", formal.type().name());
        assertEquals("int", formal.type().arguments().getFirst().name());
        assertFalse(formal.optional());
        assertFalse(formal.argumentOmittable());
        assertTrue(formal.calleeType().isStagedV3Option());
        assertEquals("Option", formal.calleeType().stagedV3PreviewType().name());
        assertTrue(formal.valueType().isStagedV3Option());
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
        assertTrue(first.type().isStagedV3Option());
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

    @Test
    void v3DefaultedOverloadsCannotOccupyOverlappingArityIntervals() {
        var one = function("""
                pub fnc choose(int x): void { return; }
                """);
        var two = function("""
                @DefaultArgVals(extra: 5)
                pub fnc choose(int x, int extra?): void { return; }
                """);
        var signatures = List.of(
                new OptionalCallShapeBinder.StagedV3Signature(
                        one.parameters(), one.annotations()),
                new OptionalCallShapeBinder.StagedV3Signature(
                        two.parameters(), two.annotations()));
        assertThrows(IllegalArgumentException.class, () ->
                OptionalCallShapeBinder.selectStagedV3(signatures, List.of(
                        Ast.CallArgument.value(new Ast.LiteralExpr(1L)))));
    }

    @Test
    void legacyBinderCannotTreatV3NameSuffixAsOrdinaryRequiredFormal() {
        var f = function("""
                @DefaultArgVals(x: 5)
                pub fnc choose(int x?): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () ->
                OptionalCallShapeBinder.formalParameterShapes(f.parameters()));
        assertThrows(IllegalArgumentException.class, () ->
                OptionalCallShapeBinder.declaredParameterShapes(f.parameters()));
        assertEquals(List.of(true),
                OptionalCallShapeBinder.stagedV3ParameterShapes(
                        f.parameters(), f.annotations()).stream()
                        .map(OptionalCallShapeBinder.Parameter::optional).toList());
    }

    @Test
    void forgedSelfCannotBecomeAUserDefaultArgument() {
        var formal = new Ast.Param(Ast.TypeRef.simple("int"), "self");
        var annotation = new Ast.Annotation("DefaultArgVals", List.of(),
                List.of(new Ast.NamedDefault("self", Ast.TypeRef.intLiteral("5"))));
        assertThrows(IllegalArgumentException.class, () ->
                V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(formal), List.of(annotation)));
    }
    @Test
    void requiredV3OptionTypeCannotBypassCompilerAdmission() {
        Ast.Program program = Parser.parseStagedV3("""
                pub fnc f(int? value): void { return; }
                """);
        Ast.Param param = ((Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst()).parameters().getFirst();
        assertTrue(param.type().containsStagedV3Option());
        assertFalse(param.argumentOmittable());
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(program));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.formalParameterShapes(List.of(param)));
    }

    @Test
    void returnTypeAndNestedLocalV3TypeSugarMustAlsoFailClosed() {
        Ast.Program withReturn = Parser.parseStagedV3("""
                pub fnc f(): int? { return None; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(withReturn));
        Ast.Program withLocal = Parser.parseStagedV3("""
                pub fnc f(): void {
                    val Box<string?> result = None;
                    return;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(withLocal));
    }

    @Test
    void forgedStagedTypeMarkersCannotUseLegacyAbiPreview() {
        Ast.TypeRef staged = Ast.TypeRef.stagedV3Option(Ast.TypeRef.simple("int"));
        Ast.Param p = new Ast.Param(staged, "value");
        assertTrue(p.type().containsStagedV3Option());
        assertTrue(p.calleeType().isStagedV3Option()); // No ABI normalization
        assertEquals("Option", p.calleeType().stagedV3PreviewType().name()); // Preview only
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(List.of(p)));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.formalParameterShapes(List.of(p)));
    }

    @Test
    void previewPreservesLexicalEffectsAndExplicitOmissionProvenance() {
        var f = function("""
                @DefaultArgVals(b: 9)
                pub fnc f(int a, int b?, bool c): void { return; }
                """);
        Ast.Expr first = new Ast.LiteralExpr(true);
        Ast.Expr second = new Ast.LiteralExpr(5L);
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("c", first),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", second));
        var shape = OptionalCallShapeBinder.stagedV3ParameterShapes(
                f.parameters(), f.annotations());
        var binder = OptionalCallShapeBinder.bindParsed(shape, items);
        var preview = V3CallLoweringPreview.plan(
                f.parameters(), f.annotations(), items, binder);
        assertSame(first, preview.unevaluatedExpressionsInSourceOrder().get(0));
        assertSame(second, preview.unevaluatedExpressionsInSourceOrder().get(1));
        assertEquals(List.of(
                V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                V3CallLoweringPreview.SlotAction.DEFAULT_SCALAR,
                V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED),
                preview.formalOrderSlots().stream().map(V3CallLoweringPreview.Slot::action).toList());
        assertEquals(List.of(2, 1, 0),
                preview.formalOrderSlots().stream()
                        .map(V3CallLoweringPreview.Slot::originalCallItemIndex).toList());
        assertEquals(List.of(1, -1, 0),
                preview.formalOrderSlots().stream()
                        .map(V3CallLoweringPreview.Slot::evaluatedSourceIndex).toList());
        assertEquals(DefaultArgumentPolicy.Literal.integer(9),
                preview.formalOrderSlots().get(1).closedDefault());
    }

    @Test
    void previewDistinguishesSuppliedNoneFromDefaultNoneAndImplicitNone() {
        var f = function("""
                @DefaultArgVals(a: None)
                pub fnc f(int? a, string? b?): void { return; }
                """);
        List<Ast.CallArgument> noneSupplied = List.of(
                Ast.CallArgument.named("a", new Ast.NameExpr("None")));
        var signature = OptionalCallShapeBinder.stagedV3ParameterShapes(
                f.parameters(), f.annotations());
        var supplied = V3CallLoweringPreview.plan(
                f.parameters(), f.annotations(), noneSupplied,
                OptionalCallShapeBinder.bindParsed(signature, noneSupplied));
        assertEquals(V3CallLoweringPreview.SlotAction.CHECK_SUPPLIED,
                supplied.formalOrderSlots().get(0).action());
        assertEquals(V3CallLoweringPreview.SlotAction.OMITTED_OPTION_NONE,
                supplied.formalOrderSlots().get(1).action());
        assertEquals(1, supplied.unevaluatedExpressionsInSourceOrder().size());

        var missing = V3CallLoweringPreview.plan(
                f.parameters(), f.annotations(), List.of(),
                OptionalCallShapeBinder.bindParsed(signature, List.of()));
        assertEquals(V3CallLoweringPreview.SlotAction.DEFAULT_NONE,
                missing.formalOrderSlots().get(0).action());
        assertEquals(V3CallLoweringPreview.SlotAction.OMITTED_OPTION_NONE,
                missing.formalOrderSlots().get(1).action());
        assertTrue(missing.unevaluatedExpressionsInSourceOrder().isEmpty());
    }

    @Test
    void previewRejectsForgedCallPlanBeforeAnyExpressionIsExposed() {
        var f = function("""
                @DefaultArgVals(a: 5)
                pub fnc f(int a?, bool b): void { return; }
                """);
        List<Ast.CallArgument> actual = List.of(
                Ast.CallArgument.named("b", new Ast.LiteralExpr(true)),
                Ast.CallArgument.omit("a"));
        var shape = OptionalCallShapeBinder.stagedV3ParameterShapes(
                f.parameters(), f.annotations());
        var genuine = OptionalCallShapeBinder.bindParsed(shape, actual);
        var reordered = new OptionalCallShapeBinder.Plan(
                List.of(0, -1), -1, List.of(), List.of(1), List.of(0));
        assertNotEquals(genuine, reordered);
        assertThrows(IllegalArgumentException.class,
                () -> V3CallLoweringPreview.plan(f.parameters(), f.annotations(), actual, reordered));
    }

    @Test
    void callableDefaultsCannotAttachToOrdinaryClassFieldSpellings() {
        String[] fields = {
                "val int id = 1;",
                "let int id = 1;",
                "id: int = 1;",
                "int id: 1;"
        };
        for (String field : fields) {
            String source = "define class Holder as\n"
                    + "@DefaultArgVals(id: 2)\n"
                    + field + "\nend";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStagedV3(source),
                    "class field must reject callable default annotation: " + field);
        }
    }

    @Test
    void nestedBorrowOrInferenceCannotHideInsideStagedV3OptionSugar() {
        Ast.TypeRef borrow = Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false);
        Ast.TypeRef nestedBorrow = new Ast.TypeRef("List", List.of(borrow), false);
        Ast.TypeRef nestedInfer = new Ast.TypeRef("List",
                List.of(Ast.TypeRef.inferred()), false);
        Ast.TypeRef inferredArguments = new Ast.TypeRef("List", List.of(), true);

        for (Ast.TypeRef payload : List.of(
                borrow, nestedBorrow, nestedInfer, inferredArguments,
                Ast.TypeRef.simple("void"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> Ast.TypeRef.stagedV3Option(payload),
                    "unlowered v3 Option cannot take an unsafe payload: " + payload);
        }

        Ast.TypeRef inner = Ast.TypeRef.stagedV3Option(Ast.TypeRef.simple("int"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.TypeRef.stagedV3Option(inner));
        // Nested type sugar is legal in other type positions: List<int?>?
        // means Option<List<Option<int>>>. Only adjacent T?? is reserved.
        Ast.TypeRef nested = Ast.TypeRef.stagedV3Option(
                new Ast.TypeRef("List", List.of(inner), false));
        assertTrue(nested.isStagedV3Option());
        assertEquals("Option", nested.stagedV3PreviewType().name());
        assertEquals("List", nested.stagedV3PreviewType().arguments().getFirst().name());
        assertEquals("Option", nested.stagedV3PreviewType().arguments().getFirst()
                .arguments().getFirst().name());

        // Explicit, nominally unresolved Option<int> remains a legal
        // *preview* payload; the type checker must still establish identity.
        Ast.TypeRef explicitOption = new Ast.TypeRef(
                "Option", List.of(Ast.TypeRef.simple("int")), false);
        assertTrue(Ast.TypeRef.stagedV3Option(explicitOption).isStagedV3Option());
    }

    @Test
    void stagedDefaultAndOmissionCannotLaunderBorrowedGenericOption() {
        Ast.TypeRef borrowed = Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false);
        Ast.TypeRef nested = new Ast.TypeRef("List", List.of(borrowed), false);
        Ast.TypeRef option = new Ast.TypeRef("Option", List.of(nested), false);
        Ast.Param implicitNone = new Ast.Param(option, "value",
                false, false, List.of(), true);
        assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(implicitNone), List.of()));

        Ast.Param defaulted = new Ast.Param(option, "value");
        Ast.Annotation annotatedNone = new Ast.Annotation(
                "DefaultArgVals", List.of(),
                List.of(new Ast.NamedDefault("value", Ast.TypeRef.simple("None"))));
        assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(defaulted), List.of(annotatedNone)));

        Ast.TypeRef unresolved = new Ast.TypeRef("Option",
                List.of(new Ast.TypeRef("List", List.of(), true)), false);
        Ast.Param unresolvedDefault = new Ast.Param(unresolved, "value");
        assertThrows(IllegalArgumentException.class,
                () -> V3DefaultArgValsPolicy.validateDeclaration(
                        List.of(unresolvedDefault), List.of(annotatedNone)));

        // Required Option<List<borrow int>> is not a default argument:
        // remain staged; normal ownership/type resolution controls it.
        assertFalse(V3DefaultArgValsPolicy.validateDeclaration(
                List.of(defaulted), List.of()).getFirst().omittable());
    }

}
