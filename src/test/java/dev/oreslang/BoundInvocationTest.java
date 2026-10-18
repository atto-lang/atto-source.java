package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallEvaluationSchedule;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Shape/provenance tests only. Not proof of executable guest language calls. */
final class BoundInvocationTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");

    private static Ast.StagedCallExpr call(String target) {
        return new Ast.StagedCallExpr(new Ast.NameExpr(target), List.of(), false, List.of(
                Ast.CallArgument.named("c", new Ast.NameExpr("sideC")),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", new Ast.NameExpr("sideA"))));
    }

    private static Ast.ModuleDecl owner() {
        return Parser.parse("""
                define module app as
                    pub fnc work(int a, string? b, bool c): int { return a; }
                end
                """).modules().getFirst();
    }

    @Test
    void selectedFunctionIsOriginalModuleDeclarationAndPreservesSourceOrder() {
        Ast.ModuleDecl module = owner();
        Ast.StagedCallExpr call = call("work");
        var bound = BoundInvocation.bindModuleFunction(module, call, List.of(BOOL, INT));
        assertSame(module, bound.owner());
        assertSame(module.declarations().getFirst(), bound.selectedDeclaration());
        assertSame(call, bound.source());
        assertEquals(0, bound.declarationOrdinal());
        assertEquals(3, bound.preview().arguments().fullArity());
        assertEquals(List.of(2, 1, 0),
                bound.preview().arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertSame(call.callee(), bound.preview().evaluationOrder().getFirst());
        assertSame(call.arguments().getFirst().value(), bound.preview().evaluationOrder().get(1));
        assertSame(call.arguments().get(2).value(), bound.preview().evaluationOrder().get(2));
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, bound.readiness());
        assertThrows(IllegalStateException.class, bound::requireExecutable);
        assertThrows(UnsupportedOperationException.class,
                () -> bound.assertedSourceTypes().clear());
    }

    @Test
    void overloadFamilyHasStableOwnerOrdinalAndNoLabelOnlyDispatch() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc work(int a): int { return a; }
                    pub fnc work(int a, bool c): int { return a; }
                end
                """).modules().getFirst();
        var call = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("app"), "work"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(7L))));
        var bound = BoundInvocation.bindModuleFunction(module, call, List.of(BOOL, INT));
        assertSame(module.declarations().get(1), bound.selectedDeclaration());
        assertEquals(1, bound.declarationOrdinal());
        assertEquals(List.of(1, 0),
                bound.preview().arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
    }

    @Test
    void foreignCalleeForgedTypesAndMissingValuesFailClosed() {
        Ast.ModuleDecl module = owner();
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindModuleFunction(module, call("missing"), List.of(BOOL, INT)));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindModuleFunction(module, call("work"), List.of(INT, BOOL)));
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindModuleFunction(module, call("work"), List.of(BOOL)));
        var foreign = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("other"), "work"),
                List.of(), false, call("work").arguments());
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindModuleFunction(module, foreign, List.of(BOOL, INT)));
        var generic = new Ast.StagedCallExpr(new Ast.NameExpr("work"),
                List.of(INT), true, call("work").arguments());
        assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindModuleFunction(module, generic, List.of(BOOL, INT)));
    }

    @Test
    void computedPlanCannotAdmitUnloweredOptionalParametersToCompiler() {
        Ast.Program program = Parser.parse("""
                define module app as
                    pub fnc work(int a, string? b, bool c): int { return a; }
                    pub fnc main(): void {
                        const result = work(c: true, b: omit, a: 3);
                        return;
                    }
                end
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl main = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.StagedCallExpr staged = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) main.body().getFirst()).initializer();
        assertDoesNotThrow(() -> BoundInvocation.bindModuleFunction(
                module, staged, List.of(BOOL, INT)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
    }

    @Test
    void classMethodRetainsDeclarationIdentityAndEvaluatesReceiverFirst() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "work"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(7L))));
        BoundInvocation bound = BoundInvocation.bindClassMethod(klass, call, List.of(BOOL, INT));
        assertEquals(BoundInvocation.Kind.CLASS_METHOD, bound.kind());
        assertSame(klass, bound.owner());
        assertSame(klass.methods().getFirst(), bound.selectedDeclaration());
        assertEquals(0, bound.declarationOrdinal());
        assertSame(call.callee(), bound.preview().evaluationOrder().getFirst());
        assertEquals(List.of(1, 0),
                bound.preview().arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertThrows(IllegalStateException.class, bound::requireExecutable);
    }

    @Test
    void explicitConstructorUsesFullArityWithoutEvaluatingTargetExpression() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int a, bool c) { return; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        var source = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(5L)));
        var invocation = new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), source, false);
        var bound = BoundInvocation.bindClassConstructor(klass, invocation, List.of(BOOL, INT));
        assertEquals(BoundInvocation.Kind.CLASS_CONSTRUCTOR, bound.kind());
        assertSame(klass.constructor(), bound.selectedDeclaration());
        assertSame(klass, bound.owner());
        assertEquals(OptionalCallAbiPreview.InvocationKind.CLASS_CONSTRUCTOR,
                bound.preview().kind());
        assertEquals(2, bound.preview().evaluationOrder().size());
        assertEquals(List.of(1, 0),
                bound.preview().arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertThrows(IllegalStateException.class, bound::requireExecutable);
    }

    @Test
    void constructorActorForeignAndNonOwnerMethodFailClosed() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int a, bool c) { return; }
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        var args = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(5L)));
        assertThrows(IllegalArgumentException.class, () ->
                BoundInvocation.bindClassConstructor(klass,
                        new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), args, true),
                        List.of(BOOL, INT)));
        assertThrows(IllegalArgumentException.class, () ->
                BoundInvocation.bindClassConstructor(klass,
                        new Ast.StagedNewExpr(Ast.TypeRef.simple("Other"), args, false),
                        List.of(BOOL, INT)));
        assertThrows(IllegalArgumentException.class, () ->
                BoundInvocation.bindClassMethod(klass,
                        new Ast.StagedCallExpr(
                                new Ast.MemberExpr(new Ast.NameExpr("box"), "missing"),
                                List.of(), false, args),
                        List.of(BOOL, INT)));
    }

    @Test
    void staticAbstractAndReceiverSpecializedMethodsStayFailClosed() {
        Ast.ClassDecl owner = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        Ast.MethodDecl original = owner.methods().getFirst();
        var items = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(7L)));
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "work"),
                List.of(), false, items);
        assertDoesNotThrow(() ->
                BoundInvocation.bindClassMethod(owner, call, List.of(BOOL, INT)));
        for (int mode = 0; mode < 7; mode++) {
            Ast.MethodDecl unsafe = new Ast.MethodDecl(
                    original.name(), original.visibility(),
                    mode == 0, mode == 1, mode == 2, mode == 3,
                    mode == 4,
                    mode == 5 ? Ast.TypeRef.simple("Box") : null,
                    original.genericParameters(), original.parameters(),
                    original.returnType(),
                    mode == 6 ? List.of(new Ast.Annotation("CallableBy", List.of(INT)))
                              : original.annotations(),
                    original.body());
            Ast.ClassDecl unproved = new Ast.ClassDecl(
                    owner.name(), owner.visibility(), owner.isAbstract(), owner.actorKind(),
                    owner.genericParameters(), owner.parents(), owner.interfaces(),
                    owner.fields(), owner.constructor(), List.of(unsafe));
            assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindClassMethod(unproved, call, List.of(BOOL, INT)),
                    "unsafe method variant " + mode);
        }
    }

    @Test
    void effectfulAndAnnotatedFunctionTargetsRequireIndependentProof() {
        Ast.ModuleDecl owner = owner();
        Ast.FunctionDecl baseline = (Ast.FunctionDecl) owner.declarations().getFirst();
        for (int mode = 0; mode < 5; mode++) {
            Ast.FunctionDecl unsafe = new Ast.FunctionDecl(
                    baseline.name(),
                    mode == 0 ? Ast.CallableKind.ROUTINE : baseline.kind(),
                    baseline.visibility(),
                    mode == 1, mode == 2, mode == 3,
                    baseline.structural(), baseline.nonLexical(),
                    baseline.trapped(), baseline.actorKind(),
                    baseline.genericParameters(), baseline.parameters(),
                    baseline.returnType(),
                    mode == 4 ? List.of(new Ast.Annotation("CallerRole", List.of(INT)))
                              : baseline.annotations(),
                    baseline.body(), baseline.genericSpecs());
            Ast.ModuleDecl unproved = new Ast.ModuleDecl(
                    owner.name(), owner.annotations(), owner.contracts(), List.of(unsafe));
            assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindModuleFunction(unproved, call("work"), List.of(BOOL, INT)),
                    "unsafe function variant " + mode);
        }
    }

    @Test
    void annotatedConstructorsRequireAuthorizationBeforeBinding() {
        Ast.ClassDecl base = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int a, bool c) { return; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        Ast.ConstructorDecl original = base.constructor();
        Ast.ConstructorDecl restricted = new Ast.ConstructorDecl(
                original.visibility(), original.parameters(),
                List.of(new Ast.Annotation("CallableBy", List.of(INT))),
                original.body());
        Ast.ClassDecl owner = new Ast.ClassDecl(
                base.name(), base.visibility(), base.isAbstract(), base.actorKind(),
                base.genericParameters(), base.parents(), base.interfaces(),
                base.fields(), restricted, base.methods());
        var args = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(5L)));
        assertThrows(IllegalArgumentException.class, () ->
                BoundInvocation.bindClassConstructor(owner,
                        new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), args, false),
                        List.of(BOOL, INT)));
    }

    @Test
    void canonicalBoundScheduleEnforcesSourceThenAbiPhases() {
        Ast.ModuleDecl module = owner();
        var bound = BoundInvocation.bindModuleFunction(
                module, call("work"), List.of(BOOL, INT));
        var steps = bound.schedule().steps();
        assertEquals(7, steps.size());
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE,
                steps.get(0).operation());
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT,
                steps.get(1).operation());
        assertEquals(0, steps.get(1).index());
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT,
                steps.get(2).operation());
        assertEquals(1, steps.get(2).index());
        assertEquals(List.of(0, 1, 2), steps.subList(3, 6).stream()
                .map(OptionalCallEvaluationSchedule.Step::index).toList());
        assertTrue(steps.subList(3, 6).stream().allMatch(s ->
                s.operation() == OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT));
        assertEquals(OptionalCallEvaluationSchedule.Operation.INVOKE,
                steps.getLast().operation());
        assertEquals(-1, steps.getLast().index());
        assertEquals(List.of(2, 1, 0), bound.schedule().abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> bound.schedule().steps().clear());
        assertThrows(IllegalStateException.class, bound::requireExecutable);
    }

    @Test
    void methodAndConstructorSchedulesHaveDistinctCalleeRules() {
        Ast.ClassDecl owner = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int a, bool c) { return; }
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        var args = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(7L)));
        var method = BoundInvocation.bindClassMethod(owner,
                new Ast.StagedCallExpr(
                        new Ast.MemberExpr(new Ast.NameExpr("box"), "work"),
                        List.of(), false, args),
                List.of(BOOL, INT));
        var constructor = BoundInvocation.bindClassConstructor(owner,
                new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), args, false),
                List.of(BOOL, INT));
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE,
                method.schedule().steps().getFirst().operation());
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT,
                constructor.schedule().steps().getFirst().operation());
        assertEquals(2, constructor.schedule().evaluationOrder().size());
        assertEquals(3, method.schedule().evaluationOrder().size());
        assertEquals(2, constructor.schedule().abiSlots().size());
        assertEquals(2, method.schedule().abiSlots().size());
        assertThrows(IllegalStateException.class, method::requireExecutable);
        assertThrows(IllegalStateException.class, constructor::requireExecutable);
    }
}
