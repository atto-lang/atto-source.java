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

    @Test
    void c06_inlinePkgPlusMatchingExplicitExportIsIdempotent() {
        resolve("""
                pub[pkg] define actor Worker as
                end
                export[pkg] actor Worker;
                """);
    }

    @Test
    void c07_explicitPrivateCannotBeExported() {
        assertExportError("EXPORT_EXPLICIT_PRIVATE", """
                private define class Secret as
                end
                export class Secret;
                """);
    }

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
                pub[pkg] define type Hidden = int;
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
                pub[file] define type Hidden = int;
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

    @Test
    void c24_fncExportsCompleteOverloadFamily() {
        assertOne(resolve("""
                export fnc work;
                fnc work(int value): int { return value; }
                fnc work(string value): string { return value; }
                """), "::function::work", "work", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c25_fncInferredFamilyAlias() {
        assertOne(resolve("""
                fnc work(int value): int { return value; }
                fnc work(string value): string { return value; }
                export work as run;
                """), "::function::work", "run", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c26_fncInlinePublicFamilyWithAlias() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                pub fnc work(int value): int { return value; }
                pub fnc work(string value): string { return value; }
                export fnc work as run;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("work")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("run")));
    }

    @Test
    void c27_fncIncompatibleFamilyScopes() {
        assertExportError("OVERLOAD_VISIBILITY_CONFLICT", """
                fnc work(int value): int { return value; }
                pub[pkg] fnc work(string value): string { return value; }
                pub fnc work(bool value): bool { return value; }
                export fnc work;
                """);
    }

    @Test
    void c28_fncPrivateFamilyCannotPartiallyExport() {
        assertExportError("EXPORT_EXPLICIT_PRIVATE", """
                private fnc work(int value): int { return value; }
                fnc work(string value): string { return value; }
                export fnc work;
                """);
    }

    @Test
    void c29_fncDuplicateOverloadSignature() {
        assertExportError("DUPLICATE_OVERLOAD_SIGNATURE", """
                fnc work(int value): int { return value; }
                fnc work(int other): int { return other; }
                export fnc work;
                """);
    }

    @Test
    void c30_routineExportsCompleteOverloadFamily() {
        assertOne(resolve("""
                export routine work;
                routine work(int value): int { return value; }
                routine work(string value): string { return value; }
                """), "::routine::work", "work", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c31_routineInferredFamilyAlias() {
        assertOne(resolve("""
                routine work(int value): int { return value; }
                routine work(string value): string { return value; }
                export work as run;
                """), "::routine::work", "run", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c32_routineInlinePublicFamilyWithAlias() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                pub routine work(int value): int { return value; }
                pub routine work(string value): string { return value; }
                export routine work as run;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("work")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("run")));
    }

    @Test
    void c33_routineIncompatibleFamilyScopes() {
        assertExportError("OVERLOAD_VISIBILITY_CONFLICT", """
                routine work(int value): int { return value; }
                pub[pkg] routine work(string value): string { return value; }
                pub routine work(bool value): bool { return value; }
                export routine work;
                """);
    }

    @Test
    void c34_routinePrivateFamilyCannotPartiallyExport() {
        assertExportError("EXPORT_EXPLICIT_PRIVATE", """
                private routine work(int value): int { return value; }
                routine work(string value): string { return value; }
                export routine work;
                """);
    }

    @Test
    void c35_routineDuplicateOverloadSignature() {
        assertExportError("DUPLICATE_OVERLOAD_SIGNATURE", """
                routine work(int value): int { return value; }
                routine work(int other): int { return other; }
                export routine work;
                """);
    }

    @Test
    void c36_defaultOverloadInheritsExplicitFamilyScope() {
        assertOne(resolve("""
                pub fnc work(int value): int { return value; }
                fnc work(bool value): bool { return value; }
                """), "::function::work", "work", Ast.ExportScope.PUBLIC);
    }

    @Test
    void c37_publicAliasCappedByFileModule() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define pub[file] module Local as
                  define class Internal as
                  end
                  export class Internal as Api;
                end
                """);
        assertOne(bindings, "::class::Internal", "Api", Ast.ExportScope.FILE);
    }

    @Test
    void c38_publicAndAliasBothCappedByFileModule() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define pub[file] module Local as
                  pub define class Internal as
                  end
                  export class Internal as Api;
                end
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.scope() == Ast.ExportScope.FILE));
    }

    @Test
    void c39_publicAliasCappedByPackageModule() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define pub[pkg] module Local as
                  define class Internal as
                  end
                  export class Internal as Api;
                end
                """);
        assertOne(bindings, "::class::Internal", "Api", Ast.ExportScope.PACKAGE);
    }

    @Test
    void c40_publicAndAliasBothCappedByPackageModule() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define pub[pkg] module Local as
                  pub define class Internal as
                  end
                  export class Internal as Api;
                end
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.scope() == Ast.ExportScope.PACKAGE));
    }

    @Test
    void c41_packageMemberInFileModuleRemainsFileScoped() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define pub[file] module Local as
                  pub[pkg] define class X as
                  end
                end
                """);
        assertOne(bindings, "::class::X", "X", Ast.ExportScope.FILE);
    }

    @Test
    void c42_recursiveSignatureCycleWithEqualVisibility() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                pub define type A = B;
                pub define type B = A;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.scope() == Ast.ExportScope.PUBLIC));
    }

    @Test
    void c43_recursiveSignatureLeaksNarrowTypeThroughAlias() {
        assertExportError("EXPORT_API_LEAK", """
                private define type Hidden = int;
                define type Wrapper = Hidden;
                export type Wrapper;
                """);
    }

    @Test
    void c44_allOverloadSignaturesCheckedForLeakage() {
        assertExportError("EXPORT_API_LEAK", """
                private define type Hidden = int;
                fnc f(): int { return 0; }
                fnc f(Hidden value): int { return 1; }
                export fnc f;
                """);
    }

    @Test
    void c45_aliasMakesNominalTypeAccessibleWithoutLocalSpelling() {
        List<ExportResolver.ExportBinding> bindings = resolve("""
                define type Internal = int;
                export type Internal as PublicType;
                fnc use(Internal value): Internal { return value; }
                export fnc use;
                """);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("PublicType")));
        assertTrue(bindings.stream().anyMatch(b -> b.externalName().equals("use")));
    }

    @Disabled("Java AST still lacks first-class Symbol declarations; syntax remains fail-closed")
    @Test
    void c46_symbolSelfIdentityResolvesLocalDeclaration() {
        assertExportError("EXPORT_SYMBOL_DECLARATION_UNAVAILABLE", """
                export symbol #ready as #ready;
                fnc main(): void { return; }
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
