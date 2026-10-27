package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.ExportAbiMetadata;
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
        assertSyntaxError("export symbol ready as 'ready';", "EXPORT_LITERAL_ALIAS_UNSUPPORTED");
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
    void contextualSymbolIdentifierIsNotStolenByTypedExportGrammar() {
        Ast.ExportDecl export = root(Parser.parse("""
                export symbol as PublicSymbol;
                const int symbol = 1;
                """)).exports().getFirst();

        assertNull(export.kind());
        assertEquals("symbol", export.items().getFirst().localName());
        assertEquals("PublicSymbol", export.items().getFirst().externalName());
    }

    @Test
    void annotationExpansionPreservesExportMetadata() {
        Ast.Program parsed = Parser.parse("""
                export class Box as PublicBox;
                define class Box as
                end
                """);
        Ast.Program expanded = AnnotationExpander.expand(parsed);
        assertEquals(root(parsed).exports(), root(expanded).exports());
    }

    @Test
    void exportMetadataSharesLocalDigestAcrossAliases() {
        ExportAbiMetadata.Document metadata = OresCompiler.emitExportMetadata("""
                export class Box as PublicBox;
                pub define class Box as
                end
                """);

        assertEquals(ExportAbiMetadata.VERSION, metadata.version());
        assertEquals(2, metadata.entries().size());
        ExportAbiMetadata.Entry original = metadata.entries().stream()
                .filter(e -> e.externalName().equals("Box")).findFirst().orElseThrow();
        ExportAbiMetadata.Entry alias = metadata.entries().stream()
                .filter(e -> e.externalName().equals("PublicBox")).findFirst().orElseThrow();

        assertEquals(original.localSymbolId(), alias.localSymbolId());
        assertEquals(original.signatureDigest(), alias.signatureDigest());
        assertEquals(ExportResolver.Origin.IMPLICIT_PUB, original.origin());
        assertEquals(ExportResolver.Origin.EXPLICIT_EXPORT, alias.origin());
    }

    @Test
    void exportMetadataDigestTracksSignatureNotAliasSpelling() {
        String first = OresCompiler.emitExportMetadata("""
                export fnc work as first_name;
                fnc work(int value): int { return value; }
                """).entries().getFirst().signatureDigest();

        String renamed = OresCompiler.emitExportMetadata("""
                export fnc work as second_name;
                fnc work(int value): int { return value; }
                """).entries().getFirst().signatureDigest();

        String changed = OresCompiler.emitExportMetadata("""
                export fnc work as second_name;
                fnc work(bool value): int { return 1; }
                """).entries().getFirst().signatureDigest();

        assertEquals(first, renamed);
        assertNotEquals(first, changed);
    }

    @Test
    void exportMetadataRejectsInferredPublicSignature() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.emitExportMetadata("""
                        export const answer;
                        const answer = 42;
                        """));
        assertTrue(failure.getMessage().contains("EXPORT_METADATA_UNRESOLVED_TYPE"), failure.getMessage());
    }

    @Test
    void privateTypeAliasIsNotLibraryRootUnlessExplicitlyExported() {
        var privateOnly = OresCompiler.compileForBuild("""
                define type Hidden = int;
                pub fnc main(): int { return 0; }
                """, BuildOptions.library(Map.of()));
        assertFalse(privateOnly.retainedSymbols().contains(Parser.ROOT_MODULE + ".Hidden"));

        var explicitlyExported = OresCompiler.compileForBuild("""
                export type Hidden;
                define type Hidden = int;
                pub fnc main(): int { return 0; }
                """, BuildOptions.library(Map.of()));
        assertTrue(explicitlyExported.retainedSymbols().contains(Parser.ROOT_MODULE + ".Hidden"));
    }

    @Test
    void synthesizedConstructorCannotLeakNarrowPrivateFieldType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        private define type InternalId = int;
                        define class PublicApi as
                          val InternalId id;
                        end
                        export class PublicApi;
                        """)));

        assertTrue(failure.getMessage().contains("EXPORT_API_LEAK"), failure.getMessage());
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
    void nonPublicSymbolExportDoesNotSurviveWithoutItsPrunedDeclaration() {
        var result = OresCompiler.compileForBuild("""
                export[file] symbol #ready;
                define symbol #ready;
                pub fnc main(): int { return 0; }
                """, BuildOptions.library(Map.of()));

        assertFalse(result.retainedSymbols().contains(Parser.ROOT_MODULE + ".#ready"));
        Ast.ModuleDecl root = root(result.program());
        assertTrue(root.exports().stream()
                .flatMap(export -> export.items().stream())
                .noneMatch(item -> item.localName().equals("#ready")));
    }

    @Test
    void metadataOnlyRootModuleExportSurvivesTreeShaking() {
        var result = OresCompiler.compileForBuild("""
                export module Api;
                define module Api as
                  pub fnc ping(): int { return 1; }
                end
                """, BuildOptions.library(Map.of()));

        Ast.ModuleDecl root = root(result.program());
        assertTrue(root.declarations().isEmpty());
        assertEquals(1, root.exports().size());
        assertEquals(Ast.ExportKind.MODULE, root.exports().getFirst().kind());
        assertEquals("Api", root.exports().getFirst().items().getFirst().localName());
        assertTrue(result.retainedSymbols().contains("Api.ping"));

        ExportResolver.ExportBinding moduleBinding =
                ExportResolver.resolve(result.program()).bindings().stream()
                        .filter(binding -> binding.localKind() == Ast.ExportKind.MODULE)
                        .findFirst()
                        .orElseThrow();
        assertEquals("Api", moduleBinding.externalName());
        assertEquals(Ast.ExportScope.PUBLIC, moduleBinding.scope());
    }

    @Test
    void symbolExportRequiresALocalDeclaration() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export symbol #ready;
                        fnc main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_UNKNOWN_TARGET"), failure.getMessage());
    }

    @Test
    void symbolDeclarationResolvesSelfIdentityExport() {
        ExportResolver.ExportBinding binding = ExportResolver.resolve(Parser.parse("""
                export symbol #ready as #ready;
                define symbol #ready;
                """)).bindings().getFirst();

        assertEquals(Ast.ExportKind.SYMBOL, binding.localKind());
        assertEquals("#ready", binding.externalName());
        assertTrue(binding.localSymbolId().endsWith("::symbol::#ready"), binding.localSymbolId());
    }

    @Test
    void explicitPrivateSymbolCannotBeExported() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        private define symbol #ready;
                        export symbol #ready;
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_EXPLICIT_PRIVATE"), failure.getMessage());
    }

    @Test
    void publicSymbolParticipatesInSourceMetadataWithoutHostExposure() {
        ExportAbiMetadata.Document metadata = OresCompiler.emitExportMetadata("""
                define pub symbol #ready;
                """);

        assertEquals(1, metadata.entries().size());
        assertEquals(Ast.ExportKind.SYMBOL, metadata.entries().getFirst().localKind());
        assertEquals("#ready", metadata.entries().getFirst().externalName());
    }

    @Test
    void explicitlyExportedSymbolIsATreeShakerLibraryRoot() {
        var result = OresCompiler.compileForBuild("""
                export symbol #ready;
                define symbol #ready;
                fnc unused(): int { return 1; }
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retainedSymbols().contains(Parser.ROOT_MODULE + ".#ready"));
        assertFalse(result.retainedSymbols().contains(Parser.ROOT_MODULE + ".unused"));
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

    @Test
    void exportedConstMetadataFailsClosedUntilCanonicalValueEncodingExists() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.emitExportMetadata("""
                        export const ready;
                        const string ready = 'ready';
                        """));
        assertTrue(
                failure.getMessage().contains("EXPORT_CONST_ABI_VALUE_UNENCODED"),
                failure.getMessage());
    }

}
