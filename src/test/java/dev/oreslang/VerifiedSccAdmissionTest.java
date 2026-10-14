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
}
