package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExplicitExportResolver;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitExportResolverTest {

    private static ExplicitExportResolver.Result resolve(String source) {
        return ExplicitExportResolver.resolve(Parser.parse(source));
    }

    private static List<ExplicitExportResolver.ExportBinding> rootBindings(String source) {
        return resolve(source).bindings(Parser.ROOT_MODULE);
    }

    private static Ast.TypeAliasDecl publicAlias(String name, Ast.TypeRef type) {
        return new Ast.TypeAliasDecl(name, Ast.Visibility.PUBLIC, List.of(), type);
    }

    private static Ast.TypeRef nestedType(int wrappers) {
        Ast.TypeRef type = Ast.TypeRef.simple("int");
        for (int i = 0; i < wrappers; i++) type = new Ast.TypeRef("Array", List.of(type), false);
        return type;
    }

    @Test
    void deeplyNestedGeneratedTypeIsRejectedWithoutHostStackOverflow() {
        Ast.TypeRef type = nestedType(100_000);
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE,
                List.of(publicAlias("Deep", type)))));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ExplicitExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains("type depth limit exceeded"), failure.getMessage());
    }

    @Test
    void exactTypeDepthBoundaryIsAdmitted() {
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE,
                List.of(publicAlias("Deep", nestedType(255))))));
        assertDoesNotThrow(() -> ExplicitExportResolver.resolve(program));
    }

    @Test
    void wideGeneratedTypeCannotAllocateUnboundedTraversalWork() {
        var type = new Ast.TypeRef("Tuple", java.util.Collections.nCopies(65_536,
                Ast.TypeRef.simple("int")), false);
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE,
                List.of(publicAlias("Wide", type)))));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ExplicitExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains("type work budget exceeded"), failure.getMessage());
    }

    @Test
    void exactNamespaceTypeWorkBoundaryIsAdmitted() {
        var type = new Ast.TypeRef("Tuple", java.util.Collections.nCopies(65_535,
                Ast.TypeRef.simple("int")), false);
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE,
                List.of(publicAlias("Wide", type)))));
        assertDoesNotThrow(() -> ExplicitExportResolver.resolve(program));
    }

    @Test
    void typeWorkBudgetIsSharedAcrossExportedSignatures() {
        var type = new Ast.TypeRef("Tuple", java.util.Collections.nCopies(32_768,
                Ast.TypeRef.simple("int")), false);
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE,
                List.of(publicAlias("A", type), publicAlias("B", type)))));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ExplicitExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains("type work budget exceeded"), failure.getMessage());
    }

    @Test
    void implicitPublicOverloadIncludesUnmodifiedFamilyMembersInEitherOrder() {
        for (String source : List.of(
                "pub fnc work(): int { return 1; } fnc work(int x): int { return x; }",
                "fnc work(int x): int { return x; } pub fnc work(): int { return 1; }")) {
            assertEquals(2, rootBindings(source).getFirst().declarationIndices().size());
        }
    }

    @Test
    void explicitExportOfMixedPublicAndUnmodifiedOverloadsIsIdempotent() {
        var bindings = rootBindings("""
                export fnc work;
                pub fnc work(): int { return 1; }
                fnc work(int x): int { return x; }
                """);
        assertEquals(1, bindings.size());
        assertEquals(2, bindings.getFirst().declarationIndices().size());
    }

    @Test
    void implicitPublicFamilyCannotPartiallyHideExplicitlyPrivateOverload() {
        var failure = assertThrows(IllegalArgumentException.class, () -> resolve("""
                pub fnc work(): int { return 1; }
                private fnc work(int x): int { return x; }
                """));
        assertTrue(failure.getMessage().contains("explicitly private"), failure.getMessage());
    }

    @Test
    void genericFunctionBinderShadowsPrivateLocalTypeForLeakageChecks() {
        assertDoesNotThrow(() -> resolve("""
                export fnc identity;
                define type T = int;
                fnc identity<T>(T value): T { return value; }
                """));
    }

    @Test
    void genericAliasAndInterfaceBindersShadowPrivateLocalType() {
        assertDoesNotThrow(() -> resolve("""
                export type Box;
                define type T = int;
                define type Box<T> = Array<T>;
                """));
        assertDoesNotThrow(() -> resolve("""
                export interface Box;
                define type T = int;
                define interface Box<T> as
                  fnc echo(T value): T;
                end
                """));
    }

    @Test
    void genericClassAndMethodBindersAreScopedToTheirSignatures() {
        assertDoesNotThrow(() -> resolve("""
                export class Box;
                define type T = int;
                define type U = int;
                define class Box<T> as
                  pub echo(T value): T { return value; }
                  pub local<U>(U value): U { return value; }
                end
                """));
    }

    @Test
    void methodBinderDoesNotHidePrivateTypeInSiblingSignature() {
        var failure = assertThrows(IllegalArgumentException.class, () -> resolve("""
                export class Box;
                define type T = int;
                define class Box as
                  pub local<T>(T value): T { return value; }
                  pub exposed(): T { return 1; }
                end
                """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'T'"), failure.getMessage());
    }

    @Test
    void genericDefaultStillChecksNominalTypeVisibility() {
        var failure = assertThrows(IllegalArgumentException.class, () -> resolve("""
                export fnc identity;
                define type Hidden = int;
                fnc identity<T = Hidden>(T value): T { return value; }
                """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'Hidden'"), failure.getMessage());
    }

    @Test
    void nestedGenericArgumentStillChecksNominalTypeVisibility() {
        var failure = assertThrows(IllegalArgumentException.class, () -> resolve("""
                export fnc expose;
                define type Hidden = int;
                fnc expose<T>(Array<Hidden> value): Array<Hidden> { return value; }
                """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'Hidden'"), failure.getMessage());
    }

    @Test
    void localSymbolIdentityDoesNotDependOnDefaultLocale() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var binding = rootBindings("export fnc work; fnc work(): int { return 1; }").getFirst();
            assertTrue(binding.localSymbolId().endsWith("::function::work"));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test
    void duplicateNamespaceCannotOverwriteResolvedBindings() {
        var parsed = Parser.parse("pub fnc work(): int { return 1; }");
        var module = parsed.modules().getFirst();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ExplicitExportResolver.resolve(new Ast.Program(List.of(module, module))));
        assertTrue(failure.getMessage().contains("duplicate export namespace"), failure.getMessage());
    }

    @Test
    void defaultPrivateDeclarationMayBeExplicitlyExported() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export class X;

                define class X as
                end
                """);

        assertEquals(1, bindings.size());
        ExplicitExportResolver.ExportBinding binding = bindings.getFirst();
        assertEquals("X", binding.localName());
        assertEquals("X", binding.externalName());
        assertEquals(Ast.ExportKind.CLASS, binding.localKind());
        assertEquals(ExplicitExportResolver.Origin.EXPLICIT_EXPORT, binding.origin());
    }

    @Test
    void aliasCreatesExternalBindingWithoutRenamingLocalDeclaration() {
        ExplicitExportResolver.ExportBinding binding = rootBindings("""
                export class Internal as Public;

                define class Internal as
                end
                """).getFirst();

        assertEquals("Internal", binding.localName());
        assertEquals("Public", binding.externalName());
        assertTrue(binding.localSymbolId().endsWith("::class::Internal"));
    }

    @Test
    void explicitPrivateDeclarationCannotBeExported() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export class X;

                        private define class X as
                        end
                        """));
        assertTrue(failure.getMessage().contains("explicitly private"), failure.getMessage());
    }

    @Test
    void typedKindMismatchFails() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export actor X;

                        define class X as
                        end
                        """));
        assertTrue(failure.getMessage().contains("kind mismatch"), failure.getMessage());
    }

    @Test
    void aliasCollisionFails() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export class A as Public;
                        export class B as Public;

                        define class A as
                        end

                        define class B as
                        end
                        """));
        assertTrue(failure.getMessage().contains("collision"), failure.getMessage());
    }

    @Test
    void inlinePubPlusIdenticalExplicitExportIsIdempotent() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export class X;

                pub define class X as
                end
                """);

        assertEquals(1, bindings.size());
        assertEquals(ExplicitExportResolver.Origin.IMPLICIT_PUB, bindings.getFirst().origin());
    }

    @Test
    void typedExportIgnoresDifferentKindPrivateDeclarationWithSameSpelling() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export class Same;

                private fnc Same(): void {
                  return;
                }

                define class Same as
                end
                """);

        assertEquals(1, bindings.size());
        assertEquals(Ast.ExportKind.CLASS, bindings.getFirst().localKind());
    }

    @Test
    void untypedSameSpellingAcrossKindsIsAmbiguous() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export Same;

                        fnc Same(): void {
                          return;
                        }

                        define class Same as
                        end
                        """));
        assertTrue(failure.getMessage().contains("ambiguous"), failure.getMessage());
    }

    @Test
    void overloadedFunctionFamilyProducesOneLogicalBinding() {
        ExplicitExportResolver.ExportBinding binding = rootBindings("""
                export fnc work;

                fnc work(): int {
                  return 1;
                }

                fnc work(int value): int {
                  return value;
                }
                """).getFirst();

        assertEquals(Ast.ExportKind.FUNCTION, binding.localKind());
        assertEquals(2, binding.declarationIndices().size());
    }

    @Test
    void inlinePubPlusAliasExposesOriginalAndAlias() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export class Internal as Public;

                pub define class Internal as
                end
                """);

        assertEquals(2, bindings.size());
        assertEquals(
                List.of("Internal", "Public"),
                bindings.stream().map(ExplicitExportResolver.ExportBinding::externalName).sorted().toList());
    }

    @Test
    void publicFunctionCannotLeakUnexportedLocalType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export fnc expose;

                        define type Hidden = int;

                        fnc expose(Hidden value): Hidden {
                          return value;
                        }
                        """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'Hidden'"), failure.getMessage());
    }

    @Test
    void publicFunctionMayUseSeparatelyExportedLocalType() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export type Hidden;
                export fnc expose;

                define type Hidden = int;

                fnc expose(Hidden value): Hidden {
                  return value;
                }
                """);

        assertEquals(
                List.of("Hidden", "expose"),
                bindings.stream()
                        .map(ExplicitExportResolver.ExportBinding::externalName)
                        .sorted()
                        .toList());
    }

    @Test
    void publicClassMethodCannotLeakUnexportedLocalType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export class Api;

                        define type Hidden = int;

                        define class Api as
                          pub get(): Hidden {
                            return 1;
                          }
                        end
                        """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'Hidden'"), failure.getMessage());
    }

    @Test
    void publicFunctionEffectCannotLeakUnexportedLocalType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export fnc expose;

                        define type Hidden = int;

                        fnc expose(): void throws Hidden {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("leaks non-public local type 'Hidden'"), failure.getMessage());
    }

    @Test
    void privateClassMemberDoesNotBecomePartOfExportedApiSurface() {
        assertDoesNotThrow(() -> resolve("""
                export class Api;

                define type Hidden = int;

                define class Api as
                  private get(): Hidden {
                    return 1;
                  }
                end
                """));
    }

    @Test
    void aliasedTypeExportSatisfiesVisibilityOfTheLocalTypeIdentity() {
        List<ExplicitExportResolver.ExportBinding> bindings = rootBindings("""
                export type Hidden as PublicHidden;
                export fnc expose;

                define type Hidden = int;

                fnc expose(Hidden value): Hidden {
                  return value;
                }
                """);

        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(binding ->
                binding.localName().equals("Hidden")
                        && binding.externalName().equals("PublicHidden")));
    }

    @Test
    void packageAndFileScopesRemainFailClosedUntilJavaVisibilityParityLands() {
        for (String scope : List.of("[pkg]", "[file]")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> resolve("""
                            export%s class X;

                            define class X as
                            end
                            """.formatted(scope)));
            assertTrue(failure.getMessage().contains("public scope only"), failure.getMessage());
        }
    }

    @Test
    void symbolSyntaxIsPreservedButSemanticLoweringRemainsFailClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export symbol #ready as #ready;

                        define class Placeholder as
                        end
                        """));
        assertTrue(failure.getMessage().contains("Symbol declaration export resolution"), failure.getMessage());
    }

    @Test
    void namedModuleDefaultPrivateDeclarationMayBeExported() {
        ExplicitExportResolver.Result result = resolve("""
                define module M as
                  export class X;

                  define class X as
                  end
                end
                """);

        assertEquals(1, result.bindings("M").size());
        assertEquals("X", result.bindings("M").getFirst().externalName());
    }

    @Test
    void namedModuleExplicitPrivateDeclarationCannotBeExported() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        define module M as
                          export class X;

                          define private class X as
                          end
                        end
                        """));
        assertTrue(failure.getMessage().contains("explicitly private"), failure.getMessage());
    }

    @Test
    void postKindExplicitPrivateSpellingCannotEvadeExportGate() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> resolve("""
                        export fnc secret;

                        fnc private secret(): void {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("explicitly private"), failure.getMessage());
    }
}
