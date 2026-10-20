package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.BuildTreeShaker;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class BuildTreeShakerTest {
    private static Ast.FunctionDecl function(
            String name,
            Ast.Visibility visibility,
            Ast.TypeRef returnType,
            List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                name,
                Ast.CallableKind.FNC,
                visibility,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                returnType,
                List.of(),
                body);
    }

    private static Ast.FunctionDecl routine(
            String name,
            Ast.Visibility visibility,
            List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                name,
                Ast.CallableKind.ROUTINE,
                visibility,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                body);
    }

    private static Ast.ImportDecl importFunction(String name, String path) {
        return new Ast.ImportDecl(
                Ast.ImportKind.FUNCTION,
                List.of(name),
                false,
                null,
                path);
    }

    private static Ast.Program program(
            List<Ast.ImportDecl> imports,
            Ast.Decl... declarations) {
        return new Ast.Program(
                imports,
                List.of(new Ast.ModuleDecl(
                        Parser.ROOT_MODULE,
                        List.of(declarations))));
    }

    private static IncrementalCompiler.CompiledUnit unit(
            String id,
            Ast.Program program,
            Set<String> dependencies) {
        return new IncrementalCompiler.CompiledUnit(
                id,
                id,
                program.namespace(),
                "source-" + id,
                "abi-" + id,
                dependencies,
                "// synthetic checked unit " + id,
                program);
    }

    private static IncrementalCompiler.BuildResult build(
            IncrementalCompiler.CompiledUnit... units) {
        java.util.LinkedHashMap<String, IncrementalCompiler.CompiledUnit> map =
                new java.util.LinkedHashMap<>();
        for (IncrementalCompiler.CompiledUnit unit : units) {
            map.put(unit.unitId(), unit);
        }
        return new IncrementalCompiler.BuildResult(
                map,
                map.keySet(),
                Set.of(),
                map.keySet().stream().map(List::of).toList());
    }

    @Test
    void removesUnusedImportedUnitAndKeepsUsedNamedImport() {
        Ast.ImportDecl liveImport = importFunction("live", "./live.ores");
        Ast.ImportDecl deadImport = importFunction("dead", "./dead.ores");
        Ast.FunctionDecl main = routine(
                "main",
                Ast.Visibility.PUBLIC,
                List.of(new Ast.ExprStmt(
                        new Ast.CallExpr(new Ast.NameExpr("live"), List.of()))));

        IncrementalCompiler.CompiledUnit app = unit(
                "app.ores",
                program(List.of(liveImport, deadImport), main),
                Set.of("live.ores", "dead.ores"));
        IncrementalCompiler.CompiledUnit live = unit(
                "live.ores",
                program(
                        List.of(),
                        function(
                                "live",
                                Ast.Visibility.PUBLIC,
                                Ast.TypeRef.simple("int"),
                                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(1L))))),
                Set.of());
        IncrementalCompiler.CompiledUnit dead = unit(
                "dead.ores",
                program(
                        List.of(),
                        function(
                                "dead",
                                Ast.Visibility.PUBLIC,
                                Ast.TypeRef.simple("int"),
                                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(2L))))),
                Set.of());

        BuildTreeShaker.Result result = BuildTreeShaker.shake(
                build(app, live, dead),
                Map.of(
                        "app.ores",
                        Map.of(
                                "./live.ores", "live.ores",
                                "./dead.ores", "dead.ores")),
                "app.ores",
                BuildOptions.executable(Map.of()));

        assertEquals(Set.of("app.ores", "live.ores"), result.build().units().keySet());
        assertEquals(Set.of("dead.ores"), result.removedUnits());
        assertEquals(List.of(liveImport), result.build().units().get("app.ores").program().imports());
        assertTrue(result.manifests().get("live.ores")
                .retainedSymbols().contains(Parser.ROOT_MODULE + ".live"));
    }

    @Test
    void lifecycleOnlyImportKeepsInitButStillDropsUnusedPublicApi() {
        Ast.ImportDecl sideImport = importFunction("unused_api", "./side.ores");
        Ast.FunctionDecl main = routine("main", Ast.Visibility.PUBLIC, List.of());
        Ast.FunctionDecl init = routine(
                "init",
                Ast.Visibility.PRIVATE,
                List.of(new Ast.ExprStmt(new Ast.LiteralExpr(1L))));
        Ast.FunctionDecl unusedApi = function(
                "unused_api",
                Ast.Visibility.PUBLIC,
                Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(7L))));

        IncrementalCompiler.CompiledUnit app = unit(
                "app.ores",
                program(List.of(sideImport), main),
                Set.of("side.ores"));
        IncrementalCompiler.CompiledUnit side = unit(
                "side.ores",
                program(List.of(), init, unusedApi),
                Set.of());

        BuildTreeShaker.Result result = BuildTreeShaker.shake(
                build(app, side),
                Map.of("app.ores", Map.of("./side.ores", "side.ores")),
                "app.ores",
                BuildOptions.executable(Map.of()));

        assertEquals(Set.of("app.ores", "side.ores"), result.build().units().keySet());
        assertEquals(List.of(sideImport), result.build().units().get("app.ores").program().imports());
        assertTrue(result.manifests().get("side.ores")
                .retainedSymbols().contains(Parser.ROOT_MODULE + ".init"));
        assertTrue(result.manifests().get("side.ores")
                .removedSymbols().contains(Parser.ROOT_MODULE + ".unused_api"));
    }

    @Test
    void transitiveInitializationKeepsBridgeWithoutKeepingItsUnusedApi() {
        Ast.ImportDecl bridgeImport = importFunction("bridge_api", "./bridge.ores");
        Ast.ImportDecl sideImport = importFunction("side_api", "./side.ores");

        IncrementalCompiler.CompiledUnit app = unit(
                "app.ores",
                program(
                        List.of(bridgeImport),
                        routine("main", Ast.Visibility.PUBLIC, List.of())),
                Set.of("bridge.ores"));

        IncrementalCompiler.CompiledUnit bridge = unit(
                "bridge.ores",
                program(
                        List.of(sideImport),
                        function(
                                "bridge_api",
                                Ast.Visibility.PUBLIC,
                                Ast.TypeRef.simple("int"),
                                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(3L))))),
                Set.of("side.ores"));

        IncrementalCompiler.CompiledUnit side = unit(
                "side.ores",
                program(
                        List.of(),
                        routine(
                                "init",
                                Ast.Visibility.PRIVATE,
                                List.of(new Ast.ExprStmt(new Ast.LiteralExpr(1L)))),
                        function(
                                "side_api",
                                Ast.Visibility.PUBLIC,
                                Ast.TypeRef.simple("int"),
                                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(4L))))),
                Set.of());

        BuildTreeShaker.Result result = BuildTreeShaker.shake(
                build(app, bridge, side),
                Map.of(
                        "app.ores", Map.of("./bridge.ores", "bridge.ores"),
                        "bridge.ores", Map.of("./side.ores", "side.ores")),
                "app.ores",
                BuildOptions.executable(Map.of()));

        assertEquals(
                Set.of("app.ores", "bridge.ores", "side.ores"),
                result.build().units().keySet());
        assertTrue(result.manifests().get("bridge.ores")
                .removedSymbols().contains(Parser.ROOT_MODULE + ".bridge_api"));
        assertTrue(result.manifests().get("side.ores")
                .retainedSymbols().contains(Parser.ROOT_MODULE + ".init"));
        assertTrue(result.manifests().get("side.ores")
                .removedSymbols().contains(Parser.ROOT_MODULE + ".side_api"));
    }

    @Test
    void unresolvedExternalOresImportIsPreservedFailClosed() {
        Ast.ImportDecl external = importFunction("remote_api", "some-package");
        IncrementalCompiler.CompiledUnit app = unit(
                "app.ores",
                program(
                        List.of(external),
                        routine("main", Ast.Visibility.PUBLIC, List.of())),
                Set.of());

        BuildTreeShaker.Result result = BuildTreeShaker.shake(
                build(app),
                Map.of(),
                "app.ores",
                BuildOptions.executable(Map.of()));

        assertEquals(
                List.of(external),
                result.build().units().get("app.ores").program().imports());
        assertTrue(result.removedUnits().isEmpty());
    }
}
