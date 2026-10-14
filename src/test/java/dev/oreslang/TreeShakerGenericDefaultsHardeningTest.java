package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TreeShakerGenericDefaultsHardeningTest {
    @Test
    void libraryOptimizationKeepsFunctionGenericDefaultsAndDependentTypes() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub fnc choose<A, B=int, C=B, D=Array<C>>(A input): int {
                    return 7;
                }
                fnc unused(): int { return 99; }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl choose = result.program().modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("choose"))
                .findFirst().orElseThrow();

        assertEquals(List.of("A", "B", "C", "D"), choose.genericParameters());
        assertEquals(4, choose.genericSpecs().size());
        assertNull(choose.genericSpecs().get(0).defaultType());
        assertEquals("int", choose.genericSpecs().get(1).defaultType().name());
        assertEquals("B", choose.genericSpecs().get(2).defaultType().name());
        assertEquals("Array", choose.genericSpecs().get(3).defaultType().name());
        assertEquals("C", choose.genericSpecs().get(3).defaultType().arguments().getFirst().name());
        assertTrue(result.retained(Parser.ROOT_MODULE + ".choose"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".unused"));
    }

    @Test
    void optimizerPreservesContractsEffectsAndTypesReachableOnlyThroughDefaults() {
        Ast.ClassDecl payload = new Ast.ClassDecl(
                "Payload", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.FunctionDecl api = new Ast.FunctionDecl(
                "api", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, true, false, false, true, false, Ast.ActorKind.NONE,
                List.of("T"), List.of(), Ast.TypeRef.simple("void"),
                List.of(), List.of(),
                List.of(new Ast.GenericParameter("T", Ast.TypeRef.simple("Payload"))));
        Ast.TypeRef contract = Ast.TypeRef.simple("ServiceContract");
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("service", List.of(), List.of(contract), List.of(payload, api))));

        TreeShaker.Result result = TreeShaker.shake(program, BuildOptions.library(Map.of()));
        Ast.ModuleDecl module = result.program().modules().getFirst();
        Ast.FunctionDecl kept = module.declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();

        assertEquals(List.of(contract), module.contracts(), "module contracts must survive rewrite and prune");
        assertTrue(kept.suspends(), "suspends must not disappear in the optimizer");
        assertTrue(kept.nonLexical(), "nlex must not disappear in the optimizer");
        assertEquals(Ast.TypeRef.simple("Payload"), kept.genericSpecs().getFirst().defaultType());
        assertTrue(result.retained("service.Payload"),
                "a nominal type referenced only through a public default must survive pruning");
    }

    @Test
    void optimizerPreservesTrappedEffectOnGeneratedAst() {
        Ast.FunctionDecl trapped = new Ast.FunctionDecl(
                "checked", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, false, false, false, false, true, Ast.ActorKind.NONE,
                List.of(), List.of(), Ast.TypeRef.simple("void"),
                List.of(), List.of(), List.of());
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("service", List.of(trapped))));

        TreeShaker.Result result = TreeShaker.shake(program, BuildOptions.library(Map.of()));
        Ast.FunctionDecl kept = (Ast.FunctionDecl) result.program().modules().getFirst()
                .declarations().getFirst();
        assertTrue(kept.trapped(), "trap effect must not disappear under optimization");
    }
}
