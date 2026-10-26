package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertTrue(failure.getMessage().contains("IMPORT_AFTER_DECLARATION"), failure.getMessage());
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
    void syntaxDiagnosticsAreStableAndSpecific() {
        assertSyntaxError("export class {}; fnc main(): void { return; }", "EMPTY_EXPORT_GROUP");
        assertSyntaxError("export actor {A,,B}; fnc main(): void { return; }", "MALFORMED_EXPORT_LIST");
        assertSyntaxError("export *; fnc main(): void { return; }", "WILDCARD_EXPORT_UNSUPPORTED");
        assertSyntaxError("export from './dep' {X}; fnc main(): void { return; }", "REEXPORT_UNSUPPORTED");
        assertSyntaxError("export dep.X; fnc main(): void { return; }", "QUALIFIED_EXPORT_UNSUPPORTED");
        assertSyntaxError("export Foo<int>; fnc main(): void { return; }", "GENERIC_EXPORT_TARGET_UNSUPPORTED");
        assertSyntaxError("export foo(int); fnc main(): void { return; }", "CALLABLE_SIGNATURE_EXPORT_UNSUPPORTED");
        assertSyntaxError("export {class X}; fnc main(): void { return; }", "KIND_PREFIX_IN_GROUP");
        assertSyntaxError("export symbol #ready as #other; fnc main(): void { return; }", "SYMBOL_IDENTITY_MISMATCH");
        assertSyntaxError("export symbol #ready as Ready; fnc main(): void { return; }", "SYMBOL_BINDING_ALIAS_UNSUPPORTED");
        assertSyntaxError("export symbol ready as #ready; fnc main(): void { return; }", "SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE");
        assertSyntaxError("export string ready as 'ready'; fnc main(): void { return; }", "UNKNOWN_EXPORT_DECLARATION_KIND");
        assertSyntaxError("export const ready as 'ready'; fnc main(): void { return; }", "LITERAL_EXPORT_ALIAS_UNSUPPORTED");
        assertSyntaxError("export class Foo as \"foo\"; fnc main(): void { return; }", "LITERAL_EXPORT_ALIAS_UNSUPPORTED");
        assertSyntaxError("export class X fnc main(): void { return; }", "EXPORT_SEMICOLON_REQUIRED");
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
        assertTrue(failure.getMessage().contains("IMPORTED_BINDING_EXPORT_NOT_ALLOWED"), failure.getMessage());
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
        assertTrue(failure.getMessage().contains("AMBIGUOUS_EXPORT_TARGET"), failure.getMessage());
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
        assertTrue(failure.getMessage().contains("EXPORT_API_VISIBILITY_LEAK"), failure.getMessage());
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
    void symbolSyntaxFailsClosedUntilJavaHasSymbolDeclarationAst() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExportResolver.resolve(Parser.parse("""
                        export symbol #ready;
                        fnc main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("EXPORT_SYMBOL_DECLARATION_UNAVAILABLE"), failure.getMessage());
    }

    private static void assertSyntaxError(String source, String category) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(category), failure.getMessage());
    }
}
