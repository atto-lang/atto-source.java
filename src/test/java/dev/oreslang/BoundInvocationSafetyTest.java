package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These tests assert rejection by the non-executable invocation preview.
 * They do not claim interpreter, GraalVM, or LLVM runtime implementation.
 */
final class BoundInvocationSafetyTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.ClassDecl concreteBox() {
        return (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int value) { return; }
                        pub fnc echo(int v): int { return v; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
    }

    private static Ast.ClassDecl modifiedClass(
            Ast.ClassDecl base, boolean abstractClass, Ast.ConstructorDecl constructor) {
        return new Ast.ClassDecl(base.name(), base.visibility(), abstractClass,
                base.actorKind(), base.genericParameters(), base.parents(),
                base.interfaces(), base.fields(), constructor, base.methods());
    }

    private static Ast.StagedNewExpr construction() {
        return new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), List.of(
                Ast.CallArgument.named("value", new Ast.LiteralExpr(3L))), false);
    }

    @Test
    void abstractOwnerCannotProduceDirectConstructorOrMethodPreview() {
        Ast.ClassDecl concrete = concreteBox();
        Ast.ClassDecl abstractBox = modifiedClass(concrete, true, concrete.constructor());

        IllegalArgumentException ctor = assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassConstructor(
                        abstractBox, construction(), List.of(INT)));
        assertTrue(ctor.getMessage().contains("abstract"), ctor::toString);

        Ast.StagedCallExpr method = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "echo"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("v", new Ast.LiteralExpr(3L))));
        IllegalArgumentException methodFailure = assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassMethod(abstractBox, method, List.of(INT)));
        assertTrue(methodFailure.getMessage().contains("abstract"), methodFailure::toString);

        // The rejection must not globally disable ordinary concrete-owner plans.
        assertDoesNotThrow(() -> BoundInvocation.bindClassConstructor(
                concrete, construction(), List.of(INT)));
    }

    @Test
    void fabricatedInstanceMemberWithoutReceiverCannotProduceBoundPlan() {
        Ast.ClassDecl owner = concreteBox();
        Ast.StagedCallExpr nullReceiver = new Ast.StagedCallExpr(
                new Ast.MemberExpr(null, "echo"), List.of(), false,
                List.of(Ast.CallArgument.named("v", new Ast.LiteralExpr(9L))));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindLiteralClassMethod(owner, nullReceiver));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassMethod(
                        owner, nullReceiver, List.of(INT)));

        // The malformed-AST rejection must not disable the ordinary method
        // shape preview. This is still SHAPE_ONLY, never an executable token.
        Ast.StagedCallExpr valid = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "echo"),
                List.of(), false,
                List.of(Ast.CallArgument.named("v", new Ast.LiteralExpr(9L))));
        BoundInvocation preview = BoundInvocation.bindLiteralClassMethod(owner, valid);
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, preview.readiness());
        assertThrows(IllegalStateException.class, preview::requireExecutable);
    }

    @Test
    void syntheticConstructorSelfCannotBeMistakenForGuestAbiArgument() {
        Ast.ClassDecl concrete = concreteBox();
        Ast.ConstructorDecl original = concrete.constructor();

        for (boolean leading : List.of(true, false)) {
            Ast.Param self = new Ast.Param(Ast.TypeRef.simple("Box"), "self");
            List<Ast.Param> formals = leading
                    ? List.of(self, original.parameters().getFirst())
                    : List.of(original.parameters().getFirst(), self);
            Ast.ConstructorDecl forged = new Ast.ConstructorDecl(
                    original.visibility(), formals, original.annotations(), original.body());
            Ast.ClassDecl withSelf = modifiedClass(concrete, false, forged);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindClassConstructor(
                            withSelf, construction(), List.of(INT)));
            assertTrue(failure.getMessage().contains("receiver erasure"), failure::toString);
        }
    }

    @Test
    void annotatedOrContractedModuleRequiresExpandedLexicalProof() {
        Ast.ModuleDecl plain = Parser.parse("""
                define module app as
                    pub fnc work(int a): int { return a; }
                end
                """).modules().getFirst();
        Ast.StagedCallExpr direct = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr(4L))));
        Ast.StagedCallExpr qualified = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("app"), "work"),
                List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr(4L))));

        for (Ast.ModuleDecl unexpanded : List.of(
                new Ast.ModuleDecl(plain.name(),
                        List.of(new Ast.Annotation("audit", List.of())),
                        List.of(), plain.declarations()),
                new Ast.ModuleDecl(plain.name(), List.of(),
                        List.of(Ast.TypeRef.simple("Contract")),
                        plain.declarations()))) {
            for (Ast.StagedCallExpr call : List.of(direct, qualified)) {
                assertThrows(IllegalArgumentException.class,
                        () -> BoundInvocation.bindLiteralModuleFunction(
                                unexpanded, call));
                assertThrows(IllegalArgumentException.class,
                        () -> BoundInvocation.bindModuleFunction(
                                unexpanded, call, List.of(INT)));
            }
        }

        for (Ast.StagedCallExpr call : List.of(direct, qualified)) {
            BoundInvocation preview =
                    BoundInvocation.bindLiteralModuleFunction(plain, call);
            assertEquals(BoundInvocation.Readiness.SHAPE_ONLY,
                    preview.readiness());
            assertThrows(IllegalStateException.class, preview::requireExecutable);
        }
    }

    @Test
    void forgedModuleNoncallableNameCollisionRequiresRealLexicalResolution() {
        Ast.ModuleDecl original = Parser.parse("""
                define module app as
                    pub fnc work(int a): int { return a; }
                end
                """).modules().getFirst();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr(3L))));

        assertDoesNotThrow(() -> BoundInvocation.bindLiteralModuleFunction(original, call));

        for (Ast.Decl collision : List.<Ast.Decl>of(
                new Ast.FieldDecl("work", Ast.Visibility.PRIVATE,
                        Ast.BindingKind.CONST, INT, new Ast.LiteralExpr(7L)),
                new Ast.TypeAliasDecl("work", List.of(), INT),
                new Ast.ClassDecl("work", false, List.of(), List.of(), List.of()))) {
            Ast.ModuleDecl forged = new Ast.ModuleDecl(
                    original.name(), List.of(original.declarations().getFirst(), collision));
            assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindLiteralModuleFunction(forged, call));
        }

        Ast.ModuleDecl unrelated = new Ast.ModuleDecl(
                original.name(), List.of(original.declarations().getFirst(),
                new Ast.FieldDecl("other", Ast.Visibility.PRIVATE,
                        Ast.BindingKind.CONST, INT, new Ast.LiteralExpr(7L))));
        assertDoesNotThrow(() -> BoundInvocation.bindLiteralModuleFunction(unrelated, call));
    }

    @Test
    void qualifiedModuleNameMustNotBeShadowedByAnotherCallableOrValue() {
        Ast.ModuleDecl original = Parser.parse("""
                define module app as
                    pub fnc work(int a): int { return a; }
                end
                """).modules().getFirst();
        Ast.StagedCallExpr qualified = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("app"), "work"),
                List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr(3L))));
        assertDoesNotThrow(() -> BoundInvocation.bindLiteralModuleFunction(
                original, qualified));

        Ast.ModuleDecl other = Parser.parse("""
                define module unrelated as
                    pub fnc app(): int { return 2; }
                end
                """).modules().getFirst();
        Ast.FunctionDecl collidingCallable = (Ast.FunctionDecl)
                other.declarations().getFirst();
        for (Ast.Decl collision : List.<Ast.Decl>of(
                collidingCallable,
                new Ast.FieldDecl("app", Ast.Visibility.PRIVATE,
                        Ast.BindingKind.CONST, INT, new Ast.LiteralExpr(2L)),
                new Ast.TypeAliasDecl("app", List.of(), INT))) {
            Ast.ModuleDecl forged = new Ast.ModuleDecl(
                    "app", List.of(original.declarations().getFirst(), collision));
            assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindLiteralModuleFunction(forged, qualified));
        }

        // The module's own name does not conflict merely because a distinct
        // function or field appears in the same declaration set.
        Ast.ModuleDecl disjoint = new Ast.ModuleDecl(
                "app", List.of(original.declarations().getFirst(),
                new Ast.FieldDecl("other", Ast.Visibility.PRIVATE,
                        Ast.BindingKind.CONST, INT, new Ast.LiteralExpr(2L))));
        assertDoesNotThrow(() -> BoundInvocation.bindLiteralModuleFunction(
                disjoint, qualified));
    }

    @Test
    void forgedClassFieldAndMethodCollisionRequiresMemberResolution() {
        Ast.ClassDecl original = concreteBox();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "echo"),
                List.of(), false,
                List.of(Ast.CallArgument.named("v", new Ast.LiteralExpr(9L))));
        assertDoesNotThrow(() -> BoundInvocation.bindLiteralClassMethod(original, call));

        Ast.FieldDecl collidingField = new Ast.FieldDecl(
                "echo", Ast.Visibility.PRIVATE, Ast.BindingKind.CONST,
                INT, new Ast.LiteralExpr(0L));
        Ast.ClassDecl forged = new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), List.of(collidingField),
                original.constructor(), original.methods());
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindLiteralClassMethod(forged, call));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassMethod(forged, call, List.of(INT)));

        Ast.FieldDecl unrelatedField = new Ast.FieldDecl(
                "counter", Ast.Visibility.PRIVATE, Ast.BindingKind.CONST,
                INT, new Ast.LiteralExpr(0L));
        Ast.ClassDecl safe = new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), List.of(unrelatedField),
                original.constructor(), original.methods());
        var witness = BoundInvocation.bindLiteralClassMethod(safe, call);
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, witness.readiness());
        assertThrows(IllegalStateException.class, witness::requireExecutable);
    }

    @Test
    void selectedFormalsAndCallItemOriginsSurviveReorderedMultipleOmissions() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc work(int a, string? b, bool? c, int d): int { return a; }
                end
                """).modules().getFirst();
        Ast.Expr effectD = new Ast.CallExpr(new Ast.NameExpr("effectD"), List.of());
        Ast.Expr effectA = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false, List.of(
                        Ast.CallArgument.named("d", effectD),
                        Ast.CallArgument.omit("c"),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", effectA)));
        BoundInvocation bound = BoundInvocation.bindModuleFunction(
                module, call, List.of(INT, INT));

        assertSame(module.declarations().getFirst(), bound.selectedDeclaration());
        assertEquals(List.of(call.callee(), effectD, effectA),
                bound.schedule().evaluationOrder());
        assertEquals(List.of("a", "b", "c", "d"),
                bound.schedule().abiSlots().stream()
                        .map(slot -> slot.formalName()).toList());
        assertEquals(List.of(1, -1, -1, 0),
                bound.schedule().abiSlots().stream()
                        .map(slot -> slot.evaluatedSourceIndex()).toList());
        assertEquals(List.of(3, 2, 1, 0),
                bound.schedule().abiSlots().stream()
                        .map(slot -> slot.originalCallItemIndex()).toList());
        assertEquals(8, bound.schedule().steps().size());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, bound.readiness());
        assertThrows(IllegalStateException.class, bound::requireExecutable);
    }

    @Test
    void callerAssertedOptionTypeCanProduceOnlyNonExecutablePreview() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc work(Option<int> required): int { return 1; }
                end
                """).modules().getFirst();
        Ast.TypeRef optionInt = new Ast.TypeRef("Option", List.of(INT), false);
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false,
                List.of(Ast.CallArgument.named("required", new Ast.NameExpr("None"))));
        BoundInvocation bound = BoundInvocation.bindModuleFunction(
                module, call, List.of(optionInt));
        assertEquals(1, bound.schedule().abiSlots().size());
        assertEquals(0, bound.schedule().abiSlots().getFirst().originalCallItemIndex());
        assertEquals(dev.oreslang.types.OptionalCallAbiPreview.Action.PASS_REQUIRED,
                bound.schedule().abiSlots().getFirst().action());
        // A caller's exact-looking type claim is not a checked expression,
        // ownership loan or authorization. No executable token exists here.
        assertThrows(IllegalStateException.class, bound::requireExecutable);
        assertThrows(UnsupportedOperationException.class,
                () -> bound.assertedSourceTypes().clear());
    }

    @Test
    void literalOnlyBindingDerivesTypesFromAstWithoutCallerClaims() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc work(int a, string? b, bool c): int { return a; }
                end
                """).modules().getFirst();
        Ast.StagedCallExpr literalCall = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false, List.of(
                        Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(7L))));
        var bound = BoundInvocation.bindLiteralModuleFunction(module, literalCall);
        assertEquals(List.of(Ast.TypeRef.simple("bool"), INT),
                bound.assertedSourceTypes());
        assertEquals(List.of(2, 1, 0),
                bound.schedule().abiSlots().stream()
                        .map(slot -> slot.originalCallItemIndex()).toList());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, bound.readiness());
        assertThrows(IllegalStateException.class, bound::requireExecutable);

        Ast.StagedCallExpr variableCall = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false, List.of(
                        Ast.CallArgument.named("c", new Ast.NameExpr("looksBoolean")),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(7L))));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindLiteralModuleFunction(module, variableCall));
        Ast.StagedCallExpr nullCall = new Ast.StagedCallExpr(
                new Ast.NameExpr("work"), List.of(), false, List.of(
                        Ast.CallArgument.named("c", new Ast.LiteralExpr(null)),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(7L))));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindLiteralModuleFunction(module, nullCall));
    }

    @Test
    void literalOnlyClassAndConstructorPathsRemainNonExecutable() {
        Ast.ClassDecl klass = concreteBox();
        Ast.StagedCallExpr method = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "echo"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("v", new Ast.LiteralExpr(9L))));
        BoundInvocation methodBound = BoundInvocation.bindLiteralClassMethod(klass, method);
        assertEquals(List.of(INT), methodBound.assertedSourceTypes());
        assertThrows(IllegalStateException.class, methodBound::requireExecutable);

        BoundInvocation constructorBound =
                BoundInvocation.bindLiteralClassConstructor(klass, construction());
        assertEquals(List.of(
                dev.oreslang.types.OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT,
                dev.oreslang.types.OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT,
                dev.oreslang.types.OptionalCallEvaluationSchedule.Operation.INVOKE),
                constructorBound.schedule().steps().stream()
                        .map(step -> step.operation()).toList());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, constructorBound.readiness());
        assertThrows(IllegalStateException.class, constructorBound::requireExecutable);
    }
}
