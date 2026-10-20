package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TreeShakerReachabilityHardeningTest {
    private static Ast.FunctionDecl fn(
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

    private static Ast.ClassDecl privateClass(String name) {
        return new Ast.ClassDecl(name, false, List.of(), List.of(), List.of());
    }

    @Test
    void unqualifiedValueReferencePrefersItsLexicalModule() {
        Ast.FunctionDecl aHelper = fn(
                "helper", Ast.Visibility.PRIVATE, Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(1L))));
        Ast.FunctionDecl aEntry = fn(
                "entry", Ast.Visibility.PUBLIC, Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(
                        new Ast.CallExpr(new Ast.NameExpr("helper"), List.of()))));
        Ast.FunctionDecl bHelper = fn(
                "helper", Ast.Visibility.PRIVATE, Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(2L))));

        Ast.Program input = new Ast.Program(List.of(
                new Ast.ModuleDecl("A", List.of(aHelper, aEntry)),
                new Ast.ModuleDecl("B", List.of(bHelper))));

        TreeShaker.Result result = TreeShaker.shake(
                input,
                new BuildOptions(Map.of(), Set.of("A.entry"), false));

        assertTrue(result.retained("A.entry"));
        assertTrue(result.retained("A.helper"));
        assertTrue(result.removed("B.helper"));
    }

    @Test
    void unqualifiedTypeReferencePrefersItsLexicalModule() {
        Ast.FunctionDecl aEntry = fn(
                "entry", Ast.Visibility.PUBLIC, Ast.TypeRef.simple("Thing"),
                List.of());

        Ast.Program input = new Ast.Program(List.of(
                new Ast.ModuleDecl("A", List.of(privateClass("Thing"), aEntry)),
                new Ast.ModuleDecl("B", List.of(privateClass("Thing")))));

        TreeShaker.Result result = TreeShaker.shake(
                input,
                new BuildOptions(Map.of(), Set.of("A.entry"), false));

        assertTrue(result.retained("A.Thing"));
        assertTrue(result.removed("B.Thing"));
    }


    @Test
    void retainedOverloadFamilyScansDependenciesFromEveryArity() {
        Ast.FunctionDecl depOne = fn(
                "dep_one", Ast.Visibility.PRIVATE, Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(1L))));
        Ast.FunctionDecl depTwo = fn(
                "dep_two", Ast.Visibility.PRIVATE, Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(2L))));

        Ast.FunctionDecl pickOne = new Ast.FunctionDecl(
                "pick",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "a")),
                Ast.TypeRef.simple("int"),
                List.of(),
                List.of(new Ast.ReturnStmt(
                        new Ast.CallExpr(new Ast.NameExpr("dep_one"), List.of()))));
        Ast.FunctionDecl pickTwo = new Ast.FunctionDecl(
                "pick",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(
                        new Ast.Param(Ast.TypeRef.simple("int"), "a"),
                        new Ast.Param(Ast.TypeRef.simple("int"), "b")),
                Ast.TypeRef.simple("int"),
                List.of(),
                List.of(new Ast.ReturnStmt(
                        new Ast.CallExpr(new Ast.NameExpr("dep_two"), List.of()))));
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main",
                Ast.CallableKind.ROUTINE,
                Ast.Visibility.PUBLIC,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(new Ast.ExprStmt(
                        new Ast.CallExpr(
                                new Ast.NameExpr("pick"),
                                List.of(new Ast.LiteralExpr(7L))))));

        Ast.Program input = new Ast.Program(List.of(
                new Ast.ModuleDecl(
                        Parser.ROOT_MODULE,
                        List.of(depOne, depTwo, pickOne, pickTwo, main))));

        TreeShaker.Result result = TreeShaker.shake(input, BuildOptions.executable());

        assertTrue(result.retained(Parser.ROOT_MODULE + ".pick"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".dep_one"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".dep_two"),
                "family-level v1 retention must scan dependencies from every overload");
    }

    @Test
    void libraryBuildDropsPrivateTypeAliases() {
        Ast.Program input = new Ast.Program(List.of(
                new Ast.ModuleDecl(
                        Parser.ROOT_MODULE,
                        List.of(
                                new Ast.TypeAliasDecl(
                                        "PublicAlias",
                                        Ast.Visibility.PUBLIC,
                                        List.of(),
                                        Ast.TypeRef.simple("int")),
                                new Ast.TypeAliasDecl(
                                        "HiddenAlias",
                                        Ast.Visibility.PRIVATE,
                                        List.of(),
                                        Ast.TypeRef.simple("int"))))));

        TreeShaker.Result result = TreeShaker.shake(input, BuildOptions.library(Map.of()));

        assertTrue(result.retained(Parser.ROOT_MODULE + ".PublicAlias"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".HiddenAlias"));
    }

    @Test
    void anonymousWildcardImportIsKeptWhenAnExternalBindingSurvives() {
        Ast.ImportDecl wildcard = new Ast.ImportDecl(
                Ast.ImportKind.ALL,
                List.of(),
                true,
                null,
                "./dependency.ores");
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main",
                Ast.CallableKind.ROUTINE,
                Ast.Visibility.PUBLIC,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(new Ast.ExprStmt(new Ast.NameExpr("externalName"))));

        Ast.Program input = new Ast.Program(
                List.of(wildcard),
                List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE, List.of(main))));

        TreeShaker.Result result = TreeShaker.shake(input, BuildOptions.executable());

        assertEquals(List.of(wildcard), result.program().imports());
        assertTrue(result.manifest().externalBindings().contains("externalName"));
    }

    @Test
    void fieldAnnotationTypeDependenciesRemainReachable() {
        Ast.ClassDecl marker = privateClass("Marker");
        Ast.FieldDecl config = new Ast.FieldDecl(
                "config",
                Ast.Visibility.PRIVATE,
                Ast.BindingKind.CONST,
                Ast.TypeRef.simple("int"),
                List.of(new Ast.Annotation(
                        "KeepType",
                        List.of(Ast.TypeRef.simple("Marker")))),
                new Ast.LiteralExpr(1L),
                false);
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main",
                Ast.CallableKind.ROUTINE,
                Ast.Visibility.PUBLIC,
                false,
                Ast.ActorKind.NONE,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(new Ast.ExprStmt(new Ast.NameExpr("config"))));

        Ast.Program input = new Ast.Program(List.of(
                new Ast.ModuleDecl(Parser.ROOT_MODULE, List.of(marker, config, main))));

        TreeShaker.Result result = TreeShaker.shake(input, BuildOptions.executable());

        assertTrue(result.retained(Parser.ROOT_MODULE + ".config"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".Marker"));
    }
}
