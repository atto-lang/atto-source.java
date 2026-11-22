package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.CanonicalTypeIdentity;
import dev.oreslang.compiler.CheckedPublicTypeIdentity;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class CheckedPublicTypeIdentityTest {
    private static final ExportInterfaceCodec.Header ENV =
        new ExportInterfaceCodec.Header("", "compiler-test", "native-test", "strict");

    private static Ast.Program program(Ast.Decl... entries) {
        return new Ast.Program(List.of(new Ast.ModuleDecl("api",List.of(entries))));
    }

    private static Ast.FunctionDecl fn(String name, Ast.Visibility visibility, Ast.TypeRef result) {
        return new Ast.FunctionDecl(name, visibility, false, List.of(),List.of(),
                result,List.of(),List.of());
    }

    @Test void typeCheckerOwnsPublicNominalOriginSnapshot() {
        var checked=TypeChecker.checkTypesAndSnapshotPublicNominals(program(
            new Ast.ClassDecl("Box",Ast.Visibility.PUBLIC,false,Ast.ActorKind.NONE,
                List.of(),List.of(),List.of(),List.of(),null,List.of())));
        assertEquals("api",checked.resolvedNames().get("api.Box").module());
        assertEquals("Box",checked.resolvedNames().get("Box").symbol());
        assertThrows(UnsupportedOperationException.class,()->checked.resolvedNames().clear());
    }

    @Test void primitiveReturnTypeIdentityIsProducedAfterAdmission() {
        var source=program(fn("ok",Ast.Visibility.PUBLIC,Ast.TypeRef.simple("void")));
        var checked=CheckedPublicTypeIdentity.publicFunctionReturnType(
            source,"app.ores",ENV,"api","ok",0);
        var expected=CanonicalTypeIdentity.of(Ast.TypeRef.simple("void"),Map.of(),List.of());
        assertEquals(expected.sha256(),checked.sha256());
    }

    @Test void missingPrivateAndUnresolvedTypeSelectorsFailClosed() {
        var source=program(fn("secret",Ast.Visibility.PRIVATE,Ast.TypeRef.simple("void")));
        assertThrows(IllegalArgumentException.class,()->
            CheckedPublicTypeIdentity.publicFunctionReturnType(
                source,"app.ores",ENV,"api","secret",0));
        assertThrows(IllegalArgumentException.class,()->
            CheckedPublicTypeIdentity.publicFunctionReturnType(
                source,"app.ores",ENV,"api","missing",0));
    }

    @Test void importsCannotBypassTrustedCrossFileResolution() {
        var imported=new Ast.ImportDecl(Ast.ImportKind.FUNCTION,
            List.of("remote"),false,null,"vendor/pkg");
        var source=new Ast.Program(List.of(imported),
            List.of(new Ast.ModuleDecl("api",List.of(
                fn("ok",Ast.Visibility.PUBLIC,Ast.TypeRef.simple("void"))))));
        assertThrows(IllegalArgumentException.class,()->
            TypeChecker.checkTypesAndSnapshotPublicNominals(source));
    }
}
