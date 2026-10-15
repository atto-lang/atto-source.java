package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.VerifiedSccAdmission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class VerifiedSccAdmissionTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "strict");

    private static Ast.FunctionDecl function(String name, Ast.TypeRef returnType,
                                             List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(name, Ast.Visibility.PUBLIC, false,
                List.of(), List.of(), returnType, List.of(), body);
    }

    private static Ast.Program source(String declaredName, String importedName,
                                      String importedFrom, Ast.TypeRef declaredType) {
        var call = new Ast.CallExpr(new Ast.NameExpr(importedName), List.of());
        var entry = function(declaredName, declaredType, List.of(new Ast.ReturnStmt(call)));
        var imported = new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of(importedName), false, null, importedFrom);
        return new Ast.Program(null, List.of(imported),
                List.of(new Ast.ModuleDecl("api", List.of(entry))));
    }

    private static Map<String, Ast.Program> cyclicPair() {
        return Map.of(
            "a.ores", source("left", "right", "./b.ores", Ast.TypeRef.simple("int")),
            "b.ores", source("right", "left", "./a.ores", Ast.TypeRef.simple("int")));
    }

    private static Map<String, ExportInterfaceCodec.Header> headers() {
        return Map.of("a.ores", ENV, "b.ores", ENV);
    }

    @Test void verifiesRealCrossFileCallsAcrossCompleteCycle() {
        var result = VerifiedSccAdmission.verifySingleScc(cyclicPair(), headers(), Map.of());
        assertEquals(List.of("a.ores", "b.ores"), result.units());
        assertEquals(2, result.checkedPrograms().size());
        assertEquals(64, result.interfaceFingerprint().length());
        assertThrows(UnsupportedOperationException.class,
                () -> result.checkedPrograms().clear());
    }

    @Test void checkerCatchesImportedReturnTypeMismatchInsteadOfUnknown() {
        var changed = new LinkedHashMap<>(cyclicPair());
        var stringFunction = function("right", Ast.TypeRef.simple("String"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr("bad"))));
        var old = changed.get("b.ores");
        changed.put("b.ores", new Ast.Program(old.namespace(), old.imports(),
                List.of(new Ast.ModuleDecl("api", List.of(stringFunction)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifySingleScc(changed, headers(), Map.of()),
                "int-returning left must not accept imported right():String");
    }

    @Test void invalidMemberPreventsAnySccAdmission() {
        var broken = new LinkedHashMap<>(cyclicPair());
        var badFn = function("right", Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr("invalid"))));
        var old = broken.get("b.ores");
        broken.put("b.ores", new Ast.Program(old.namespace(), old.imports(),
                List.of(new Ast.ModuleDecl("api", List.of(badFn)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifyGraph(broken, headers(), Map.of()));
    }

    @Test void linkerRejectsUnsupportedNominalImportedSignatures() {
        var advanced = new LinkedHashMap<>(cyclicPair());
        var incompatible = function("right", Ast.TypeRef.simple("CustomNominal"), List.of());
        var old = advanced.get("b.ores");
        advanced.put("b.ores", new Ast.Program(old.namespace(), old.imports(),
                List.of(new Ast.ModuleDecl("api", List.of(incompatible)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifyGraph(advanced, headers(), Map.of()));
    }

    @Test void verifiedProofTypesCannotBeCreatedByCallers() {
        assertFalse(VerifiedSccAdmission.VerifiedScc.class.isRecord());
        assertFalse(VerifiedSccAdmission.VerifiedGraph.class.isRecord());
        for(var type:List.of(VerifiedSccAdmission.VerifiedScc.class,
                              VerifiedSccAdmission.VerifiedGraph.class)) {
            for(var constructor:type.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        }
    }

    @Test void inputOrderingCannotChangeCycleIdentity() {
        var first = VerifiedSccAdmission.verifyGraph(cyclicPair(), headers(), Map.of());
        var order = new LinkedHashMap<String,Ast.Program>();
        order.put("b.ores", cyclicPair().get("b.ores"));
        order.put("a.ores", cyclicPair().get("a.ores"));
        var second = VerifiedSccAdmission.verifyGraph(order, headers(), Map.of());
        assertEquals(first.interfacePlan().unitFingerprint(),
                second.interfacePlan().unitFingerprint());
    }
    @Test void realCheckerRejectsWrongArgumentTypeAtImportedFunctionBoundary() {
        var provider = new Ast.FunctionDecl("right", Ast.Visibility.PUBLIC, false,
                List.of(), List.of(new Ast.Param(Ast.TypeRef.simple("int"), "value")),
                Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("value"))));
        var providerImports = new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("left"), false, null, "./a.ores");
        var providerSource = new Ast.Program(null, List.of(providerImports),
                List.of(new Ast.ModuleDecl("api", List.of(provider))));
        var badCall = new Ast.CallExpr(new Ast.NameExpr("right"),
                List.of(new Ast.LiteralExpr("wrong-argument-type")));
        var consumerFn = function("left", Ast.TypeRef.simple("int"),
                List.of(new Ast.ReturnStmt(badCall)));
        var consumerImports = new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("right"), false, null, "./b.ores");
        var consumerSource = new Ast.Program(null, List.of(consumerImports),
                List.of(new Ast.ModuleDecl("api", List.of(consumerFn))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifySingleScc(
                        Map.of("a.ores", consumerSource, "b.ores", providerSource),
                        headers(), Map.of()));
    }

    @Test void unsupportedEffectfulFunctionCannotReceiveCrossFileCertificate() {
        var source = cyclicPair();
        var changed = new LinkedHashMap<>(source);
        var effects = new Ast.FunctionDecl("right", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, true, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of(), List.of(),
                Ast.TypeRef.simple("int"), List.of(), List.of());
        var existing = changed.get("b.ores");
        changed.put("b.ores", new Ast.Program(existing.namespace(),
                existing.imports(), List.of(new Ast.ModuleDecl("api", List.of(effects)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifyGraph(changed, headers(), Map.of()));
    }

    @Test void publicAliasOrModuleContractsCannotEscapeRestrictedSccVerification() {
        var original = cyclicPair();
        var publicAlias = new Ast.TypeAliasDecl("PublicAlias", Ast.Visibility.PUBLIC,
                List.of(), Ast.TypeRef.simple("int"));
        var changed = new LinkedHashMap<>(original);
        var a = changed.get("a.ores");
        var function = a.modules().getFirst().declarations().getFirst();
        changed.put("a.ores", new Ast.Program(a.namespace(), a.imports(),
                List.of(new Ast.ModuleDecl("api", List.of(function, publicAlias)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifySingleScc(changed, headers(), Map.of()));

        var withContract = new LinkedHashMap<>(original);
        withContract.put("a.ores", new Ast.Program(a.namespace(), a.imports(),
                List.of(new Ast.ModuleDecl("api", List.of(),
                        List.of(Ast.TypeRef.simple("UnlinkedContract")),
                        List.of(function)))));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedSccAdmission.verifySingleScc(withContract, headers(), Map.of()));
    }


}
