package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.TwoPhaseInterfaceLinker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TwoPhaseInterfaceLinkerTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "safe");

    private static Ast.ImportDecl use(String path, String name) {
        return new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of(name), false, null, path);
    }

    private static Ast.Program source(String declaredName,
                                      Ast.Visibility visibility, Ast.ImportDecl... imports) {
        var function = new Ast.FunctionDecl(declaredName, visibility, false,
                List.of(), List.of(), Ast.TypeRef.simple("int"),
                List.of(), List.of());
        return new Ast.Program(null, List.of(imports),
                List.of(new Ast.ModuleDecl("api", List.of(function))));
    }

    private static Map<String, Ast.Program> cycle() {
        return Map.of("a.ores", source("work", Ast.Visibility.PUBLIC, use("./b.ores","work")),
                      "b.ores", source("work", Ast.Visibility.PUBLIC, use("./a.ores","work")));
    }

    private static Map<String, ExportInterfaceCodec.Header> environments() {
        return Map.of("a.ores", ENV, "b.ores", ENV);
    }

    private static Map<String, Map<String, String>> noExternal() {
        return Map.of("a.ores", Map.<String,String>of(),
                      "b.ores", Map.<String,String>of());
    }

    @Test void publicFunctionCycleLinksAfterDeclarationsAreCollected() {
        var declarations = TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                cycle(), environments());
        assertEquals(Set.of("a.ores", "b.ores"), declarations.unitIds());
        var linked = TwoPhaseInterfaceLinker.resolveLinkedImports(
                declarations, Map.of(), noExternal());
        assertEquals(List.of("a.ores","b.ores"), linked.plan().groups().getFirst().units());
        assertThrows(UnsupportedOperationException.class,
                () -> declarations.interfaces().clear());
    }

    @Test void changedPublicFunctionInvalidatesEveryCycleMember() {
        var original = TwoPhaseInterfaceLinker.resolveLinkedImports(
                TwoPhaseInterfaceLinker.collectCheckedDeclarations(cycle(), environments()),
                Map.of(), noExternal());
        var modified = new LinkedHashMap<>(cycle());
        var replacement = new Ast.FunctionDecl("work",Ast.Visibility.PUBLIC,false,
                List.of(),List.of(),Ast.TypeRef.simple("String"),List.of(),List.of());
        modified.put("b.ores",new Ast.Program(List.of(use("./a.ores","work")),
                List.of(new Ast.ModuleDecl("api", List.of(replacement)))));
        var changed = TwoPhaseInterfaceLinker.resolveLinkedImports(
                TwoPhaseInterfaceLinker.collectCheckedDeclarations(modified, environments()),
                Map.of(), noExternal());
        assertNotEquals(original.plan().unitFingerprint().get("a.ores"),
                changed.plan().unitFingerprint().get("a.ores"));
        assertNotEquals(original.plan().unitFingerprint().get("b.ores"),
                changed.plan().unitFingerprint().get("b.ores"));
    }

    @Test void stageTwoDoesNotGrantPrivateOrUnverifiedImports() {
        var missing = new LinkedHashMap<>(cycle());
        missing.put("b.ores", source("work",Ast.Visibility.PRIVATE, use("./a.ores","work")));
        var declarations = TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                missing,environments());
        assertThrows(IllegalArgumentException.class,
                () -> TwoPhaseInterfaceLinker.resolveLinkedImports(
                        declarations,Map.of(),noExternal()));

        var wildcard = new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of(),true,null,"./b.ores");
        var withWildcard = new LinkedHashMap<>(cycle());
        withWildcard.put("a.ores",source("work",Ast.Visibility.PUBLIC,wildcard));
        assertThrows(IllegalArgumentException.class,
                () -> TwoPhaseInterfaceLinker.resolveLinkedImports(
                        TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                                withWildcard,environments()),Map.of(),noExternal()));
    }

    @Test void rejectUnknownProviderAndPathAliasBeforePhaseTwo() {
        var invalid = new LinkedHashMap<>(cycle());
        invalid.put("a.ores",source("work",Ast.Visibility.PUBLIC,
                use("./nonexistent.ores","work")));
        assertThrows(IllegalArgumentException.class,
                () -> TwoPhaseInterfaceLinker.resolveLinkedImports(
                        TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                                invalid,environments()),Map.of(),noExternal()));

        var aliased = Map.of("dir/../a.ores",cycle().get("a.ores"),
                "b.ores",cycle().get("b.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                        aliased,Map.of("dir/../a.ores",ENV,"b.ores",ENV)));
    }
}
