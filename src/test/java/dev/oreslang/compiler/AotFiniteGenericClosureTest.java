package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class AotFiniteGenericClosureTest {
    private static Ast.TypeRef type(String name) {
        return Ast.TypeRef.simple(name);
    }

    private static Ast.FunctionDecl fn(String name, List<String> typeParams,
            Ast.TypeRef paramType, Ast.TypeRef resultType, Ast.Expr result) {
        List<Ast.Param> parameters = paramType == null
                ? List.of() : List.of(new Ast.Param(paramType, "value"));
        return new Ast.FunctionDecl(name, Ast.Visibility.PUBLIC, false,
                typeParams, parameters, resultType, List.of(),
                List.of(new Ast.ReturnStmt(result)));
    }

    private static Ast.Program program(Ast.FunctionDecl... functions) {
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                Parser.ROOT_MODULE, List.of(functions))));
    }

    @Test
    void explicitGenericsCloseToFiniteTransitiveMonomorphizationPlan() {
        Ast.FunctionDecl identity = fn("identity", List.of("T"),
                type("T"), type("T"), new Ast.NameExpr("value"));
        Ast.FunctionDecl wrapper = fn("wrapper", List.of("T"),
                type("T"), type("T"),
                new Ast.CallExpr(new Ast.NameExpr("identity"),
                        List.of(type("T")),
                        List.of(new Ast.NameExpr("value"))));
        Ast.FunctionDecl main = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.NameExpr("wrapper"),
                        List.of(type("int")),
                        List.of(new Ast.LiteralExpr(42L))));

        AotFiniteGenericClosure.Plan plan = AotFiniteGenericClosure.plan(
                program(identity, wrapper, main), BuildOptions.executable());
        assertEquals(3, plan.reachable().size());
        assertTrue(plan.reachable().contains(new AotFiniteGenericClosure.Instance(
                Parser.ROOT_MODULE, "main", 0, List.of())));
        assertTrue(plan.reachable().contains(new AotFiniteGenericClosure.Instance(
                Parser.ROOT_MODULE, "wrapper", 1, List.of(type("int")))));
        assertTrue(plan.reachable().contains(new AotFiniteGenericClosure.Instance(
                Parser.ROOT_MODULE, "identity", 1, List.of(type("int")))));
    }

    @Test
    void sameSpecializationIsDeduplicatedEvenWhenCalledTwice() {
        Ast.FunctionDecl identity = fn("identity", List.of("T"),
                type("T"), type("T"), new Ast.NameExpr("value"));
        Ast.CallExpr left = new Ast.CallExpr(new Ast.NameExpr("identity"),
                List.of(type("int")), List.of(new Ast.LiteralExpr(1L)));
        Ast.CallExpr right = new Ast.CallExpr(new Ast.NameExpr("identity"),
                List.of(type("int")), List.of(new Ast.LiteralExpr(2L)));
        Ast.FunctionDecl main = fn("main", List.of(), null, type("int"),
                new Ast.BinaryExpr("+", left, right));
        assertEquals(2, AotFiniteGenericClosure.plan(
                program(identity, main), BuildOptions.executable()).reachable().size());
    }

    @Test
    void typeGrowingRecursiveInstantiationFailsWithinFiniteBudget() {
        Ast.TypeRef listOfT = new Ast.TypeRef("List", List.of(type("T")), false);
        Ast.FunctionDecl growing = fn("grow", List.of("T"),
                type("T"), type("T"),
                new Ast.CallExpr(new Ast.NameExpr("grow"),
                        List.of(listOfT), List.of(new Ast.NameExpr("value"))));
        Ast.FunctionDecl main = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.NameExpr("grow"),
                        List.of(type("int")), List.of(new Ast.LiteralExpr(7L))));
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(growing, main), BuildOptions.executable()));
        assertTrue(err.getMessage().contains("AOT-FINITE-GENERICS"), err::toString);
        assertTrue(err.getMessage().contains("budget")
                        || err.getMessage().contains("depth"),
                err::toString);
    }

    @Test
    void unprovenInferredGenericAndVirtualDispatchFailClosed() {
        Ast.FunctionDecl identity = fn("identity", List.of("T"),
                type("T"), type("T"), new Ast.NameExpr("value"));
        Ast.FunctionDecl inferred = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.NameExpr("identity"),
                        List.of(new Ast.LiteralExpr(42L))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(identity, inferred), BuildOptions.executable()))
                .getMessage().contains("inferred generic"));

        Ast.FunctionDecl dynamic = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.MemberExpr(new Ast.NameExpr("object"), "run"),
                        List.of(new Ast.LiteralExpr(42L))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(dynamic), BuildOptions.executable()))
                .getMessage().contains("dispatch"));
    }

    @Test
    void unspecializedPublicLibraryGenericFailsInsteadOfRuntimeFallback() {
        Ast.FunctionDecl identity = fn("identity", List.of("T"),
                type("T"), type("T"), new Ast.NameExpr("value"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(identity), BuildOptions.library(Map.of())));
        assertTrue(error.getMessage().contains("finite instantiation manifest"),
                error::toString);
    }

    @Test
    void distinctOverloadArityDoesNotSilentlySelectWrongTarget() {
        Ast.FunctionDecl main = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.NameExpr("missing"), List.of()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(main), BuildOptions.executable()))
                .getMessage().contains("unresolved static call"));
    }

    @Test
    void checkedMacroFunctionCaptureIsAnExplicitStaticDependency() {
        Ast.Program expanded = MacroExpander.expand(Parser.parse("""
                pub fnc helper() -> int { return 42; }
                define macro reference(Identifier target) -> Syntax<Expr> {
                    return capture_fnc(target);
                }
                fnc main() -> int {
                    return reference!(ident helper)();
                }
                """));
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(expanded, BuildOptions.executable());
        assertEquals(2, plan.reachable().size());
        assertTrue(plan.reachable().stream().anyMatch(s -> s.name().equals("helper")));
    }

    @Test
    void publicCompilerApiTypeChecksBeforeProducingClosedPlan() {
        AotFiniteGenericClosure.Plan simple = OresCompiler.planFiniteAotGenerics(
                "fnc main() -> int { return 7; }", BuildOptions.executable());
        assertEquals(1, simple.reachable().size());

        AotFiniteGenericClosure.Plan fromMacro = OresCompiler.planFiniteAotGenerics("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc main() -> int {
                    return identity!(syntax expr 42);
                }
                """, BuildOptions.executable());
        assertEquals(1, fromMacro.reachable().size());
    }

    @Test
    void unresolvedGenericFormalCannotMasqueradeAsConcreteType() {
        Ast.FunctionDecl identity = fn("identity", List.of("T"),
                type("T"), type("T"), new Ast.NameExpr("value"));
        Ast.FunctionDecl main = fn("main", List.of(), null, type("int"),
                new Ast.CallExpr(new Ast.NameExpr("identity"),
                        List.of(type("T")), List.of(new Ast.LiteralExpr(7L))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(identity, main), BuildOptions.executable()))
                .getMessage().contains("unbound generic formal"));
    }

    @Test
    void manifestOrderingIsStableAcrossRootSetIteration() {
        Ast.FunctionDecl alpha = fn("alpha", List.of(), null,
                type("int"), new Ast.LiteralExpr(1L));
        Ast.FunctionDecl zeta = fn("zeta", List.of(), null,
                type("int"), new Ast.LiteralExpr(2L));
        BuildOptions options = new BuildOptions(Map.of(),
                java.util.Set.of("zeta", "alpha"), false);
        AotFiniteGenericClosure.Plan plan = AotFiniteGenericClosure.plan(
                program(zeta, alpha), options);
        assertEquals(List.of("alpha", "zeta"),
                plan.reachable().stream().map(AotFiniteGenericClosure.Instance::name).toList());
    }

    @Test
    void sourceUnitIdentityFollowsTheSpecializationManifest() {
        Ast.FunctionDecl main = fn("main", List.of(), null,
                type("int"), new Ast.LiteralExpr(42L));
        Ast.Program source = MacroSourceUnitIdentity.attach(
                program(main), "pkg/units/../entry.ores");
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(source, BuildOptions.executable());
        assertEquals("pkg/entry.ores", plan.sourceUnitId());
    }

    @Test
    void functionValueRequiresAStaticWitnessRatherThanDynamicCodegen() {
        Ast.FunctionDecl main = fn("main", List.of(), null,
                Ast.TypeRef.functionType(List.of(type("int")), type("int")),
                new Ast.LiteralExpr(7L));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(
                        program(main), BuildOptions.executable()))
                .getMessage().contains("static witness/closure-layout proof"));
    }

    @Test
    void checkedSourceGenericCallsProduceTheSameFiniteManifest() {
        AotFiniteGenericClosure.Plan plan =
                OresCompiler.planFiniteAotGenerics("""
                        fnc identity<T>(T value): T { return value; }
                        fnc wrapper<T>(T value): T {
                            return identity<T>(value);
                        }
                        fnc main(): int { return wrapper<int>(42); }
                        """, BuildOptions.executable());
        assertEquals(3, plan.reachable().size());
        assertTrue(plan.reachable().stream().anyMatch(instance ->
                instance.name().equals("identity")
                        && instance.typeArguments().equals(List.of(type("int")))));
    }

    @Test
    void checkedButInferredGenericsRequireExplicitAotLoweringProof() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.planFiniteAotGenerics("""
                        fnc identity<T>(T value): T { return value; }
                        fnc main(): int { return identity(42); }
                        """, BuildOptions.executable()));
        assertTrue(err.getMessage().contains("inferred generic"),
                err::toString);
    }

    @Test
    void indexingBudgetFailsBeforeUnboundedModuleIndexing() {
        java.util.ArrayList<Ast.ModuleDecl> modules = new java.util.ArrayList<>();
        for (int i = 0; i < 257; i++) {
            modules.add(new Ast.ModuleDecl("module_" + i, List.of()));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(new Ast.Program(modules),
                        BuildOptions.executable()));
        assertTrue(error.getMessage().contains("module indexing budget"),
                error::toString);
    }

    @Test
    void oversizedFunctionSignatureFailsBeforeGraphTraversal() {
        java.util.ArrayList<Ast.Param> parameters = new java.util.ArrayList<>();
        for (int i = 0; i < 129; i++) {
            parameters.add(new Ast.Param(type("int"), "param_" + i));
        }
        Ast.FunctionDecl oversized = new Ast.FunctionDecl(
                "main", Ast.Visibility.PUBLIC, false, List.of(),
                parameters, type("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(1L))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(program(oversized),
                        BuildOptions.executable()));
        assertTrue(error.getMessage().contains("function signature budget"),
                error::toString);
    }

    @Test
    void specializedLocalVariableTypesMustBeConcrete() {
        Ast.FunctionDecl source = fn("main", List.of(), null,
                type("int"), new Ast.LiteralExpr(7L));
        Ast.FunctionDecl unresolved = new Ast.FunctionDecl(
                source.name(), source.kind(), source.visibility(),
                source.async(), source.suspends(), source.generator(),
                source.structural(), source.nonLexical(), source.trapped(),
                source.actorKind(), source.genericParameters(),
                source.parameters(), source.returnType(), source.annotations(),
                List.of(new Ast.BindingStmt(Ast.BindingKind.CONST,
                                Ast.TypeRef.inferred(), "local",
                                new Ast.LiteralExpr(5L)),
                        new Ast.ReturnStmt(new Ast.LiteralExpr(7L))),
                source.genericSpecs(), source.effects());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(program(unresolved),
                        BuildOptions.executable()));
        assertTrue(error.getMessage().contains("unresolved/inferred type"),
                error::toString);
    }

    @Test
    void uncheckedMacroDeclarationsNeverAppearAsExecutableClosure() {
        Ast.Program source = Parser.parse("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc main() -> int { return identity!(syntax expr 42); }
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AotFiniteGenericClosure.plan(source, BuildOptions.executable()))
                .getMessage().contains("only free functions"));
    }
}
