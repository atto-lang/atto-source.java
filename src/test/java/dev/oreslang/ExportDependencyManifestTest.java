package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportDependencyManifest;
import dev.oreslang.compiler.ExportInterfaceCodec;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class ExportDependencyManifestTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final ExportInterfaceCodec.Header HEADER =
            new ExportInterfaceCodec.Header("", "compiler-1", "x86_64", "safe");

    private static Ast.ImportDecl imported(String... names) {
        return new Ast.ImportDecl(Ast.ImportKind.FUNCTION,List.of(names),
                false, null, "./provider.ores");
    }
    private static Ast.Program source(List<Ast.ImportDecl> imports) {
        var fn = new Ast.FunctionDecl("run", Ast.Visibility.PUBLIC, false, List.of(),
                List.of(), Ast.TypeRef.simple("int"), List.of(), List.of());
        return new Ast.Program(null, imports,
                List.of(new Ast.ModuleDecl("api", List.of(fn))));
    }
    private static ExportDependencyManifest.Binding b(Ast.ImportDecl imported,
                                                       String target, String digest) {
        return new ExportDependencyManifest.Binding(imported, target, "vendor", digest);
    }
    private static ExportDependencyManifest.Manifest collect(Ast.ImportDecl imp,
                                                              String target, String digest) {
        return ExportDependencyManifest.collect(source(List.of(imp)),
                "app.ores", HEADER, List.of(b(imp,target,digest)));
    }

    @Test void reorderingSymbolsIsStableButRetargetingSameImportPathIsNot() {
        var original = imported("foo","bar");
        var reverse = imported("bar","foo");
        var first = collect(original, "provider.ores", A);
        var reordered = collect(reverse, "provider.ores", A);
        assertArrayEquals(first.bytes(), reordered.bytes());
        assertEquals(first.sha256(), reordered.sha256());
        assertNotEquals(first.sha256(), collect(original, "new-provider.ores", A).sha256());
        assertNotEquals(first.sha256(), collect(original, "provider.ores", B).sha256());
    }

    @Test void decoderRejectsUnknownVersionAndTrailingData() {
        var valid = collect(imported("foo"), "provider.ores", A).bytes();
        assertArrayEquals(valid, ExportDependencyManifest.decode(valid).bytes());
        var modified = valid.clone();
        modified[6] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.decode(modified));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.decode(Arrays.copyOf(valid,valid.length+1)));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.decode(Arrays.copyOf(valid,valid.length-1)));
    }

    @Test void importedDeclarationBindingMustBeExact() {
        var one = imported("foo");
        var other = imported("bar");
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.collect(source(List.of(one)),
                    "app.ores",HEADER,List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.collect(source(List.of(one)),
                    "app.ores",HEADER,List.of(b(one,"provider.ores",A),b(other,"provider.ores",A))));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.collect(source(List.of(one)),
                    "app.ores",HEADER,List.of(b(one,"provider.ores",A),b(one,"other.ores",A))));
        assertThrows(IllegalArgumentException.class,
                () -> collect(one, "app.ores", A));
    }

    @Test void aKnownProviderMustMatchFingerprintAndEnvironment() {
        var imp = imported("foo");
        var providedHeader = new ExportInterfaceCodec.Header(
                "vendor", "compiler-1", "x86_64", "safe");
        var provided = ExportInterfaceCodec.encode(providedHeader,List.of(
                new ExportInterfaceCodec.Entry("vendor",ExportInterfaceCodec.Kind.FUNCTION,
                        "foo","(int)int")));
        var manifest = collect(imp,"provider.ores",provided.sha256());
        assertDoesNotThrow(() -> ExportDependencyManifest.verifyAgainstProviders(
                manifest,Map.of("provider.ores",provided)));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.verifyAgainstProviders(manifest, Map.of()));
        var changed = ExportInterfaceCodec.encode(providedHeader,List.of(
                new ExportInterfaceCodec.Entry("vendor",ExportInterfaceCodec.Kind.FUNCTION,
                        "foo","(String)int")));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.verifyAgainstProviders(
                        manifest,Map.of("provider.ores",changed)));
        assertThrows(IllegalArgumentException.class,
                () -> ExportDependencyManifest.verifyAgainstProviders(
                        collect(imported("missing"),"provider.ores",provided.sha256()),
                        Map.of("provider.ores",provided)));
    }
}
