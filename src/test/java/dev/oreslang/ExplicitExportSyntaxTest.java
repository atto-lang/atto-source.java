package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitExportSyntaxTest {

    private static Ast.ModuleDecl root(Ast.Program program) {
        return program.modules().stream()
                .filter(m -> m.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void exportMetadataIsTransparentToImportPrelude() {
        Ast.Program program = Parser.parse("""
                export class Box;
                import type Id from './dep';
                export class Box as PublicBox;

                define class Box as
                end
                """);

        assertEquals(1, program.imports().size());
        assertEquals(2, root(program).exports().size());
        assertEquals(Ast.ExportKind.CLASS, root(program).exports().getFirst().kind());
        assertEquals("PublicBox", root(program).exports().get(1).items().getFirst().externalName());
    }

    @Test
    void exportAfterRealDeclarationDoesNotReopenImportPrelude() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                        end
                        export class Box;
                        import type Id from './dep';
                        """));
        assertTrue(failure.getMessage().contains("EXPORT_IMPORT_PRELUDE"), failure.getMessage());
    }

    @Test
    void groupedAliasesScopesAndTrailingCommaArePreserved() {
        Ast.ExportDecl export = root(Parser.parse("""
                export(package) class {A, B as PublicB,};
                define class A as
                end
                define class B as
                end
                """)).exports().getFirst();

        assertEquals(Ast.ExportScope.PACKAGE, export.scope());
        assertEquals(Ast.ExportKind.CLASS, export.kind());
        assertEquals(List.of(
                        new Ast.ExportItem("A", "A", false, false),
                        new Ast.ExportItem("B", "PublicB", false, false)),
                export.items());
    }

    @Test
    void symbolSelfIdentityIsParsedButNotRenamed() {
        Ast.ExportItem item = root(Parser.parse("""
                export symbol #ready as #ready;
                fnc main(): void { return; }
                """)).exports().getFirst().items().getFirst();

        assertEquals("#ready", item.localName());
        assertEquals("#ready", item.externalName());
        assertTrue(item.symbol());
        assertTrue(item.selfIdentity());
    }

    @Test
    void allCanonicalPositiveSyntaxVectorsParse() {
        assertExportParses("export X;");
        assertExportParses("export[pkg] actor Counter;");
        assertExportParses("export(package) class {X, Y as Z,};");
        assertExportParses("export {X, Y as Z, #ready,};");
        assertExportParses("export symbol {#ready, #failed,};");
        assertExportParses("export class {X};");
        assertExportParses("export symbol #ready as #ready;");
    }

    @Test
    void allCanonicalNegativeSyntaxVectorsUseStableCategories() {
        assertSyntaxError("export X", "EXPORT_SEMICOLON_REQUIRED");
        assertSyntaxError("export {};", "EXPORT_EMPTY_GROUP");
        assertSyntaxError("export class {};", "EXPORT_EMPTY_GROUP");
        assertSyntaxError("export actor {,X};", "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {X,,Y};", "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {X Y};", "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {{X}};", "EXPORT_NESTED_GROUP");
        assertSyntaxError("export *;", "EXPORT_WILDCARD_UNSUPPORTED");
        assertSyntaxError("export * from \"./dep\";", "EXPORT_REEXPORT_UNSUPPORTED");
        assertSyntaxError("export from \"./dep\" {X};", "EXPORT_REEXPORT_UNSUPPORTED");
        assertSyntaxError("export dep.X;", "EXPORT_QUALIFIED_TARGET_UNSUPPORTED");
        assertSyntaxError("export dep::X;", "EXPORT_QUALIFIED_TARGET_UNSUPPORTED");
        assertSyntaxError("export Foo<int>;", "EXPORT_GENERIC_TARGET_UNSUPPORTED");
        assertSyntaxError("export foo(int);", "EXPORT_CALLABLE_SIGNATURE_UNSUPPORTED");
        assertSyntaxError("export {class X, actor Y};", "EXPORT_KIND_PREFIX_IN_GROUP");
        assertSyntaxError("export actor {actor X};", "EXPORT_KIND_PREFIX_IN_GROUP");
        assertSyntaxError("export symbol #ready as Ready;", "EXPORT_SYMBOL_BINDING_ALIAS_UNSUPPORTED");
        assertSyntaxError("export symbol #ready as #ok;", "EXPORT_SYMBOL_IDENTITY_MISMATCH");
        assertSyntaxError("export[pkg][file] X;", "EXPORT_MULTIPLE_SCOPES");
        assertSyntaxError("export[project] X;", "EXPORT_UNKNOWN_SCOPE");
        assertSyntaxError("export symbol ready as #ready;", "EXPORT_SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE");
        assertSyntaxError("export string ready as 'ready';", "EXPORT_UNKNOWN_DECLARATION_KIND");
        assertSyntaxError("export const ready as 'ready';", "EXPORT_LITERAL_ALIAS_UNSUPPORTED");
        assertSyntaxError("export class Foo as \"foo\";", "EXPORT_LITERAL_ALIAS_UNSUPPORTED");
    }

    @Test
    void forwardExportResolvesAfterCompleteLocalCollection() {
        ExportResolver.Result result = ExportResolver.resolve(Parser.parse("""
                export class Box as PublicBox;

                define class Box as
                end
                """));

        assertEquals(1, result.bindings().size());
        ExportResolver.ExportBinding binding = result.bindings().getFirst();
        assertEquals("PublicBox", binding.externalName());
        assertEquals(Ast.ExportKind.CLASS, binding.localKind());
        assertEquals(ExportResolver.Origin.EXPLICIT_EXPORT, binding.origin());
    }

    @Test
    void inlinePubAndExplicitAliasProduceTwoBindings() {
        ExportResolver.Result result = ExportResolver.resolve(Parser.parse("""
                export class Box as PublicBox;

                pub define class Box as
                end
                """));

        assertEquals(
                List.of("Box", "PublicBox"),
                result.bindings().stream().map(ExportResolver.ExportBinding::externalName).sorted().toList());
    }

    @Test
    void typedKindMismatchAndAliasCollisionReject() {
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export actor Box;
                        define class Box as
                        end
                        """)));
        assertTrue(mismatch.getMessage().contains("EXPORT_KIND_MISMATCH"), mismatch.getMessage());

        IllegalArgumentException collision = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export class A as Public;
                        export class B as Public;
                        define class A as
                        end
                        define class B as
                        end
                        """)));
        assertTrue(collision.getMessage().contains("EXPORT_ALIAS_COLLISION"), collision.getMessage());
    }

    @Test
    void importedBindingCannotPassThroughLocalExportForm() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export X;
                        import class X from './dep';
                        fnc main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_IMPORTED_NAME"), failure.getMessage());
    }

    @Test
    void overloadFamilyGetsOneStableLogicalIdentity() {
        ExportResolver.Result result = ExportResolver.resolve(Parser.parse("""
                export fnc work;

                fnc work(): int { return 1; }
                fnc work(int value): int { return value; }
                """));

        assertEquals(1, result.bindings().size());
        assertTrue(result.bindings().getFirst().localSymbolId().endsWith("::function::work"));
    }

    @Test
    void untypedSameSpellingAcrossNamespacesIsAmbiguous() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export X;

                        define type X = int;
                        const int X = 1;
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_AMBIGUOUS_TARGET"), failure.getMessage());
    }

    @Test
    void duplicateIdenticalExportIsIdempotentAndFileScopeIsPreserved() {
        ExportResolver.Result duplicate = ExportResolver.resolve(Parser.parse("""
                export class Box;
                export class Box;
                define class Box as
                end
                """));
        assertEquals(1, duplicate.bindings().size());

        ExportResolver.Result fileScoped = ExportResolver.resolve(Parser.parse("""
                export[file] type Local;
                define type Local = int;
                """));
        assertEquals(Ast.ExportScope.FILE, fileScoped.bindings().getFirst().scope());
    }

    @Test
    void unknownAndUntypedTargetsUseCanonicalSemanticDiagnostics() {
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export class Missing;
                        fnc main(): void { return; }
                        """)));
        assertTrue(unknown.getMessage().contains("EXPORT_UNKNOWN_TARGET"), unknown.getMessage());

        ExportResolver.Result untyped = ExportResolver.resolve(Parser.parse("""
                export Count;
                define type Count = int;
                """));
        assertEquals(Ast.ExportKind.TYPE, untyped.bindings().getFirst().localKind());
    }

    @Test
    void sameSpellingDistinctSymbolsCannotShareAlias() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export type Thing as Shared;
                        export const Thing as Shared;
                        define type Thing = int;
                        const int Thing = 1;
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_ALIAS_COLLISION"), failure.getMessage());
    }

    @Test
    void publicApiCannotLeakPrivateLocalType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export fnc reveal;

                        define type Internal = int;
                        fnc reveal(Internal value): int { return 1; }
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_API_LEAK"), failure.getMessage());
    }

    @Test
    void publicInlineDeclarationCannotBeNarrowedByExplicitExport() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export[pkg] class Box;

                        pub define class Box as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_SCOPE_CONFLICT"), failure.getMessage());
    }

    @Test
    void moduleExportsHaveTheirOwnNamespace() {
        Ast.Program program = Parser.parse("""
                define module Models as
                  export class Box as PublicBox;
                  define class Box as
                  end
                end
                """);

        Ast.ModuleDecl models = program.modules().stream().filter(m -> m.name().equals("Models")).findFirst().orElseThrow();
        assertEquals(1, models.exports().size());

        ExportResolver.Result result = ExportResolver.resolve(program);
        assertEquals(1, result.bindings().size());
        assertEquals("Models", result.bindings().getFirst().ownerNamespaceId());
        assertEquals("PublicBox", result.bindings().getFirst().externalName());
    }

    @Test
    void sourceExportDoesNotImplyNativeOrHostExportMetadata() {
        ExportResolver.ExportBinding binding = ExportResolver.resolve(Parser.parse("""
                export class Box;
                define class Box as
                end
                """)).bindings().getFirst();

        assertEquals(Ast.ExportScope.PUBLIC, binding.scope());
        assertEquals(Ast.ExportKind.CLASS, binding.localKind());
        // ExportBinding deliberately contains no JVM class name, native symbol,
        // Wasm export index, CLR visibility, or host-interop flag.
    }

    @Test
    void publicExplicitExportIsATreeShakerLibraryRoot() {
        var result = OresCompiler.compileForBuild("""
                export class Box;

                define class Box as
                end

                fnc unused(): int { return 1; }
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retainedSymbols().contains(Parser.ROOT_MODULE + ".Box"));
        assertFalse(result.retainedSymbols().contains(Parser.ROOT_MODULE + ".unused"));
        assertEquals(1, root(result.program()).exports().size());
    }

    @Test
    void symbolSyntaxFailsClosedUntilJavaHasSymbolDeclarationAst() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export symbol #ready;
                        fnc main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_SYMBOL_DECLARATION_UNAVAILABLE"), failure.getMessage());
    }

    private static void assertExportParses(String exportSource) {
        assertDoesNotThrow(() -> Parser.parse(exportSource + "\nfnc main(): void { return; }"));
    }

    private static void assertSyntaxError(String exportSource, String category) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(exportSource + "\nfnc main(): void { return; }"));
        assertTrue(failure.getMessage().contains(category), failure.getMessage());
    }
}
