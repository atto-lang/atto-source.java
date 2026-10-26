package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportSemanticMatrixTest {
    @Test
    void c01_forwardExportBeforeDeclaration() {
        List<Ast.ExportBinding> bindings = resolve("""
                export class X;
                define class X as
                end
                """);
        assertSingle(bindings, "X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c02_exportAfterDeclaration() {
        List<Ast.ExportBinding> bindings = resolve("""
                define class X as
                end
                export class X;
                """);
        assertSingle(bindings, "X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c03_exportDoesNotCloseImportPrelude() {
        List<Ast.ExportBinding> bindings = resolve("""
                export class X;
                import class Dep from "./dep";
                define class X as
                end
                """);
        assertSingle(bindings, "X", "X", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c04_declarationClosesImportPrelude() {
        assertParseError("EXPORT_IMPORT_PRELUDE", """
                define class X as
                end
                import class Dep from "./dep";
                export class X;
                """);
    }

    @Test
    void c05_inlinePubCreatesImplicitBinding() {
        List<Ast.ExportBinding> bindings = resolve("""
                pub define class X as
                end
                """);
        assertSingle(bindings, "X", "X", Ast.ExportScope.PUBLIC);
        assertEquals(Ast.ExportOrigin.IMPLICIT_PUB, bindings.getFirst().origin());
    }

    @Disabled("Java #1363: declaration-site pub[pkg] / pub[file] provenance is not on current main")
    @Test
    void c06_inlinePkgPlusMatchingExplicitExportIsIdempotent() {
        resolve("""
                pub[pkg] actor Worker
                end
                export[pkg] actor Worker;
                """);
    }

    @Disabled("Java #1363 / PR #2057: AST does not yet retain explicit-private versus default-private provenance")
    @Test
    void c07_explicitPrivateCannotBeExported() {
        assertExportError("EXPORT_EXPLICIT_PRIVATE", """
                private define class Secret as
                end
                export class Secret;
                """);
    }

    @Disabled("Java #1363: widening from declaration-site pub[pkg] requires scoped visibility provenance")
    @Test
    void c08_scopeConflictCannotWiden() {
        assertExportError("EXPORT_SCOPE_CONFLICT", """
                pub[pkg] actor Worker
                end
                export actor Worker;
                """);
    }

    @Test
    void c09_scopeConflictCannotNarrow() {
        assertExportError("EXPORT_SCOPE_CONFLICT", """
                pub actor Worker
                end
                export[pkg] actor Worker;
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
                import class X from "./dep";
                export class X;
                """);
    }

    @Test
    void c13_aliasOnlyExposesAliasForDefaultPrivateDeclaration() {
        List<Ast.ExportBinding> bindings = resolve("""
                define class Internal as
                end
                export class Internal as Public;
                """);
        assertSingle(bindings, "Internal", "Public", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c14_inlinePubPlusAliasExposesBothNames() {
        List<Ast.ExportBinding> bindings = resolve("""
                pub define class Internal as
                end
                export class Internal as Public;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("Internal")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("Public")));
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
        List<Ast.ExportBinding> bindings = resolve("""
                define class X as
                end
                export class X;
                export class X;
                """);
        assertSingle(bindings, "X", "X", Ast.ExportScope.PUBLIC);
    }

    @Disabled("Java #1363: exact contract case requires declaration-site package visibility")
    @Test
    void c17_publicSignatureLeaksPackageType() {
        assertExportError("EXPORT_API_LEAK", """
                pub[pkg] define type Hidden = int;
                define class Api as
                  pub id(Hidden value): Hidden { return value; }
                end
                export class Api;
                """);
    }

    @Test
    void c18_packageSignatureMayUsePackageType() {
        List<Ast.ExportBinding> bindings = resolve("""
                define type Hidden = int;
                export[pkg] type Hidden;
                define class Api as
                  pub id(Hidden value): Hidden { return value; }
                end
                export[pkg] class Api;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.scope() == Ast.ExportScope.PACKAGE));
    }

    @Test
    void c19_packageSignatureLeaksFileType() {
        assertExportError("EXPORT_API_LEAK", """
                define type Hidden = int;
                export[file] type Hidden;
                define class Api as
                  pub id(Hidden value): Hidden { return value; }
                end
                export[pkg] class Api;
                """);
    }

    @Test
    void c20_untypedExportResolvesOneLocalSymbol() {
        List<Ast.ExportBinding> bindings = resolve("""
                define type Count = int;
                export Count;
                """);
        assertSingle(bindings, "Count", "Count", Ast.ExportScope.PUBLIC);
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
        List<Ast.ExportBinding> bindings = resolve("""
                define type Local = int;
                export[file] type Local;
                """);
        assertSingle(bindings, "Local", "Local", Ast.ExportScope.FILE);
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

    private static List<Ast.ExportBinding> resolve(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.ModuleDecl root = program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst().orElseThrow();
        return ExportResolver.resolve(program)
                .bindingsFor(ExportResolver.namespaceId(program, root));
    }

    private static void assertSingle(
            List<Ast.ExportBinding> bindings,
            String local,
            String external,
            Ast.ExportScope scope) {
        assertEquals(1, bindings.size());
        Ast.ExportBinding binding = bindings.getFirst();
        assertEquals(local, binding.localName());
        assertEquals(external, binding.externalName());
        assertEquals(scope, binding.scope());
    }

    private static void assertParseError(String code, String source) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("[" + code + "]"), failure.getMessage());
    }

    private static void assertExportError(String code, String source) {
        Ast.Program program = Parser.parse(source);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> ExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains("[" + code + "]"), failure.getMessage());
    }
}
