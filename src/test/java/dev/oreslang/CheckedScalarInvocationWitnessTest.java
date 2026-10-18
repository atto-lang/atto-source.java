package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.CheckedScalarInvocationWitness;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Preview-only proof tests, never a passing runtime/Option ABI conformance claim. */
final class CheckedScalarInvocationWitnessTest {
    private static Ast.Program program() {
        return Parser.parse("""
                define module app as
                    pub fnc target(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): void {
                        const result = target(enabled: true, label: _, a: 7);
                        return;
                    }
                end
                """);
    }

    private static Ast.FunctionDecl caller(Ast.Program program) {
        return (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
    }

    private static Ast.StagedCallExpr call(Ast.FunctionDecl caller) {
        return (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
    }

    @Test
    void checkerIssuesLexicalAndScalarWitnessButNotExecutableToken() {
        Ast.Program program = program();
        Ast.FunctionDecl caller = caller(program);
        var checked = TypeChecker.inspectStagedScalarInvocation(program, caller, call(caller));
        assertSame(program.modules().getFirst().declarations().getFirst(),
                checked.selectedDeclaration());
        assertEquals(CheckedScalarInvocationWitness.ResultSite.BINDING,
                checked.resultSite());
        assertEquals(Ast.TypeRef.simple("int"), checked.resultType());
        assertEquals(List.of(Ast.TypeRef.simple("bool"), Ast.TypeRef.simple("int")),
                checked.checkedLiteralTypes());
        assertEquals(List.of(2, 1, 0), checked.lexical().tempRecipe().abiSlots()
                .stream().map(s -> s.rawCallItemIndex()).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> checked.checkedLiteralTypes().clear());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, checked.readiness());
        assertThrows(IllegalStateException.class, checked::requireExecutable);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program),
                "issuance must never open compiler executable admission");
    }

    @Test
    void returnSiteRequiresExactlyMatchingCallerResultType() {
        Ast.Program valid = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): int { return target(a: 5); }
                end
                """);
        Ast.FunctionDecl caller = caller(valid);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.ReturnStmt) caller.body().getFirst()).value();
        var witness = TypeChecker.inspectStagedScalarInvocation(valid, caller, call);
        assertEquals(CheckedScalarInvocationWitness.ResultSite.RETURN, witness.resultSite());

        Ast.Program invalid = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): bool { return target(a: 5); }
                end
                """);
        Ast.FunctionDecl badCaller = caller(invalid);
        Ast.StagedCallExpr badCall = (Ast.StagedCallExpr)
                ((Ast.ReturnStmt) badCaller.body().getFirst()).value();
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(invalid, badCaller, badCall));
    }

    @Test
    void scalarDeclarationContextCannotSilentlyCoerceResultType() {
        Ast.Program original = program();
        Ast.FunctionDecl sourceCaller = caller(original);
        Ast.StagedCallExpr originalCall = call(sourceCaller);
        Ast.BindingStmt incorrectlyTyped = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("bool"), "result", originalCall);
        Ast.FunctionDecl modified = withBody(sourceCaller,
                List.of(incorrectlyTyped, new Ast.ReturnStmt(null)));
        Ast.Program program = withCaller(original, modified);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(program, modified, originalCall));

        // A matching explicit scalar type is supported without coercion.
        Ast.BindingStmt matching = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("int"), "result", originalCall);
        Ast.FunctionDecl valid = withBody(sourceCaller,
                List.of(matching, new Ast.ReturnStmt(null)));
        Ast.Program matchingProgram = withCaller(original, valid);
        assertDoesNotThrow(() -> TypeChecker.inspectStagedScalarInvocation(
                matchingProgram, valid, originalCall));
    }

    @Test
    void voidResultsAreNotBindingValuesAndUnsupportedNominalResultsFailClosed() {
        Ast.Program voidSource = Parser.parse("""
                define module app as
                    pub fnc target(int a): void { return; }
                    pub fnc caller(): void {
                        const result = target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl calling = caller(voidSource);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        voidSource, calling, call(calling)));

        Ast.Program nominalSource = Parser.parse("""
                define module app as
                    pub fnc target(int a): Option<int> { return None; }
                    pub fnc caller(): void {
                        const result = target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl nominalCaller = caller(nominalSource);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        nominalSource, nominalCaller, call(nominalCaller)));
    }

    @Test
    void detachedOrNonliteralSitesCannotForgeCheckerIssuedProof() {
        Ast.Program program = program();
        Ast.FunctionDecl caller = caller(program);
        Ast.StagedCallExpr originalCall = call(caller);
        Ast.StagedCallExpr detached = new Ast.StagedCallExpr(
                originalCall.callee(), List.of(), false, originalCall.arguments());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(program, caller, detached));

        Ast.Program nonliteral = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): void {
                        const result = target(a: effect());
                        return;
                    }
                end
                """);
        Ast.FunctionDecl effectCaller = caller(nonliteral);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        nonliteral, effectCaller, call(effectCaller)));
    }

    private static Ast.FunctionDecl withBody(Ast.FunctionDecl source, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                source.name(), source.kind(), source.visibility(), source.async(),
                source.suspends(), source.generator(), source.structural(),
                source.nonLexical(), source.trapped(), source.actorKind(),
                source.genericParameters(), source.parameters(), source.returnType(),
                source.annotations(), body);
    }

    private static Ast.Program withCaller(Ast.Program original, Ast.FunctionDecl caller) {
        Ast.ModuleDecl module = original.modules().getFirst();
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(),
                List.of(module.declarations().getFirst(), caller))));
    }
}
