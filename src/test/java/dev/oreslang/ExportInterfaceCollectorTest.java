package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ExportInterfaceCollectorTest {
    private static final ExportInterfaceCodec.Header HEADER =
            new ExportInterfaceCodec.Header("", "test-compiler", "test-abi", "safe");

    private static Ast.FunctionDecl fn(String name, String param, Ast.Visibility visibility) {
        return new Ast.FunctionDecl(name, visibility, false, List.of(),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), param)),
                Ast.TypeRef.simple("int"), List.of(), List.of());
    }

    private static Ast.Program source(Ast.Decl... declarations) {
        return new Ast.Program(List.of(new Ast.ModuleDecl("api", List.of(declarations))));
    }

    @Test void publicDeclarationsStableAcrossSourceOrder() {
        var a = ExportInterfaceCollector.collect(source(
                fn("one", "first", Ast.Visibility.PUBLIC),
                fn("two", "second", Ast.Visibility.PUBLIC)), HEADER);
        var b = ExportInterfaceCollector.collect(source(
                fn("two", "second", Ast.Visibility.PUBLIC),
                fn("one", "first", Ast.Visibility.PUBLIC)), HEADER);
        assertEquals(a.sha256(), b.sha256());
        assertArrayEquals(a.bytes(), b.bytes());
    }

    @Test void privateFunctionDoesNotChangeExportedAbi() {
        var base = ExportInterfaceCollector.collect(
                source(fn("publicFn", "arg", Ast.Visibility.PUBLIC)), HEADER);
        var withPrivate = ExportInterfaceCollector.collect(source(
                fn("publicFn", "arg", Ast.Visibility.PUBLIC),
                fn("secret", "hidden", Ast.Visibility.PRIVATE)), HEADER);
        assertEquals(base.sha256(), withPrivate.sha256());
    }

    @Test void exportedNamedLabelsAndTargetAreFingerprintInputs() {
        var a = ExportInterfaceCollector.collect(
                source(fn("one", "before", Ast.Visibility.PUBLIC)), HEADER);
        var b = ExportInterfaceCollector.collect(
                source(fn("one", "after", Ast.Visibility.PUBLIC)), HEADER);
        assertNotEquals(a.sha256(), b.sha256());
        assertNotEquals(a.sha256(), ExportInterfaceCollector.collect(
                source(fn("one", "before", Ast.Visibility.PUBLIC)),
                new ExportInterfaceCodec.Header("", "test-compiler", "arm64", "safe")).sha256());
    }

    @Test void unresolvedOrPrivateLayoutMustNotBecomeTrustedInterface() {
        var privateField = new Ast.FieldDecl("hidden", Ast.Visibility.PRIVATE,
                Ast.BindingKind.VAL, Ast.TypeRef.simple("int"), null);
        var klass = new Ast.ClassDecl("Thing", Ast.Visibility.PUBLIC, false,
                Ast.ActorKind.NONE, List.of(), List.of(), List.of(),
                List.of(privateField), null, List.of());
        assertThrows(IllegalArgumentException.class, () ->
                ExportInterfaceCollector.collect(source(klass), HEADER));
        var incomplete = new Ast.FunctionDecl("bad", Ast.Visibility.PUBLIC, false,
                List.of(), List.of(), null, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () ->
                ExportInterfaceCollector.collect(source(incomplete), HEADER));
    }
}
