package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallEvaluationSchedule;
import dev.oreslang.types.OptionalCallLiteralStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executable checks of a non-executable literal-only compiler proof. */
final class OptionalCallLiteralStageTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");

    private static List<Ast.Param> signature() {
        return List.of(new Ast.Param(INT, "a"),
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"),
                new Ast.Param(BOOL, "enabled"));
    }

    @Test
    void namedReorderedScalarsAreDerivedWithoutCallerTypes() {
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true)),
                        Ast.CallArgument.omit("label"),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(42L))));
        var proof = OptionalCallLiteralStage.prepareDirectLiteralCall(call, signature());
        assertEquals(List.of(BOOL, INT), proof.independentlyDerivedTypes());
        assertEquals(List.of(2, 1, 0), proof.invocation().arguments().abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertEquals(List.of(1, -1, 0), proof.invocation().arguments().abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                proof.invocation().arguments().abiSlots().get(1).action());
        assertEquals(2, proof.evaluation().sourceBindings().size());
        assertEquals(0, proof.evaluation().sourceBindings().get(0).evaluatedIndex());
        assertEquals(2, proof.evaluation().sourceBindings().get(0).formalIndex());
        assertThrows(UnsupportedOperationException.class,
                () -> proof.independentlyDerivedTypes().clear());
    }

    @Test
    void suppliedValueBecomesTypedSomeButOmissionIsTypedNone() {
        List<Ast.Param> optional = List.of(
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"));
        var supplied = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("label", new Ast.LiteralExpr("hi"))));
        var some = OptionalCallLiteralStage.prepareDirectLiteralCall(supplied, optional);
        assertEquals(OptionalCallAbiPreview.Action.WRAP_SOME,
                some.invocation().arguments().abiSlots().getFirst().action());
        assertEquals(new Ast.TypeRef("Option", List.of(STRING), false),
                some.invocation().arguments().abiSlots().getFirst().calleeType());

        var omitted = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.omit("label")));
        var none = OptionalCallLiteralStage.prepareDirectLiteralCall(omitted, optional);
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                none.invocation().arguments().abiSlots().getFirst().action());
        assertTrue(none.evaluation().sourceBindings().isEmpty());
        assertEquals(List.of(
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE, -1),
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 0),
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.INVOKE, -1)),
                none.evaluation().steps());
    }

    @Test
    void wrongScalarTypeAndRequiredOptionRemainRejected() {
        var wrong = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr("wrong")),
                        Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(wrong, signature()));
        var requiredOption = List.of(new Ast.Param(
                new Ast.TypeRef("Option", List.of(STRING), false), "value"));
        var rawString = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("value", new Ast.LiteralExpr("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(
                        rawString, requiredOption));
    }

    @Test
    void nonliteralNoneUnknownAndSideEffectCallsCannotForgeLiteralProof() {
        var optional = List.of(new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "x"));
        for (Ast.Expr unproved : List.of(new Ast.NameExpr("None"),
                new Ast.NameExpr("userValue"),
                new Ast.CallExpr(new Ast.NameExpr("sideEffect"), List.of()))) {
            var call = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                    List.of(Ast.CallArgument.named("x", unproved)));
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallLiteralStage.prepareDirectLiteralCall(call, optional));
        }
        var nil = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr(null))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(nil, optional));
    }

    @Test
    void dynamicOrMemberCalleeAndGenericApplicationCannotEnterLiteralPath() {
        var optional = List.of(new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "x"));
        var args = List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr("hello")));
        var method = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("receiver"), "f"),
                List.of(), false, args);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(method, optional));
        var generic = new Ast.StagedCallExpr(
                new Ast.NameExpr("f"), List.of(STRING), true, args);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(generic, optional));
    }

    @Test
    void localCandidateUsesParsedDeclarationRatherThanCallerSuppliedFormals() {
        Ast.Program program = Parser.parse("""
                pub fnc f(int a, string? label, bool enabled): int {
                    return a;
                }
                """);
        Ast.FunctionDecl parsed = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.NameExpr("f"), List.of(), false, List.of(
                    Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true)),
                    Ast.CallArgument.omit("label"),
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(12L))));
        var candidate = OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                program, call);
        assertSame(parsed, candidate.declaration());
        assertEquals(List.of(BOOL, INT), candidate.preview().independentlyDerivedTypes());
        assertEquals(List.of(2, 1, 0),
                candidate.preview().invocation().arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                candidate.preview().invocation().arguments().abiSlots().get(1).action());
    }

    @Test
    void localCandidateRejectsAmbiguousShadowedImportedAndWrongValueDeclarations() {
        Ast.Program program = Parser.parse("""
                pub fnc f(int a, string? label, bool enabled): int {
                    return a;
                }
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl fn = (Ast.FunctionDecl) module.declarations().getFirst();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.NameExpr("f"), List.of(), false, List.of(
                    Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true)),
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(12L))));
        var duplicated = new Ast.Program(List.of(
                new Ast.ModuleDecl(module.name(), List.of(fn, fn))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        duplicated, call));

        var sameNameValue = new Ast.FieldDecl("f", Ast.Visibility.PRIVATE,
                Ast.BindingKind.VAL, INT, new Ast.LiteralExpr(3L));
        var shadowed = new Ast.Program(List.of(
                new Ast.ModuleDecl(module.name(), List.of(sameNameValue, fn))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        shadowed, call));

        for (Ast.Decl conflicting : List.<Ast.Decl>of(
                new Ast.ClassDecl("f", false, List.of(), List.of(), List.of()),
                new Ast.InterfaceDecl("f", List.of(), List.of()),
                new Ast.TypeAliasDecl("f", List.of(), INT))) {
            var nameCollision = new Ast.Program(List.of(
                    new Ast.ModuleDecl(module.name(), List.of(fn, conflicting))));
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                            nameCollision, call));
        }

        var extraModule = new Ast.Program(List.of(module, module));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        extraModule, call));

        var imported = new Ast.Program(
                List.of(new Ast.ImportDecl(Ast.ImportKind.ALL,
                        List.of(), true, null, "external")),
                List.of(module));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        imported, call));

        var decorated = new Ast.Program(List.of(new Ast.ModuleDecl(
                module.name(), List.of(new Ast.Annotation("Audit", List.of())),
                List.of(fn))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        decorated, call));

        var asyncFn = new Ast.FunctionDecl(fn.name(), fn.kind(), fn.visibility(),
                true, Ast.ActorKind.NONE, List.of(), fn.parameters(),
                fn.returnType(), List.of(), fn.body());
        var asyncProgram = new Ast.Program(List.of(new Ast.ModuleDecl(
                module.name(), List.of(asyncFn))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        asyncProgram, call));

        var wrongFunction = new Ast.StagedCallExpr(new Ast.NameExpr("other"),
                List.of(), false, call.arguments());
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        program, wrongFunction));

        var wrongLabel = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("doesNotExist", new Ast.LiteralExpr(12L))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        program, wrongLabel));
    }

    @Test
    void localCandidateCannotProveNonliteralEffectsOrTypeSubstitution() {
        Ast.Program program = Parser.parse("""
                pub fnc f(int a, string? label, bool enabled): int {
                    return a;
                }
                """);
        var sideEffect = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("a",
                            new Ast.CallExpr(new Ast.NameExpr("effect"), List.of())),
                    Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        program, sideEffect));
        var typeApplied = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(INT), true, List.of(
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(1L)),
                    Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                        program, typeApplied));
    }

    @Test
    void localLiteralCandidatesRefuseUnprovedNominalMutableAndBorrowedSlots() {
        Ast.Program program = Parser.parse("""
                pub fnc f(int a, string? label, bool enabled): int {
                    return a;
                }
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl fn = (Ast.FunctionDecl) module.declarations().getFirst();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(5L)),
                    Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true))));

        for (Ast.Param replacement : List.of(
                new Ast.Param(INT, "a", true, false),
                new Ast.Param(INT, "a", false, true),
                new Ast.Param(Ast.TypeRef.simple("UnresolvedNominal"), "a"),
                new Ast.Param(Ast.TypeRef.borrowed(INT, false), "a"),
                new Ast.Param(new Ast.TypeRef("Option", List.of(INT), false), "a"))) {
            var modifiedFormals = new java.util.ArrayList<>(fn.parameters());
            modifiedFormals.set(0, replacement);
            var modified = new Ast.FunctionDecl(fn.name(), fn.kind(), fn.visibility(),
                    false, Ast.ActorKind.NONE, List.of(), List.copyOf(modifiedFormals),
                    fn.returnType(), List.of(), fn.body());
            var candidateProgram = new Ast.Program(List.of(
                    new Ast.ModuleDecl(module.name(), List.of(modified))));
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(
                            candidateProgram, call));
        }
    }

    @Test
    void scalarCallPlansRejectSyntheticNonFiniteFloatsWithoutRejectingFiniteValues() {
        var formals = List.of(new Ast.Param(Ast.TypeRef.simple("float"), "weight"));
        for (double special : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            var call = new Ast.StagedCallExpr(
                    new Ast.NameExpr("weightOnly"), List.of(), false,
                    List.of(Ast.CallArgument.named(
                            "weight", new Ast.LiteralExpr(special))));
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallLiteralStage.prepareDirectLiteralCall(
                            call, formals));
        }
        for (double finite : new double[] { 0.0, -0.0, 2.5, -100.75 }) {
            var call = new Ast.StagedCallExpr(
                    new Ast.NameExpr("weightOnly"), List.of(), false,
                    List.of(Ast.CallArgument.named(
                            "weight", new Ast.LiteralExpr(finite))));
            var candidate = OptionalCallLiteralStage.prepareDirectLiteralCall(
                    call, formals);
            assertEquals(List.of(Ast.TypeRef.simple("float")),
                    candidate.independentlyDerivedTypes());
        }
    }

    @Test
    void directScalarsRejectIncorrectClaimedTypeInGeneralAbiPreview() {
        var params = List.of(new Ast.Param(INT, "x"));
        var call = List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr(7L)));
        var plan = dev.oreslang.types.OptionalCallShapeBinder.bindParsed(
                dev.oreslang.types.OptionalCallShapeBinder.declaredParameterShapes(params), call);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        params, call, plan, List.of(STRING)));
    }
}
