package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.AdmittedInterfacePlanner;
import dev.oreslang.compiler.CanonicalTypeIdentity;
import dev.oreslang.compiler.CheckedPublicTypeIdentity;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CompilerAdmissionTokenTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "safe");

    private static Ast.Program program(Ast.TypeRef result) {
        var run = new Ast.FunctionDecl("work", Ast.Visibility.PUBLIC, false,
                List.of(), List.of(), result, List.of(), List.of());
        return new Ast.Program(List.of(new Ast.ModuleDecl("main", List.of(run))));
    }

    @Test void checkedTokensCannotBePubliclyConstructedOrDecoded() {
        assertFalse(OresCompiler.CheckedProgram.class.isRecord());
        assertFalse(TypeChecker.CheckedPublicNominals.class.isRecord());
        for (var constructor : OresCompiler.CheckedProgram.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for (var constructor : TypeChecker.CheckedPublicNominals.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test void localAdmittedTypeIdentityEqualsCheckerApprovedPrimitive() {
        var admitted = OresCompiler.admitLocal(program(Ast.TypeRef.simple("void")));
        assertTrue(admitted.program().imports().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> admitted.warnings().clear());
        var actual = CheckedPublicTypeIdentity.publicFunctionReturnType(
                admitted, "main.ores", ENV, "main", "work", 0);
        var expected = CanonicalTypeIdentity.of(Ast.TypeRef.simple("void"),
                Map.of(), List.of());
        assertEquals(expected.sha256(), actual.sha256());
    }

    @Test void onlyCompilerIssuedTokensCanEnterLocalPlanner() {
        var token = OresCompiler.admitLocal(program(Ast.TypeRef.simple("void")));
        var plan = AdmittedInterfacePlanner.planLocal(
                Map.of("main.ores", token), Map.of("main.ores", ENV));
        assertEquals(1, plan.groups().size());
        assertEquals(1, plan.unitFingerprint().size());
        assertThrows(IllegalArgumentException.class,
                () -> AdmittedInterfacePlanner.planLocal(Map.of("main.ores", token),
                        Map.of()));
    }

    @Test void importingProgramCannotGetLocalAdmissionToken() {
        var imp = new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
                List.of("remote"), false, null, "external/provider");
        var ordinary = program(Ast.TypeRef.simple("void"));
        var imported = new Ast.Program(ordinary.namespace(),
                List.of(imp), ordinary.modules());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.admitLocal(imported));
    }
}
