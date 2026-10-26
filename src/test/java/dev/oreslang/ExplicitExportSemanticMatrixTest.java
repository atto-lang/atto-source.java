package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitExportSemanticMatrixTest {

    @Test
    void c01_forwardExportBeforeDeclaration() {
        assertOne(resolve("""
                export class X;
                define class X as
                end
                """), "::class::X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c02_exportAfterDeclaration() {
        assertOne(resolve("""
                define class X as
                end
                export class X;
                """), "::class::X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c03_exportDoesNotCloseImportPrelude() {
        assertOne(resolve("""
                export class X;
                import class Dep from './dep';
                define class X as
                end
                """), "::class::X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c04_declarationClosesImportPrelude() {
        assertParseError("EXPORT_IMPORT_PRELUDE", """
                define class X as
                end
                import class Dep from './dep';
                export class X;
                """);
    }

    @Test
    void c05_inlinePubCreatesImplicitBinding() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                pub define class X as
                end
                """);
        assertOne(bindings, "::class::X", "X", Ast.ExportScope.PUBLIC);
        assertEquals(ExportResolver.Origin.IMPLICIT_PUB, bindings.getFirst().origin());
    }

    @Disabled("Java #1363: current AST does not yet retain declaration-side pub[pkg]/pub[file] scope")
    @Test
    void c06_inlinePkgPlusMatchingExplicitExportIsIdempotent() {
        resolve("""
                pub[pkg] define actor Worker as
                end
                export[pkg] actor Worker;
                """);
    }

    @Disabled("Java #1363: current AST collapses explicit private and default-private provenance")
    @Test
    void c07_explicitPrivateCannotBeExported() {
        assertExportError("EXPORT_EXPLICIT_PRIVATE", """
                private define class Secret as
                end
                export class Secret;
                """);
    }

    @Disabled("Java #1363: widening requires declaration-side package visibility provenance")
    @Test
    void c08_scopeConflictCannotWiden() {
        assertExportError("EXPORT_SCOPE_CONFLICT", """
                pub[pkg] define actor Worker as
                end
                export actor Worker;
                """);
    }

    @Test
    void c09_scopeConflictCannotNarrow() {
        assertExportError("EXPORT_SCOPE_CONFLICT", """
                pub define class X as
                end
                export[pkg] class X;
                """);
    }

    @Test
    void c10_typedKindMismatch() {
        assertExportError("EXPORT_KIND_MISMATCH", """
                define class X as
                end
                export actor X;
                """);
    }

    @Test
    void c11_unknownLocalExport() {
        assertExportError("EXPORT_UNKNOWN_TARGET", "export class Missing;");
    }

    @Test
    void c12_localExportCannotReexportImportedName() {
        assertExportError("EXPORT_IMPORTED_NAME", """
                import class X from './dep';
                export X;
                """);
    }

    @Test
    void c13_aliasOnlyExposesAliasForDefaultPrivateDeclaration() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define class Internal as
                end
                export class Internal as Public;
                """);
        assertOne(bindings, "::class::Internal", "Public", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c14_inlinePubPlusAliasExposesBothNames() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                pub define class Internal as
                end
                export class Internal as Public;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("Internal")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("Public")));
        assertEquals(1, bindings.stream().map(ExportResolver.ExportBinding::localSymbolId).distinct().count());
    }

    @Test
    void c15_aliasCollision() {
        assertExportError("EXPORT_ALIAS_COLLISION", """
                define class A as
                end
                define class B as
                end
                export class A as Public;
                export class B as Public;
                """);
    }

    @Test
    void c16_duplicateIdenticalExportIsIdempotent() {
        assertOne(resolve("""
                define class X as
                end
                export class X;
                export class X;
                """), "::class::X", "X", Ast.ExportScope.PUBLIC);
    }

    @Disabled("Java #1363: exact common-source case requires declaration-side package visibility")
    @Test
    void c17_publicSignatureLeaksPackageType() {
        assertExportError("EXPORT_API_LEAK", """
                pub[pkg] define type Hidden = int;
                export fnc reveal;
                fnc reveal(Hidden value): int { return 1; }
                """);
    }

    @Test
    void c18_packageSignatureMayUsePackageType() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define type Hidden = int;
                export[pkg] type Hidden;
                export[pkg] fnc id;
                fnc id(Hidden value): Hidden { return value; }
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.scope() == Ast.ExportScope.PACKAGE));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("Hidden")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("id")));
    }

    @Test
    void c19_packageSignatureLeaksFileType() {
        assertExportError("EXPORT_API_LEAK", """
                define type Hidden = int;
                export[file] type Hidden;
                export[pkg] fnc id;
                fnc id(Hidden value): Hidden { return value; }
                """);
    }

    @Test
    void c20_untypedExportResolvesOneLocalSymbol() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define type Count = int;
                export Count;
                """);
        assertOne(bindings, "::type::Count", "Count", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c21_untypedExportRejectsAmbiguousTypeValueSpelling() {
        assertExportError("EXPORT_AMBIGUOUS_TARGET", """
                define type Thing = int;
                const int Thing = 1;
                export Thing;
                """);
    }

    @Test
    void c22_fileExportRemainsFileScoped() {
        assertOne(resolve("""
                define type Local = int;
                export[file] type Local;
                """), "::type::Local", "Local", Ast.ExportScope.FILE);
    }

    @Test
    void c23_sameSpellingDistinctSymbolsCannotShareOneExternalAlias() {
        assertExportError("EXPORT_ALIAS_COLLISION", """
                define type Thing = int;
                const int Thing = 1;
                export type Thing as Shared;
                export const Thing as Shared;
                """);
    }

    private static List<ExportResolver.ExportBinding> resolve(String source) {
        return ExportResolver.resolve(Parser.parse(source)).bindings();
    }

    private static void assertOne(
            List<ExportResolver.ExportBinding> bindings,
            String localIdSuffix,
            String external,
            Ast.ExportScope scope) {
        assertEquals(1, bindings.size());
        ExportResolver.ExportBinding binding = bindings.getFirst();
        assertTrue(binding.localSymbolId().endsWith(localIdSuffix), binding.localSymbolId());
        assertEquals(external, binding.externalName());
        assertEquals(scope, binding.scope());
    }

    private static void assertParseError(String code, String source) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
    }

    private static void assertExportError(String code, String source) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
    }
}
