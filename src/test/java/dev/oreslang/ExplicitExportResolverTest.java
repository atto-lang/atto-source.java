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
