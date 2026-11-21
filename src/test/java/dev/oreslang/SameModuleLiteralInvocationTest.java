package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.SameModuleLiteralInvocation;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.OptionalCallAbiPreview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lexical-origin assertions only, with no executable type/ownership token. */
final class SameModuleLiteralInvocationTest {
    private static Ast.Program program() {
        return Parser.parse("""
                define module app as
                    pub fnc target(int a, string? b, bool c): int { return a; }
                    pub fnc caller(): void {
                        const result = target(c: true, b: _, a: 4);
                        return;
                    }
                end
                """);
    }

    private static Ast.FunctionDecl caller(Ast.Program program) {
        return (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
    }

    private static Ast.StagedCallExpr call(Ast.FunctionDecl caller) {
        return (Ast.StagedCallExpr) ((Ast.BindingStmt) caller.body().getFirst()).initializer();
    }

    @Test
    void directSourceInvocationHasSameProgramAndDeclarationIdentity() {
        Ast.Program program = program();
        Ast.FunctionDecl calling = caller(program);
        Ast.StagedCallExpr call = call(calling);
        var witness = SameModuleLiteralInvocation.inspect(program, calling, call);

        assertSame(program, witness.program());
        assertSame(program.modules().getFirst(), witness.module());
        assertSame(calling, witness.caller());
        assertSame(call, witness.source());
        assertSame(program.modules().getFirst().declarations().getFirst(),
                witness.bound().selectedDeclaration());
        assertEquals(List.of(2, 1, 0),
                witness.tempRecipe().abiSlots().stream()
                        .map(slot -> slot.rawCallItemIndex()).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                witness.tempRecipe().abiSlots().get(1).action());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, witness.readiness());
        assertThrows(IllegalStateException.class, witness::requireExecutable);
    }

    @Test
    void detachedOrForeignCallerAndDetachedInvocationCannotClaimLexicalOrigin() {
        Ast.Program program = program();
        Ast.FunctionDecl calling = caller(program);
        Ast.StagedCallExpr original = call(calling);
        Ast.Program another = Parser.parse("""
                define module different as
                    pub fnc caller(): void { return; }
                end
                """);
        var foreignCaller = (Ast.FunctionDecl)
                another.modules().getFirst().declarations().getFirst();
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(program, foreignCaller, original));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(program, calling,
                        new Ast.StagedCallExpr(original.callee(), List.of(), false,
                                original.arguments())));
    }

    @Test
    void shadowedCallTargetAndUnsoundCallerTypeClaimsAreRejected() {
        Ast.Program parsed = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(int target): void {
                        const result = target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl caller = caller(parsed);
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(parsed, caller, call(caller)));

        Ast.Program variables = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): void {
                        const x = 7;
                        const result = target(a: x);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl variableCaller = caller(variables);
        Ast.StagedCallExpr variableCall = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) variableCaller.body().get(1)).initializer();
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(variables, variableCaller, variableCall));
    }

    @Test
    void importedOrNestedScopeFailsClosedBeforeAnyExecution() {
        Ast.Program parsed = program();
        Ast.ImportDecl bogus = new Ast.ImportDecl(
                Ast.ImportKind.MODULE, List.of("foreign"), false, null, "foreign");
        Ast.Program imported = new Ast.Program(parsed.namespace(),
                List.of(bogus), parsed.modules());
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(imported,
                        caller(parsed), call(caller(parsed))));

        Ast.FunctionDecl caller = caller(parsed);
        Ast.StagedCallExpr original = call(caller);
        Ast.FunctionDecl wrapped = new Ast.FunctionDecl(
                caller.name(), caller.kind(), caller.visibility(), caller.async(),
                caller.generator(), caller.structural(), caller.nonLexical(), caller.trapped(),
                caller.actorKind(), caller.genericParameters(), caller.parameters(),
                caller.returnType(), caller.annotations(),
                List.of(new Ast.BlockStmt(List.of(new Ast.ExprStmt(original)))));
        Ast.ModuleDecl module = parsed.modules().getFirst();
        Ast.ModuleDecl modified = new Ast.ModuleDecl(module.name(), module.annotations(),
                module.contracts(), List.of(module.declarations().getFirst(), wrapped));
        Ast.Program nested = new Ast.Program(List.of(modified));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(nested, wrapped, original));
    }

    @Test
    void moduleQualifierShadowAndDestructureBindingCannotSupplyAuthority() {
        Ast.Program namespaceShadow = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): void {
                        const app = 7;
                        const result = app.target(a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl calling = caller(namespaceShadow);
        Ast.StagedCallExpr staged = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) calling.body().get(1)).initializer();
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(namespaceShadow, calling, staged));

        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl originalCaller = caller(original);
        Ast.StagedCallExpr call = call(originalCaller);
        Ast.DestructureStmt destructure = new Ast.DestructureStmt(
                List.of(new Ast.DestructureBinding(Ast.BindingKind.CONST, "target")),
                new Ast.TupleExpr(List.of(new Ast.LiteralExpr(1L),
                        new Ast.LiteralExpr(2L))));
        Ast.FunctionDecl modified = new Ast.FunctionDecl(
                originalCaller.name(), originalCaller.kind(), originalCaller.visibility(),
                originalCaller.async(), originalCaller.generator(),
                originalCaller.structural(), originalCaller.nonLexical(),
                originalCaller.trapped(), originalCaller.actorKind(),
                originalCaller.genericParameters(), originalCaller.parameters(),
                originalCaller.returnType(), originalCaller.annotations(),
                List.of(destructure, originalCaller.body().getFirst()));
        Ast.ModuleDecl modifiedModule = new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(),
                List.of(module.declarations().getFirst(), modified));
        Ast.Program modifiedProgram = new Ast.Program(List.of(modifiedModule));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(modifiedProgram, modified, call));
    }

    @Test
    void specialEffectfulCallingContextCannotClaimOrdinaryLexicalWitness() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl f = caller(original);
        Ast.StagedCallExpr call = call(f);
        Ast.FunctionDecl unsafe = new Ast.FunctionDecl(
                f.name(), f.kind(), f.visibility(), true, f.generator(),
                f.structural(), f.nonLexical(), f.trapped(), f.actorKind(),
                f.genericParameters(), f.parameters(), f.returnType(),
                f.annotations(), f.body());
        Ast.ModuleDecl modified = new Ast.ModuleDecl(module.name(),
                module.annotations(), module.contracts(),
                List.of(module.declarations().getFirst(), unsafe));
        Ast.Program program = new Ast.Program(List.of(modified));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(program, unsafe, call));
    }

    @Test
    void sameModuleQualifiedLiteralCallKeepsStaticTargetNoReceiverEvaluation() {
        Ast.Program program = Parser.parse("""
                define module app as
                    pub fnc target(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        const result = app.target(c: true, a: 5);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl caller = caller(program);
        Ast.StagedCallExpr staged = call(caller);
        var preview = SameModuleLiteralInvocation.inspect(program, caller, staged);
        assertSame(program.modules().getFirst().declarations().getFirst(),
                preview.bound().selectedDeclaration());
        assertEquals(dev.oreslang.types.OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION,
                preview.tempRecipe().targetKind());
        assertNull(preview.tempRecipe().receiverExpression());
        assertEquals(-1, preview.tempRecipe().receiverTempIndex());
        assertEquals(List.of(1, 0),
                preview.tempRecipe().abiSlots().stream()
                        .map(slot -> slot.tempIndex()).toList());
        assertThrows(IllegalStateException.class, preview::requireExecutable);
    }

    @Test
    void foreignModuleTargetCollisionRequiresUnqualifiedResolutionProof() {
        Ast.Program parsed = program();
        Ast.ModuleDecl app = parsed.modules().getFirst();
        Ast.FunctionDecl calling = caller(parsed);
        Ast.StagedCallExpr staged = call(calling);
        Ast.ModuleDecl foreignSource = Parser.parse("""
                define module foreign as
                    pub fnc target(int a): int { return a; }
                end
                """).modules().getFirst();

        for (Ast.Decl competing : List.<Ast.Decl>of(
                foreignSource.declarations().getFirst(),
                new Ast.FieldDecl("target", Ast.Visibility.PRIVATE,
                        Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                        new Ast.LiteralExpr(5L)),
                new Ast.ClassDecl("target", false, List.of(), List.of(), List.of()),
                new Ast.TypeAliasDecl("target", List.of(),
                        Ast.TypeRef.simple("int")))) {
            Ast.Program ambiguous = new Ast.Program(List.of(
                    app, new Ast.ModuleDecl("foreign", List.of(competing))));
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> SameModuleLiteralInvocation.inspect(
                            ambiguous, calling, staged));
            assertTrue(rejected.getMessage().contains(
                    "cross-module unqualified target"), rejected::toString);
        }

        // Distinct module names and disjoint declarations are not sufficient
        // grounds to reject an otherwise valid shape-only lexical preview.
        Ast.Program disjoint = new Ast.Program(List.of(app,
                new Ast.ModuleDecl("foreign", List.of(
                        new Ast.TypeAliasDecl("Other", List.of(),
                                Ast.TypeRef.simple("int"))))));
        var preview = SameModuleLiteralInvocation.inspect(
                disjoint, calling, staged);
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, preview.readiness());
        assertThrows(IllegalStateException.class, preview::requireExecutable);
    }

    @Test
    void duplicateModuleNamesCannotForgeStaticSameModuleResolution() {
        Ast.Program original = program();
        Ast.ModuleDecl app = original.modules().getFirst();
        Ast.ModuleDecl another = new Ast.ModuleDecl("app", List.of());
        Ast.Program ambiguous = new Ast.Program(List.of(app, another));
        Ast.FunctionDecl calling = caller(original);
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        ambiguous, calling, call(calling)));
        // A distinct unrelated module name does not create an ambiguity.
        Ast.Program disjoint = new Ast.Program(List.of(
                app, new Ast.ModuleDecl("other", List.of())));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                disjoint, calling, call(calling)));
    }

    @Test
    void aliasOrClassNameCollisionsMustNotClaimCompilerCheckedScope() {
        Ast.Program original = program();
        Ast.ModuleDecl source = original.modules().getFirst();
        Ast.FunctionDecl calling = caller(original);
        Ast.StagedCallExpr staged = call(calling);
        Ast.TypeAliasDecl colliding = new Ast.TypeAliasDecl(
                "target", List.of(), Ast.TypeRef.simple("int"));
        Ast.ModuleDecl withAlias = new Ast.ModuleDecl(
                source.name(), List.of(source.declarations().getFirst(),
                        calling, colliding));
        Ast.Program conflicting = new Ast.Program(List.of(withAlias));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        conflicting, calling, staged));

        Ast.TypeAliasDecl moduleAlias = new Ast.TypeAliasDecl(
                "app", List.of(), Ast.TypeRef.simple("int"));
        Ast.ModuleDecl withModuleAlias = new Ast.ModuleDecl(
                source.name(), List.of(source.declarations().getFirst(),
                        calling, moduleAlias));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        new Ast.Program(List.of(withModuleAlias)), calling, staged));
    }

    @Test
    void annotatedOrContractedModuleMustBeExpandedBeforeLexicalProof() {
        Ast.Program original = program();
        Ast.ModuleDecl source = original.modules().getFirst();
        Ast.FunctionDecl calling = caller(original);
        var annotation = new Ast.Annotation("Authoritative", List.of());
        var annotated = new Ast.ModuleDecl(
                source.name(), List.of(annotation), List.of(),
                source.declarations());
        var contracted = new Ast.ModuleDecl(
                source.name(), List.of(), List.of(Ast.TypeRef.simple("Shape")),
                source.declarations());
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        new Ast.Program(List.of(annotated)), calling, call(calling)));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        new Ast.Program(List.of(contracted)), calling, call(calling)));
    }


    @Test
    void classAndInterfaceNamesCollidingWithFunctionOrModuleNeedResolver() {
        Ast.Program original = program();
        Ast.ModuleDecl source = original.modules().getFirst();
        Ast.FunctionDecl calling = caller(original);
        Ast.StagedCallExpr staged = call(calling);

        Ast.ClassDecl sameNameClass = new Ast.ClassDecl(
                "target", false, List.of(), List.of(), List.of());
        Ast.InterfaceDecl sameNameInterface = new Ast.InterfaceDecl(
                "app", Ast.Visibility.PRIVATE, List.of(), List.of(), List.of(), false);
        for (Ast.Decl competing : List.<Ast.Decl>of(
                sameNameClass, sameNameInterface)) {
            Ast.ModuleDecl ambiguous = new Ast.ModuleDecl(
                    source.name(), List.of(source.declarations().getFirst(),
                            calling, competing));
            assertThrows(IllegalArgumentException.class,
                    () -> SameModuleLiteralInvocation.inspect(
                            new Ast.Program(List.of(ambiguous)), calling, staged));
        }

        Ast.ClassDecl unrelated = new Ast.ClassDecl(
                "Unrelated", false, List.of(), List.of(), List.of());
        Ast.ModuleDecl safe = new Ast.ModuleDecl(
                source.name(), List.of(source.declarations().getFirst(),
                        calling, unrelated));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                new Ast.Program(List.of(safe)), calling, staged));
    }


    @Test
    void duplicateNestedCallAstAndUnprovedEarlierEffectsAreNotLexicalProof() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl caller = caller(original);
        Ast.StagedCallExpr target = call(caller);

        // The exact same AST call object appears nested inside another
        // expression as well as at its original direct statement site.
        var nested = new Ast.BindingStmt(Ast.BindingKind.CONST, null, "hidden",
                new Ast.ListExpr(List.of(target)));
        Ast.FunctionDecl duplicate = withBody(caller, List.of(
                nested, caller.body().getFirst(), new Ast.ReturnStmt(null)));
        Ast.Program duplicateProgram = withCaller(module, duplicate);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(duplicateProgram, duplicate, target));
        assertTrue(failure.getMessage().contains("other expression"), failure::toString);

        // Even when the original staged call is unique, this small witness
        // cannot prove a separate earlier side effect or defer.
        Ast.FunctionDecl effected = withBody(caller, List.of(
                new Ast.ExprStmt(new Ast.CallExpr(
                        new Ast.NameExpr("anotherEffect"), List.of())),
                caller.body().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, effected), effected, target));

        Ast.FunctionDecl deferred = withBody(caller, List.of(
                new Ast.DeferStmt(new Ast.LiteralExpr(1L)),
                caller.body().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, deferred), deferred, target));
    }

    @Test
    void unreachableTopLevelCallAndUnreachableTailCannotClaimLexicalProof() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);

        // The exact original call AST is present but unreachable after an
        // unconditional return. Shape-only inspection must not certify it.
        Ast.FunctionDecl deadCall = withBody(source, List.of(
                new Ast.ReturnStmt(null), source.body().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, deadCall), deadCall, staged));

        // Even a reachable call in a body with unproved dead instructions
        // after return must be referred to the full CFG checker.
        Ast.FunctionDecl deadTail = withBody(source, List.of(
                source.body().getFirst(), new Ast.ReturnStmt(null),
                new Ast.ExprStmt(new Ast.LiteralExpr(5L))));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, deadTail), deadTail, staged));

        // A normal direct call followed by its final return is still valid.
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                original, source, staged));
    }

    @Test
    void qualifiedModuleCallRejectsConflictingCallableNamespace() {
        Ast.Program parsed = Parser.parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): void {
                        const value = app.target(a: 7);
                        return;
                    }
                end
                """);
        Ast.FunctionDecl calling = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.StagedCallExpr staged = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) calling.body().getFirst()).initializer();
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                parsed, calling, staged));

        Ast.Program competingSource = Parser.parse("""
                define module unrelated as
                    pub fnc app(): int { return 4; }
                end
                """);
        Ast.FunctionDecl competing = (Ast.FunctionDecl)
                competingSource.modules().getFirst().declarations().getFirst();
        Ast.ModuleDecl source = parsed.modules().getFirst();
        Ast.ModuleDecl withCompetitor = new Ast.ModuleDecl(
                source.name(), source.annotations(), source.contracts(),
                List.of(source.declarations().getFirst(), calling, competing));
        Ast.Program ambiguous = new Ast.Program(List.of(withCompetitor));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(ambiguous, calling, staged));

        // A differently named unrelated function does not invalidate the
        // static qualifier in this deliberately restricted witness.
        Ast.Program harmlessSource = Parser.parse("""
                define module unrelated as
                    pub fnc helper(): int { return 4; }
                end
                """);
        Ast.FunctionDecl harmless = (Ast.FunctionDecl)
                harmlessSource.modules().getFirst().declarations().getFirst();
        Ast.Program safe = new Ast.Program(List.of(new Ast.ModuleDecl(
                source.name(), source.annotations(), source.contracts(),
                List.of(source.declarations().getFirst(), calling, harmless))));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                safe, calling, staged));
    }

    @Test
    void forgedOpaqueOrNullLiteralOutsideCallCannotEarnPureLexicalWitness() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);

        // LiteralExpr(Object) can be directly assembled with payloads the
        // language parser never emits as pure scalar literal expressions.
        for (Ast.LiteralExpr forged : List.of(
                new Ast.LiteralExpr(new Object()),
                new Ast.LiteralExpr(null),
                new Ast.LiteralExpr(new Ast.Imaginary(2.0)))) {
            Ast.FunctionDecl modified = withBody(source, List.of(
                    new Ast.ExprStmt(forged),
                    source.body().getFirst(),
                    new Ast.ReturnStmt(null)));
            assertThrows(IllegalArgumentException.class,
                    () -> SameModuleLiteralInvocation.inspect(
                            withCaller(module, modified), modified, staged));
        }

        // Independently typed primitive scalars remain admissible when they
        // occur outside the selected source call.
        Ast.FunctionDecl scalar = withBody(source, List.of(
                new Ast.ExprStmt(new Ast.LiteralExpr(13L)),
                new Ast.ExprStmt(new Ast.LiteralExpr(true)),
                source.body().getFirst(),
                new Ast.ReturnStmt(null)));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                withCaller(module, scalar), scalar, staged));
    }

    @Test
    void forgedEmptyStatementAndUninitializedLocalNeedSemanticProof() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);

        for (Ast.Stmt unproved : List.<Ast.Stmt>of(
                new Ast.ExprStmt(null),
                new Ast.BindingStmt(Ast.BindingKind.CONST, null, "missing", null))) {
            Ast.FunctionDecl forged = withBody(source, List.of(
                    unproved, source.body().getFirst(), new Ast.ReturnStmt(null)));
            assertThrows(IllegalArgumentException.class,
                    () -> SameModuleLiteralInvocation.inspect(
                            withCaller(module, forged), forged, staged));
        }
        Ast.FunctionDecl ordinary = withBody(source, List.of(
                new Ast.BindingStmt(Ast.BindingKind.CONST, null, "present",
                        new Ast.LiteralExpr(7L)),
                source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                withCaller(module, ordinary), ordinary, staged));
    }

    @Test
    void duplicateSameScopeLocalsCannotEarnStagedLexicalWitness() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);

        Ast.BindingStmt seed = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                "seed", new Ast.LiteralExpr(7L));
        Ast.FunctionDecl forged = withBody(source, List.of(
                seed,
                new Ast.BindingStmt(Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                        "seed", new Ast.LiteralExpr(8L)),
                source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, forged), forged, staged));

        Ast.FunctionDecl unique = withBody(source, List.of(
                seed,
                new Ast.BindingStmt(Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                        "other", new Ast.LiteralExpr(8L)),
                source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                withCaller(module, unique), unique, staged));
    }

    @Test
    void callerParameterCollisionOrDuplicateFormalsNeedLexicalProof() {
        Ast.Program original = program();
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl source = caller(original);
        Ast.StagedCallExpr staged = call(source);
        Ast.Param param = new Ast.Param(Ast.TypeRef.simple("int"), "seed");
        Ast.BindingStmt local = new Ast.BindingStmt(
                Ast.BindingKind.CONST, Ast.TypeRef.simple("int"),
                "seed", new Ast.LiteralExpr(7L));

        Ast.FunctionDecl shadowed = withFormalsAndBody(source, List.of(param),
                List.of(local, source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, shadowed), shadowed, staged));

        Ast.FunctionDecl duplicated = withFormalsAndBody(source,
                List.of(param, param),
                List.of(source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertThrows(IllegalArgumentException.class,
                () -> SameModuleLiteralInvocation.inspect(
                        withCaller(module, duplicated), duplicated, staged));

        Ast.FunctionDecl legal = withFormalsAndBody(source, List.of(param),
                List.of(source.body().getFirst(), new Ast.ReturnStmt(null)));
        assertDoesNotThrow(() -> SameModuleLiteralInvocation.inspect(
                withCaller(module, legal), legal, staged));
    }

    private static Ast.FunctionDecl withFormalsAndBody(
            Ast.FunctionDecl source, List<Ast.Param> formals, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                source.name(), source.kind(), source.visibility(), source.async(),
                source.suspends(), source.generator(), source.structural(),
                source.nonLexical(), source.trapped(), source.actorKind(),
                source.genericParameters(), formals, source.returnType(),
                source.annotations(), body);
    }

    private static Ast.FunctionDecl withBody(Ast.FunctionDecl source, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                source.name(), source.kind(), source.visibility(), source.async(),
                source.suspends(), source.generator(), source.structural(),
                source.nonLexical(), source.trapped(), source.actorKind(),
                source.genericParameters(), source.parameters(), source.returnType(),
                source.annotations(), body);
    }

    private static Ast.Program withCaller(Ast.ModuleDecl module, Ast.FunctionDecl caller) {
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(),
                List.of(module.declarations().getFirst(), caller))));
    }
}
