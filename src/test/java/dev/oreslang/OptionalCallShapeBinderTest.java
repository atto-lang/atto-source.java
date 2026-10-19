package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.OptionalCallShapeBinder.Argument;
import dev.oreslang.types.OptionalCallShapeBinder.Parameter;
import dev.oreslang.types.OptionalCallShapeBinder.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-integration call-plan invariants. Passing these tests does not mean
 * the parser or interpreter currently accepts named/optional value syntax.
 */
final class OptionalCallShapeBinderTest {
    private static final List<Parameter> FOO = List.of(
            Parameter.required("a"),
            Parameter.optional("b"),
            Parameter.required("c"));

    @Test
    void callItemCountAndOmissionOriginsDistinguishAbsentSyntaxFromNoneValue() {
        Plan omitted = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.underscore(), Argument.positional()));
        assertEquals(3, omitted.callItemCount());
        assertEquals(2, omitted.suppliedArgumentCount());
        assertEquals(List.of(1), omitted.omittedCallItemIndices());

        Plan suppliedNone = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(3, suppliedNone.callItemCount());
        assertEquals(3, suppliedNone.suppliedArgumentCount());
        assertEquals(List.of(), suppliedNone.omittedCallItemIndices());

        Plan implicit = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("a"), Argument.named("c")));
        assertEquals(2, implicit.callItemCount());
        assertEquals(List.of(), implicit.omittedCallItemIndices());
    }

    @Test
    void multipleExplicitOmissionsKeepOriginalSourceIndexes() {
        List<Parameter> params = List.of(
                Parameter.optional("a"), Parameter.optional("b"),
                Parameter.required("c"), Parameter.variadic("rest"));
        Plan named = OptionalCallShapeBinder.bind(params, List.of(
                Argument.namedOmission("b"),
                Argument.named("c"),
                Argument.namedOmission("a")));
        assertEquals(List.of(1), named.callItemIndexByEvaluatedSource());
        assertEquals(List.of(0, 2), named.omittedCallItemIndices());
        assertEquals(List.of(
                new OptionalCallShapeBinder.OmittedCallItem(0, 1),
                new OptionalCallShapeBinder.OmittedCallItem(2, 0)),
                named.omittedCallItemBindings());
        assertEquals(3, named.callItemCount());
        assertEquals(List.of(1, 0), named.explicitlyOmittedParameterIndices());

        Plan positional = OptionalCallShapeBinder.bind(params, List.of(
                Argument.underscore(), Argument.positionalOmission(),
                Argument.positional(), Argument.positional()));
        assertEquals(List.of(2, 3), positional.callItemIndexByEvaluatedSource());
        assertEquals(List.of(0, 1), positional.omittedCallItemIndices());
        assertEquals(List.of(
                new OptionalCallShapeBinder.OmittedCallItem(0, 0),
                new OptionalCallShapeBinder.OmittedCallItem(1, 1)),
                positional.omittedCallItemBindings());
        assertEquals(List.of(3), positional.variadicSourceIndices().stream()
                .map(positional::callItemIndex).toList());
    }

    @Test
    void rawArgumentOriginsMustBeWithinCallItemCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(0, 3)));
        assertDoesNotThrow(() -> new Plan(List.of(0, -1, 1), -1, List.of(),
                List.of(1), List.of(0, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(1, 3)));
    }

    @Test
    void explicitOmissionCannotUseLegacyIdentityOriginConstructor() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(), List.of(1)));
        Plan original = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.underscore(), Argument.positional()));
        assertEquals(List.of(0, 2), original.callItemIndexByEvaluatedSource());
        assertTrue(original.explicitlyOmitted(1));
    }

    @Test
    void callItemOriginAndOmissionComplementStayConsistent() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.underscore(), Argument.positional()));
        assertEquals(3, plan.callItemCount());
        assertEquals(List.of(1), plan.omittedCallItemIndices());
        assertEquals(List.of(0, 2), plan.callItemIndexByEvaluatedSource());

        Plan named = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.namedUnderscore("b"), Argument.named("a")));
        assertEquals(List.of(1), named.omittedCallItemIndices());
        assertEquals(List.of(0, 2), named.callItemIndexByEvaluatedSource());

        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1,
                        List.of(), List.of(1), List.of(0, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1,
                        List.of(), List.of(1), List.of(0, 0)));
    }

    @Test
    void reconstructedVariadicPlanCannotPermuteOrInterleaveRestValues() {
        // These forged public Plans previously passed uniqueness/contiguity
        // checks even though they would reverse or interleave rest payloads.
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 1, List.of(2, 1),
                        List.of(), List.of(0, 1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(2, -1), 1, List.of(0, 1),
                        List.of(), List.of(0, 1, 2)));

        Plan valid = new Plan(List.of(0, -1), 1, List.of(1, 2),
                List.of(), List.of(0, 1, 2));
        assertEquals(List.of(1, 2), valid.variadicSourceIndices());
        assertEquals(List.of(0, 1, 2), valid.callItemIndexByEvaluatedSource());
    }

    @Test
    void ordinaryBinderPreservesVariadicElementSequenceAfterOptionalOmission() {
        List<Parameter> params = List.of(
                Parameter.required("a"), Parameter.optional("b"),
                Parameter.required("c"), Parameter.variadic("tags"));
        Plan plan = OptionalCallShapeBinder.bind(params, List.of(
                Argument.positional(), Argument.underscore(),
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(2, 3), plan.variadicSourceIndices());
        assertEquals(List.of(0, 2, 3, 4), plan.callItemIndexByEvaluatedSource());
        assertEquals(List.of(new OptionalCallShapeBinder.OmittedCallItem(1, 1)),
                plan.omittedCallItemBindings());
    }

    @Test
    void parsedSignatureFamilySelectionIsStaticAndArityOnly() {
        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        Ast.TypeRef stringType = Ast.TypeRef.simple("string");
        List<Ast.Param> shortForm = List.of(new Ast.Param(intType, "id"));
        List<Ast.Param> longerForm = List.of(
                new Ast.Param(intType, "id"),
                new Ast.Param(intType, "count"),
                new Ast.Param(Ast.TypeRef.omittableFormal(stringType), "label"));
        List<Ast.CallArgument> source = List.of(
                Ast.CallArgument.named("count", new Ast.LiteralExpr(2L)),
                Ast.CallArgument.named("id", new Ast.LiteralExpr(1L)),
                Ast.CallArgument.omit("label"));
        OptionalCallShapeBinder.Selection chosen =
                OptionalCallShapeBinder.selectParsed(List.of(shortForm, longerForm), source);
        assertEquals(1, chosen.signatureIndex());
        assertEquals(List.of(1, 0, -1), chosen.plan().sourceIndexByParameter());
        assertEquals(List.of(0, 1), chosen.plan().callItemIndexByEvaluatedSource());
        assertEquals(List.of(new OptionalCallShapeBinder.OmittedCallItem(2, 2)),
                chosen.plan().omittedCallItemBindings());
    }

    @Test
    void parsedSignatureOverlapsRejectBeforeLabelsCouldSelectOne() {
        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        List<Ast.Param> one = List.of(new Ast.Param(intType, "id"));
        List<Ast.Param> overlap = List.of(new Ast.Param(intType, "id"),
                new Ast.Param(Ast.TypeRef.omittableFormal(intType), "count"));
        List<Ast.CallArgument> source = List.of(
                Ast.CallArgument.named("id", new Ast.LiteralExpr(1L)));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.selectParsed(List.of(one, overlap), source));
        assertTrue(rejected.getMessage().contains("overlapping"),
                rejected::toString);
    }

    @Test
    void checkedSourceExpressionsAreLexicalAndDoNotEvaluateOmittedItems() {
        Ast.Expr last = new Ast.CallExpr(new Ast.NameExpr("lastEffect"), List.of());
        Ast.Expr first = new Ast.CallExpr(new Ast.NameExpr("firstEffect"), List.of());
        List<Ast.CallArgument> source = List.of(
                Ast.CallArgument.named("c", last),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", first));
        Plan plan = OptionalCallShapeBinder.bindParsed(FOO, source);
        List<Ast.Expr> order =
                OptionalCallShapeBinder.evaluatedExpressionsInSourceOrder(FOO, source, plan);
        assertEquals(2, order.size());
        assertSame(last, order.getFirst());
        assertSame(first, order.get(1));
        assertEquals(List.of(0, 2), plan.callItemIndexByEvaluatedSource());
        assertEquals(List.of(1, -1, 0), plan.sourceIndexByParameter());

        Plan forged = new Plan(List.of(0, -1, 1), -1, List.of(),
                List.of(1), List.of(0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.evaluatedExpressionsInSourceOrder(
                        FOO, source, forged));
    }

    @Test
    void stagedOptionalFormalsBindParsedSourceWithoutTreatingRequiredOptionAsOmittable() {
        Ast.Program parsed = Parser.parse("""
                pub fnc candidate(string? label, Option<int> required, bool? enabled): void {
                    return;
                }
                pub fnc caller(): void {
                    const result = candidate(enabled: _, required: None, label: "go");
                    return;
                }
                """);
        Ast.FunctionDecl target = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.FunctionDecl caller = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.BindingStmt callBinding = (Ast.BindingStmt) caller.body().getFirst();
        Ast.StagedCallExpr call = (Ast.StagedCallExpr) callBinding.initializer();
        List<Parameter> signature =
                OptionalCallShapeBinder.declaredParameterShapes(target.parameters());
        assertEquals(List.of(
                Parameter.optional("label"), Parameter.required("required"),
                Parameter.optional("enabled")), signature);
        Plan plan = OptionalCallShapeBinder.bindParsed(signature, call.arguments());
        assertEquals(List.of(1, 0, -1), plan.sourceIndexByParameter());
        assertEquals(List.of(1, 2), plan.callItemIndexByEvaluatedSource());
        assertEquals(List.of(0), plan.omittedCallItemIndices());
        assertEquals(List.of(new OptionalCallShapeBinder.OmittedCallItem(0, 2)),
                plan.omittedCallItemBindings());
        assertDoesNotThrow(() -> OptionalCallShapeBinder.verifyPlanAgainstSource(
                signature, call.arguments(), plan));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(signature, List.of(
                        Argument.namedOmission("required"),
                        Argument.named("label"))));
    }

    @Test
    void parsedPlanRejectsSwappedOmissionFormalBindings() {
        List<Parameter> parameters = List.of(
                Parameter.required("a"), Parameter.optional("b"),
                Parameter.optional("c"), Parameter.required("d"));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("d", new Ast.LiteralExpr(9L)),
                Ast.CallArgument.omit("c"),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(1L)));
        Plan correct = OptionalCallShapeBinder.bindParsed(parameters, items);
        assertEquals(List.of(2, 1), correct.explicitlyOmittedParameterIndices());
        assertEquals(List.of(1, 2), correct.omittedCallItemIndices());
        assertEquals(List.of(0, 3), correct.callItemIndexByEvaluatedSource());
        assertDoesNotThrow(() -> OptionalCallShapeBinder.verifyPlanAgainstSource(
                parameters, items, correct));

        // All dense-source checks succeed for this forged plan, but its
        // claimed formal-to-source omission relationship has been exchanged.
        Plan swapped = new Plan(correct.sourceIndexByParameter(),
                correct.variadicParameterIndex(), correct.variadicSourceIndices(),
                List.of(1, 2), correct.callItemIndexByEvaluatedSource());
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.verifyPlanAgainstSource(
                        parameters, items, swapped));
    }

    @Test
    void stagedAstCannotHideSpreadInsideOrdinaryArgumentValue() {
        Ast.Expr raw = new Ast.NameExpr("values");
        Ast.SpreadExpr hiddenSpread = new Ast.SpreadExpr(raw);
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CallArgument.value(hiddenSpread));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CallArgument.named("items", hiddenSpread));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CallArgument.spread(hiddenSpread));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CallArgument(null, hiddenSpread, false, false));
        assertEquals(raw, Ast.CallArgument.spread(raw).value());
    }

    @Test
    void parsedOptionalFormalMetadataBindsButRequiredOptionDoesNotBecomeOptional() {
        Ast.Program parsed = Parser.parse(
                "pub fnc target(string? a, Option<int> required, bool? c): void { return; }");
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        List<Parameter> shape = OptionalCallShapeBinder.formalParameterShapes(fn.parameters());
        assertEquals(List.of(true, false, true),
                shape.stream().map(Parameter::optional).toList());
        assertEquals(List.of("a", "required", "c"),
                shape.stream().map(Parameter::name).toList());

        Ast.Program callProgram = Parser.parse(
                "pub fnc entry(): void { const result = target(c: _, required: None); return; }");
        Ast.FunctionDecl entry = (Ast.FunctionDecl)
                callProgram.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) entry.body().getFirst();
        Ast.StagedCallExpr call = (Ast.StagedCallExpr) binding.initializer();
        Plan plan = OptionalCallShapeBinder.bindParsedFormals(fn.parameters(), call.arguments());
        assertEquals(List.of(-1, 0, -1), plan.sourceIndexByParameter());
        assertEquals(List.of(0), plan.callItemIndexByEvaluatedSource());
        assertEquals(List.of(0), plan.omittedCallItemIndices());
        assertTrue(plan.omitted(0));   // implicitly omitted T?
        assertTrue(plan.explicitlyOmitted(2));
        assertFalse(plan.omitted(1));  // supplied required Option<int> via None

        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(shape,
                        List.of(Argument.namedOmission("required"))));
    }

    @Test
    void parsedCallItemsBindWithoutErasingRawOmissionPositions() {
        Ast.Program program = Parser.parse(
                "pub fnc test(): void { const v = f(c: sideC(), b: _, a: sideA()); return; }");
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) fn.body().getFirst();
        Ast.StagedCallExpr call = (Ast.StagedCallExpr) binding.initializer();
        Plan plan = OptionalCallShapeBinder.bind(FOO,
                OptionalCallShapeBinder.parsedArgumentShapes(call.arguments()));
        assertEquals(List.of(1, -1, 0), plan.sourceIndexByParameter());
        assertEquals(List.of(0, 2), plan.callItemIndexByEvaluatedSource());
        assertEquals(List.of(1), plan.omittedCallItemIndices());
        assertEquals(2, plan.suppliedArgumentCount());
    }

    @Test
    void syntheticCallItemAstCannotBypassBindingAndSpreadGates() {
        Ast.CallArgument value = Ast.CallArgument.value(new Ast.LiteralExpr(3L));
        Ast.CallArgument omission = Ast.CallArgument.omit(null);
        assertEquals(Argument.positionalOmission(),
                OptionalCallShapeBinder.parsedArgumentShapes(List.of(omission)).getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CallArgument.value(null));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CallArgument(null, new Ast.LiteralExpr(3L), true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                        List.of(), false, List.of(value)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                        List.of(), false, List.of(
                                Ast.CallArgument.named("a", new Ast.LiteralExpr(1L)), value)));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.parsedArgumentShapes(List.of(
                        Ast.CallArgument.spread(new Ast.NameExpr("values")))));
    }

    @Test
    void fullPositionalInvocationHasIdentityPermutation() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(0, 1, 2), plan.sourceIndexByParameter());
        assertEquals(3, plan.declaredArity());
    }

    @Test
    void optionalInteriorSlotCanBeOmittedByName() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("a"), Argument.named("c")));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
        assertTrue(plan.omitted(1));
        assertFalse(plan.omitted(0));
        assertEquals(1, plan.sourceIndex(2));
    }

    @Test
    void prefixPositionalThenNamedIsLegal() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.named("c")));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
    }

    @Test
    void namedArgumentsReorderOnlyBindingsNotSourceExpressions() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.named("a"), Argument.named("b")));
        assertEquals(List.of(1, 2, 0), plan.sourceIndexByParameter());
        // Expression 0 must still execute first, regardless of destination slot.
    }

    @Test
    void trailingOptionalParametersCanBeOmittedPositionally() {
        Plan plan = OptionalCallShapeBinder.bind(List.of(
                Parameter.required("a"), Parameter.optional("b"), Parameter.optional("c")),
                List.of(Argument.positional()));
        assertEquals(List.of(0, -1, -1), plan.sourceIndexByParameter());
    }

    @Test
    void optionalMiddleDoesNotShiftPositionalSlots() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positional())));
        assertTrue(error.getMessage().contains("missing required argument 'c'"));
    }

    @Test
    void rejectsUnknownAndCaseMismatchedLabels() {
        for (String label : List.of("unknown", "A")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.bind(FOO, List.of(
                            Argument.named("a"), Argument.named(label), Argument.named("c"))));
            assertTrue(error.getMessage().contains("unknown named argument"), label);
        }
    }

    @Test
    void rejectsDuplicateNamedAndPositionalNamedCollisions() {
        for (List<Argument> call : List.of(
                List.of(Argument.named("a"), Argument.named("a"), Argument.named("c")),
                List.of(Argument.positional(), Argument.named("a"), Argument.named("c")))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.bind(FOO, call));
            assertTrue(error.getMessage().contains("more than once"));
        }
    }

    @Test
    void rejectsPositionalAfterNamedAndMissingRequiredArguments() {
        IllegalArgumentException positional = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.named("a"), Argument.positional(), Argument.named("c"))));
        assertTrue(positional.getMessage().contains("positional argument after named"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(Argument.named("a"))));
        assertTrue(missing.getMessage().contains("missing required argument 'c'"));
    }

    @Test
    void rejectsTooManyArgumentsAndDuplicateFormalNames() {
        IllegalArgumentException excess = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positional(),
                        Argument.positional(), Argument.positional())));
        assertTrue(excess.getMessage().contains("too many positional"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.required("x"), Parameter.optional("x")), List.of()));
        assertTrue(duplicate.getMessage().contains("duplicate parameter name"));
    }

    @Test
    void equalArityDeclarationsCannotBeDisambiguatedByLabels() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("left")),
                        List.of(Parameter.required("right"))),
                        List.of(Argument.named("left"))));
        assertTrue(error.getMessage().contains("same-arity overloads"));
    }

    @Test
    void optionalArgumentsCannotOverrideOrDisambiguateByParameterType() {
        // foo(int a) and foo(int a, bool? b) are structurally ambiguous for foo(3).
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("a"), Parameter.optional("b"))),
                        List.of(Argument.positional())));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void fullDeclaredArityIsPreservedAcrossUniqueOverloads() {
        OptionalCallShapeBinder.Selection selected = OptionalCallShapeBinder.bindUnique(
                List.of(
                        List.of(Parameter.required("a")),
                        FOO),
                List.of(Argument.named("a"), Argument.named("c")));
        assertEquals(1, selected.signatureIndex());
        assertEquals(3, selected.plan().declaredArity());
        assertEquals(List.of(0, -1, 1), selected.plan().sourceIndexByParameter());
    }

    @Test
    void overloadedCallWithNoMatchingShapeFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("x")),
                        FOO),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("no declared callable"));
    }

    @Test
    void distinctOptionalNamesStillMakeUnlabeledOmissionAmbiguous() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a"), Parameter.optional("b")),
                        List.of(Parameter.required("a"), Parameter.optional("c"))),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void conservativeArityOverlapCheckRejectsEvenDisjointNames() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(
                                List.of(Parameter.required("left"), Parameter.optional("x")),
                                List.of(Parameter.required("right")))));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void whollyNonOptionalDifferentArityOverloadsStayAvailable() {
        assertDoesNotThrow(() -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("a"), Parameter.required("b")))));
    }

    @Test
    void malformedOverloadCannotBeQuietlySkippedByCandidateFiltering() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("x"), Parameter.optional("x"))),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("duplicate parameter name"));
    }

    @Test
    void forgedCallPlansCannotDuplicateOrSkipSourceEvaluations() {
        for (List<Integer> invalid : List.of(
                List.of(0, 0), List.of(1, -1), List.of(-2), List.of(0, 3))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Plan(invalid), invalid.toString());
        }
        assertEquals(List.of(1, -1, 0),
                new Plan(List.of(1, -1, 0)).sourceIndexByParameter());
    }

    @Test
    void emptyCallAndConstructorReceiverErasureAreSimpleArityCases() {
        assertEquals(List.of(),
                OptionalCallShapeBinder.bind(List.of(), List.of()).sourceIndexByParameter());
        // The compiler omits the construction-scoped self binder before
        // supplying constructor user-value parameters to this utility.
        assertEquals(List.of(-1, 0),
                OptionalCallShapeBinder.bind(List.of(
                        Parameter.optional("caption"), Parameter.required("id")),
                        List.of(Argument.named("id"))).sourceIndexByParameter());
    }

    @Test
    void explicitOmitConsumesFixedSlotButDoesNotEvaluateAnArgumentExpression() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positionalOmission(), Argument.positional()));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
        assertEquals(List.of(1), plan.explicitlyOmittedParameterIndices());
        assertTrue(plan.explicitlyOmitted(1));
        assertTrue(plan.omitted(1));
        assertEquals(2, plan.suppliedArgumentCount());

        Plan named = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.namedOmission("b"), Argument.named("a")));
        assertEquals(List.of(1, -1, 0), named.sourceIndexByParameter());
        assertTrue(named.explicitlyOmitted(1));
        assertEquals(2, named.suppliedArgumentCount());
    }

    @Test
    void omittedOptionalBeforeVariadicTailDoesNotCreateEvaluationHole() {
        Plan plan = OptionalCallShapeBinder.bind(List.of(
                Parameter.optional("prefix"), Parameter.variadic("rest")), List.of(
                        Argument.positionalOmission(),
                        Argument.positional(),
                        Argument.positional()));
        assertTrue(plan.omitted(0));
        assertTrue(plan.explicitlyOmitted(0));
        assertEquals(List.of(0, 1), plan.variadicSourceIndices());
        assertEquals(2, plan.suppliedArgumentCount());
        assertFalse(plan.omitted(1));
    }

    @Test
    void onlyBareUnderscoreOrOmitAreRecognizedAsOmissionTokens() {
        for (String token : List.of("_", "omit")) {
            assertEquals(Argument.positionalOmission(),
                    Argument.omittedCallItemToken(token, null));
            assertEquals(Argument.namedOmission("b"),
                    Argument.omittedCallItemToken(token, "b"));
        }
        for (String invalid : List.of("None", "void", "null", "__", "_x",
                "x_", "(_)", "_ + 1", "..._", "", " omit ", "_()")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Argument.omittedCallItemToken(invalid, null), invalid);
        }
    }

    @Test
    void tokenLevelUnderscoreFollowsSameConstructorAndCallRules() {
        List<Parameter> constructorFormals = List.of(
                Parameter.required("id"), Parameter.optional("label"),
                Parameter.required("active"));
        Plan ctor = OptionalCallShapeBinder.bind(constructorFormals, List.of(
                Argument.positional(),
                Argument.omittedCallItemToken("_", null),
                Argument.positional()));
        Plan routine = OptionalCallShapeBinder.bind(constructorFormals, List.of(
                Argument.positional(),
                Argument.omittedCallItemToken("omit", null),
                Argument.positional()));
        assertEquals(routine, ctor);
        assertEquals(List.of(0, 2), ctor.callItemIndexByEvaluatedSource());

        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(constructorFormals, List.of(
                        Argument.omittedCallItemToken("_", "id"),
                        Argument.named("active"))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(constructorFormals, List.of(
                        Argument.positional(), Argument.omittedCallItemToken("_", "label"),
                        Argument.named("label"), Argument.named("active"))));
    }

    @Test
    void omittedCallItemIsSkippedWithoutLosingOriginalExpressionIdentity() {
        Plan positional = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.underscore(), Argument.positional()));
        assertEquals(List.of(0, -1, 1), positional.sourceIndexByParameter());
        assertEquals(List.of(0, 2), positional.callItemIndexByEvaluatedSource());
        assertEquals(2, positional.callItemIndex(positional.sourceIndex(2)));

        Plan named = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.namedUnderscore("b"), Argument.named("a")));
        assertEquals(List.of(1, -1, 0), named.sourceIndexByParameter());
        assertEquals(List.of(0, 2), named.callItemIndexByEvaluatedSource());
        assertEquals(2, named.callItemIndex(named.sourceIndex(0)));
        assertEquals(0, named.callItemIndex(named.sourceIndex(2)));

        Plan variadic = OptionalCallShapeBinder.bind(List.of(
                Parameter.optional("prefix"), Parameter.variadic("tail")), List.of(
                Argument.underscore(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(0, 1), variadic.variadicSourceIndices());
        assertEquals(List.of(1, 2), variadic.callItemIndexByEvaluatedSource());
    }

    @Test
    void forgedCallItemMappingsCannotDropDuplicateOrReorderEffects() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(2, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(),
                        List.of(1), List.of(-1, 2)));
    }

    @Test
    void underscoreIsExactlyTheSameOmissionPlanAsOmit() {
        assertEquals(Argument.positionalOmission(), Argument.underscore());
        assertEquals(Argument.namedOmission("b"), Argument.namedUnderscore("b"));

        Plan spelledOmit = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positionalOmission(), Argument.positional()));
        Plan spelledUnderscore = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.underscore(), Argument.positional()));
        assertEquals(spelledOmit, spelledUnderscore);
        assertEquals(List.of(0, -1, 1), spelledUnderscore.sourceIndexByParameter());
        assertEquals(List.of(1), spelledUnderscore.explicitlyOmittedParameterIndices());
        assertEquals(2, spelledUnderscore.suppliedArgumentCount());

        Plan named = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("a"), Argument.namedUnderscore("b"), Argument.named("c")));
        assertEquals(List.of(0, -1, 1), named.sourceIndexByParameter());
        assertTrue(named.explicitlyOmitted(1));
    }

    @Test
    void underscoreIsNotAValueAndCannotSkipRequiredOrRestSlots() {
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.underscore(), Argument.positional(), Argument.positional())));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(
                        List.of(Parameter.required("head"), Parameter.variadic("tail")),
                        List.of(Argument.positional(), Argument.underscore())));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.underscore(),
                        Argument.named("b"), Argument.named("c"))));
    }

    @Test
    void omissionAndExplicitOptionNoneHaveDistinctCompileTimePlans() {
        Plan explicitOmit = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positionalOmission(), Argument.positional()));
        Plan explicitNone = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertTrue(explicitOmit.explicitlyOmitted(1));
        assertFalse(explicitNone.explicitlyOmitted(1));
        assertEquals(2, explicitOmit.suppliedArgumentCount());
        assertEquals(3, explicitNone.suppliedArgumentCount());
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.PASS_OPTION,
                OptionalCallShapeBinder.optionalAdaptation(Parameter.optional("b"), false,
                        OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL));
    }

    @Test
    void omissionInRequiredOrRestOrDuplicateParameterFailsClosed() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positionalOmission(), Argument.positional(), Argument.positional())))
                .getMessage().contains("required parameter"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(Parameter.variadic("items")),
                        List.of(Argument.positionalOmission())))
                .getMessage().contains("rest tail"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positionalOmission(),
                        Argument.named("b"), Argument.named("c"))))
                .getMessage().contains("more than once"));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(), List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(), List.of(1, 1)));
    }

    @Test
    void variadicTailCollectsAllRemainingPositionalExpressions() {
        List<Parameter> signature = List.of(
                Parameter.required("head"), Parameter.variadic("items"));
        Plan plan = OptionalCallShapeBinder.bind(signature, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(0, -1), plan.sourceIndexByParameter());
        assertEquals(1, plan.variadicParameterIndex());
        assertEquals(List.of(1, 2), plan.variadicSourceIndices());
        assertEquals(3, plan.suppliedArgumentCount());
        assertEquals(2, plan.declaredArity());
        assertFalse(plan.omitted(1), "zero or more rest values is never Option.None");
        assertThrows(IllegalArgumentException.class, () -> plan.sourceIndex(1));
    }

    @Test
    void emptyVariadicTailIsEmptyCollectionRatherThanOmittedOptional() {
        Plan plan = OptionalCallShapeBinder.bind(List.of(
                Parameter.required("id"), Parameter.variadic("items")),
                List.of(Argument.named("id")));
        assertEquals(List.of(), plan.variadicSourceIndices());
        assertFalse(plan.omitted(1));
        assertTrue(OptionalCallShapeBinder.bind(
                List.of(Parameter.variadic("items")), List.of()).variadicSourceIndices().isEmpty());
    }

    @Test
    void optionalBeforeVariadicMayBeOmittedOnlyByNamedCall() {
        List<Parameter> signature = List.of(
                Parameter.optional("prefix"), Parameter.variadic("tail"));
        Plan named = OptionalCallShapeBinder.bind(signature, List.of());
        assertTrue(named.omitted(0));
        assertFalse(named.omitted(1));
        assertEquals(List.of(), named.variadicSourceIndices());
        Plan positional = OptionalCallShapeBinder.bind(signature, List.of(
                Argument.positional(), Argument.positional()));
        assertFalse(positional.omitted(0));
        assertEquals(List.of(1), positional.variadicSourceIndices());
    }

    @Test
    void finalVariadicRequirementAndNamedRestWithoutCollectionProofFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Parameter("rest", true, true));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.variadic("items"), Parameter.required("later")), List.of()))
                .getMessage().contains("must be final"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.required("a"), Parameter.variadic("items")),
                        List.of(Argument.named("a"), Argument.named("items"))))
                .getMessage().contains("named rest"));
    }

    @Test
    void variadicPlansCannotForgeDuplicateOrSkippedEvaluations() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 1, List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 1, List.of(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 0, List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, 1), 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), -1, List.of(1)));
    }

    @Test
    void variadicOverloadsCollideWithAnyIntersectingFixedOrOptionalArity() {
        List<Parameter> rest = List.of(
                Parameter.required("a"), Parameter.variadic("tail"));
        for (List<Parameter> other : List.of(
                List.of(Parameter.required("a")),
                List.of(Parameter.required("a"), Parameter.required("b")),
                List.of(Parameter.required("a"), Parameter.optional("b")))) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                            List.of(rest, other))).getMessage().contains("overlapping"));
        }
        assertDoesNotThrow(() ->
                OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(rest, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(rest, List.of(Parameter.variadic("other")))));
    }

    @Test
    void absenceNeverMeansNullOrVoidAndMustUseTypedOption() {
        Parameter optional = Parameter.optional("b");
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.INJECT_NONE,
                OptionalCallShapeBinder.optionalAdaptation(optional, true, null));
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.WRAP_SOME,
                OptionalCallShapeBinder.optionalAdaptation(optional, false,
                        OptionalCallShapeBinder.SuppliedForm.PLAIN_VALUE));
        for (OptionalCallShapeBinder.SuppliedForm form : List.of(
                OptionalCallShapeBinder.SuppliedForm.OPTION_VALUE,
                OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL)) {
            assertEquals(OptionalCallShapeBinder.OptionalAdaptation.PASS_OPTION,
                    OptionalCallShapeBinder.optionalAdaptation(optional, false, form));
        }
        for (OptionalCallShapeBinder.SuppliedForm form : List.of(
                OptionalCallShapeBinder.SuppliedForm.NULL_LITERAL,
                OptionalCallShapeBinder.SuppliedForm.VOID_EXPRESSION)) {
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.optionalAdaptation(optional, false, form));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.optionalAdaptation(optional, true,
                        OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.optionalAdaptation(Parameter.variadic("items"), true, null));
    }
}
