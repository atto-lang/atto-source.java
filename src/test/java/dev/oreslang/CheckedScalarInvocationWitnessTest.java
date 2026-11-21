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

    @Test
    void optionalCallerFormalsDoNotEarnScalarTypeWitness() {
        Ast.Program parsed = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(string? input): void {
                        const result = target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl calling = caller(parsed);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(parsed, calling, call(calling)));
    }

    @Test
    void malformedInferenceTypeCannotSatisfyExplicitResultBinding() {
        Ast.Program original = program();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr invoked = call(source);
        Ast.TypeRef malformed = new Ast.TypeRef("$infer$",
                List.of(Ast.TypeRef.simple("bool")), false);
        Ast.BindingStmt binding = new Ast.BindingStmt(
                Ast.BindingKind.CONST, malformed, "result", invoked);
        Ast.FunctionDecl changed = withBody(source,
                List.of(binding, new Ast.ReturnStmt(null)));
        Ast.Program modified = withCaller(original, changed);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(modified, changed, invoked));
    }

    @Test
    void mutableLocalBindingsRequireOwnershipProofBeforeScalarWitness() {
        Ast.Program original = program();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);

        for (Ast.BindingStmt mutable : List.of(
                new Ast.BindingStmt(Ast.BindingKind.VAL,
                        Ast.TypeRef.simple("int"), "scratch",
                        new Ast.LiteralExpr(4L)),
                new Ast.BindingStmt(Ast.BindingKind.LET,
                        Ast.TypeRef.simple("int"), "scratch",
                        new Ast.LiteralExpr(4L)),
                new Ast.BindingStmt(Ast.BindingKind.CONST,
                        Ast.TypeRef.simple("int"), "scratch",
                        new Ast.LiteralExpr(4L), true))) {
            Ast.FunctionDecl changed = withBody(source, List.of(
                    mutable, source.body().getFirst(), new Ast.ReturnStmt(null)));
            Ast.Program forged = withCaller(original, changed);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.inspectStagedScalarInvocation(
                            forged, changed, staged));
        }

        Ast.BindingStmt immutable = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                "scratch", new Ast.LiteralExpr(4L), false);
        Ast.FunctionDecl safe = withBody(source, List.of(
                immutable, source.body().getFirst(), new Ast.ReturnStmt(null)));
        Ast.Program valid = withCaller(original, safe);
        var witness = TypeChecker.inspectStagedScalarInvocation(valid, safe, staged);
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, witness.readiness());
        assertThrows(IllegalStateException.class, witness::requireExecutable);
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
    @Test
    void selectedReturnTypeIsNotAProofOfCalleeImplementation() {
        for (String body : List.of(
                "return true;",
                "return \"oops\";",
                "return;")) {
            Ast.Program parsed = Parser.parse("""
                    define module app as
                        pub fnc target(int a): int { %s }
                        pub fnc caller(): void {
                            const value = target(a: 5);
                            return;
                        }
                    end
                    """.formatted(body));
            Ast.FunctionDecl f = caller(parsed);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.inspectStagedScalarInvocation(
                            parsed, f, call(f)), body);
        }
    }

    @Test
    void uncheckedCalleeSideEffectsAndFallthroughCannotClaimScalarProof() {
        for (String body : List.of(
                "const before = 3; return a;",
                "return a + 1;",
                "const before = 3;",
                "return target(a: a);")) {
            Ast.Program parsed = Parser.parse("""
                    define module app as
                        pub fnc target(int a): int { %s }
                        pub fnc caller(): void {
                            const value = target(a: 5);
                            return;
                        }
                    end
                    """.formatted(body));
            Ast.FunctionDecl f = caller(parsed);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.inspectStagedScalarInvocation(
                            parsed, f, call(f)), body);
        }
    }

    @Test
    void mismatchBetweenCalleeFormalAndReturnedScalarIsRejected() {
        Ast.Program parsed = Parser.parse("""
                define module app as
                    pub fnc target(int a, bool enabled): int {
                        return enabled;
                    }
                    pub fnc caller(): void {
                        const value = target(a: 5, enabled: true);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl f = caller(parsed);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        parsed, f, call(f)));
    }

    @Test
    void simpleLiteralAndRequiredFormalReturnsRetainNarrowWitness() {
        for (String body : List.of("return a;", "return 5;")) {
            Ast.Program parsed = Parser.parse("""
                    define module app as
                        pub fnc target(int a): int { %s }
                        pub fnc caller(): void {
                            const value = target(a: 5);
                            return;
                        }
                    end
                    """.formatted(body));
            Ast.FunctionDecl f = caller(parsed);
            var witness = TypeChecker.inspectStagedScalarInvocation(
                    parsed, f, call(f));
            assertEquals(Ast.TypeRef.simple("int"), witness.resultType());
            assertThrows(IllegalStateException.class, witness::requireExecutable);
        }
    }

    @Test
    void validVoidOnlyBareReturnCanBePreviewedAsExpressionStatement() {
        Ast.Program parsed = Parser.parse("""
                define module app as
                    pub fnc target(int a): void { return; }
                    pub fnc caller(): void {
                        target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl f = caller(parsed);
        Ast.StagedCallExpr invocation =
                (Ast.StagedCallExpr) ((Ast.ExprStmt) f.body().getFirst()).expression();
        var witness = TypeChecker.inspectStagedScalarInvocation(
                parsed, f, invocation);
        assertEquals(CheckedScalarInvocationWitness.ResultSite.EXPRESSION_STATEMENT,
                witness.resultSite());
        assertEquals(Ast.TypeRef.simple("void"), witness.resultType());
        assertThrows(IllegalStateException.class, witness::requireExecutable);
    }


    @Test
    void nonVoidCallerCannotPreviewCallWithoutItsOwnExplicitReturn() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        Ast.FunctionDecl modified = withSignature(caller, Ast.TypeRef.simple("int"),
                caller.parameters(), List.of(caller.body().getFirst()));
        Ast.Program forged = withCaller(original, modified);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(forged, modified, call));
        assertTrue(failure.getMessage().contains("explicitly return"), failure::toString);
    }

    @Test
    void nonVoidCallerBareReturnOrWrongScalarResultIsRejected() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        for (Ast.ReturnStmt returned : List.of(
                new Ast.ReturnStmt(null),
                new Ast.ReturnStmt(new Ast.LiteralExpr(true)),
                new Ast.ReturnStmt(new Ast.LiteralExpr("wrong")))) {
            Ast.FunctionDecl modified = withSignature(caller, Ast.TypeRef.simple("int"),
                    caller.parameters(), List.of(caller.body().getFirst(), returned));
            Ast.Program forged = withCaller(original, modified);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.inspectStagedScalarInvocation(forged, modified, call));
        }
    }

    @Test
    void voidCallerCannotReturnScalarDespiteValidCallPreview() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        Ast.FunctionDecl modified = withBody(caller,
                List.of(caller.body().getFirst(),
                        new Ast.ReturnStmt(new Ast.LiteralExpr(5L))));
        Ast.Program forged = withCaller(original, modified);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(forged, modified, call));
    }

    @Test
    void unrelatedTypedLiteralBindingCannotSmuggleWrongTypeIntoCaller() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        Ast.BindingStmt mismatch = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("bool"), "other",
                new Ast.LiteralExpr(12L));
        Ast.FunctionDecl modified = withBody(caller, List.of(
                mismatch, caller.body().getFirst(), new Ast.ReturnStmt(null)));
        Ast.Program forged = withCaller(original, modified);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(forged, modified, call));
    }

    @Test
    void callerWithMatchingExplicitScalarReturnStillHasShapeOnlyWitness() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        Ast.FunctionDecl modified = withSignature(caller, Ast.TypeRef.simple("int"),
                caller.parameters(), List.of(
                        caller.body().getFirst(),
                        new Ast.ReturnStmt(new Ast.LiteralExpr(5L))));
        Ast.Program forged = withCaller(original, modified);
        var witness = TypeChecker.inspectStagedScalarInvocation(forged, modified, call);
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, witness.readiness());
        assertThrows(IllegalStateException.class, witness::requireExecutable);
    }

    @Test
    void duplicateCallerFormalNamesCannotForgeLexicalTypeWitness() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr call = call(caller);
        Ast.Param duplicated = new Ast.Param(Ast.TypeRef.simple("int"), "x");
        Ast.FunctionDecl modified = withSignature(caller, caller.returnType(),
                List.of(duplicated, duplicated), caller.body());
        Ast.Program forged = withCaller(original, modified);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(forged, modified, call));
    }

    @Test
    void syntheticCallerSelfCannotMasqueradeAsOrdinaryScalarParameter() {
        Ast.Program original = program();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr originalCall = call(caller);
        Ast.FunctionDecl forged = withSignature(caller, caller.returnType(),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "self")), caller.body());
        Ast.Program constructed = withCaller(original, forged);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        constructed, forged, originalCall));
        assertTrue(error.getMessage().contains("constructor self"), error::toString);
    }

    @Test
    void syntheticSelectedSelfNeverBecomesAbiValueFormal() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl selected = (Ast.FunctionDecl) module.declarations().getFirst();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr forgedCall = new Ast.StagedCallExpr(
                new Ast.NameExpr("target"), List.of(), false,
                List.of(Ast.CallArgument.named("self", new Ast.LiteralExpr(5L))));
        Ast.FunctionDecl forgedTarget = withSignature(selected, selected.returnType(),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "self")),
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("self"))));
        Ast.FunctionDecl forgedCaller = withBody(caller, List.of(
                new Ast.BindingStmt(Ast.BindingKind.CONST, Ast.TypeRef.inferred(),
                        "result", forgedCall), new Ast.ReturnStmt(null)));
        Ast.Program constructed = new Ast.Program(List.of(new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(),
                List.of(forgedTarget, forgedCaller))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.inspectStagedScalarInvocation(
                        constructed, forgedCaller, forgedCall));
        assertTrue(error.getMessage().contains("constructor self"), error::toString);
    }

    private static Ast.FunctionDecl withSignature(
            Ast.FunctionDecl original, Ast.TypeRef returnType,
            List<Ast.Param> formals, List<Ast.Stmt> statements) {
        return new Ast.FunctionDecl(original.name(), original.kind(),
                original.visibility(), original.async(), original.suspends(),
                original.generator(), original.structural(), original.nonLexical(),
                original.trapped(), original.actorKind(),
                original.genericParameters(), formals, returnType,
                original.annotations(), statements);
    }

}
