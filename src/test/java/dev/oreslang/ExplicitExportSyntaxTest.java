package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportSyntaxTest {

    private static final String KEEP = """
            fnc keep(): void { return; }
            """;

    @Test
    void acceptsCanonicalV1SyntaxAndPreservesUnresolvedAst() {
        List<String> sources = List.of(
                "export X;\n" + KEEP,
                "export[pkg] actor Counter;\n" + KEEP,
                "export(package) class {X, Y as Z,};\n" + KEEP,
                "export {X, Y as Z, #ready,};\n" + KEEP,
                "export symbol {#ready, #failed,};\n" + KEEP,
                "export class {X};\n" + KEEP,
                "export[file] type InternalAge;\n" + KEEP,
                "export symbol #ready as #ready;\n" + KEEP);

        for (String source : sources) {
            Ast.Program program = Parser.parse(source);
            assertEquals(1, program.exports().size(), source);
        }

        Ast.ExportDecl symbol = Parser.parse(
                "export symbol #ready as #ready;\n" + KEEP).exports().getFirst();
        assertEquals(Ast.ExportKind.SYMBOL, symbol.kind());
        assertTrue(symbol.items().getFirst().symbolLiteral());
        assertTrue(symbol.items().getFirst().selfIdentity());
        assertEquals("ready", symbol.items().getFirst().externalName());
    }

    @Test
    void exportMetadataDoesNotCloseImportPrelude() {
        Ast.Program before = Parser.parse("""
                export class Box;
                import class Dep from './dep';
                define class Box as
                end
                """);

        Ast.Program after = Parser.parse("""
                import class Dep from './dep';
                export class Box;
                define class Box as
                end
                """);

        assertEquals(before.imports(), after.imports());
        assertEquals(before.exports(), after.exports());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                        end
                        export class Box;
                        import class Dep from './dep';
                        """));
        assertTrue(failure.getMessage().contains("EXPORT_IMPORT_PRELUDE"), failure.getMessage());
    }

    @Test
    void rejectsHardenedNegativeSyntaxWithStableCategories() {
        assertSyntaxError("export X\n" + KEEP, "EXPORT_SEMICOLON_REQUIRED");
        assertSyntaxError("export {};\n" + KEEP, "EXPORT_EMPTY_GROUP");
        assertSyntaxError("export class {};\n" + KEEP, "EXPORT_EMPTY_GROUP");
        assertSyntaxError("export actor {,X};\n" + KEEP, "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {X,,Y};\n" + KEEP, "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {X Y};\n" + KEEP, "EXPORT_MALFORMED_LIST");
        assertSyntaxError("export actor {{X}};\n" + KEEP, "EXPORT_NESTED_GROUP");
        assertSyntaxError("export *;\n" + KEEP, "EXPORT_WILDCARD_UNSUPPORTED");
        assertSyntaxError("export * from './dep';\n" + KEEP, "EXPORT_REEXPORT_UNSUPPORTED");
        assertSyntaxError("export from './dep' {X};\n" + KEEP, "EXPORT_REEXPORT_UNSUPPORTED");
        assertSyntaxError("export dep.X;\n" + KEEP, "EXPORT_QUALIFIED_TARGET_UNSUPPORTED");
        assertSyntaxError("export dep::X;\n" + KEEP, "EXPORT_QUALIFIED_TARGET_UNSUPPORTED");
        assertSyntaxError("export Foo<int>;\n" + KEEP, "EXPORT_GENERIC_TARGET_UNSUPPORTED");
        assertSyntaxError("export foo(int);\n" + KEEP, "EXPORT_CALLABLE_SIGNATURE_UNSUPPORTED");
        assertSyntaxError("export {class X, actor Y};\n" + KEEP, "EXPORT_KIND_PREFIX_IN_GROUP");
        assertSyntaxError("export actor {actor X};\n" + KEEP, "EXPORT_KIND_PREFIX_IN_GROUP");
        assertSyntaxError("export symbol #ready as Ready;\n" + KEEP, "EXPORT_SYMBOL_BINDING_ALIAS_UNSUPPORTED");
        assertSyntaxError("export symbol #ready as #ok;\n" + KEEP, "EXPORT_SYMBOL_IDENTITY_MISMATCH");
        assertSyntaxError("export symbol ready as #ready;\n" + KEEP, "EXPORT_SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE");
        assertSyntaxError("export[pkg][file] X;\n" + KEEP, "EXPORT_MULTIPLE_SCOPES");
        assertSyntaxError("export[project] X;\n" + KEEP, "EXPORT_UNKNOWN_SCOPE");
        assertSyntaxError("export string ready as 'ready';\n" + KEEP, "EXPORT_UNKNOWN_DECLARATION_KIND");
        assertSyntaxError("export const ready as 'ready';\n" + KEEP, "EXPORT_LITERAL_ALIAS_UNSUPPORTED");
        assertSyntaxError("export class Foo as \"foo\";\n" + KEEP, "EXPORT_LITERAL_ALIAS_UNSUPPORTED");
    }

    @Test
    void rejectsExecutableAndClassLocalExports() {
        assertSyntaxError("""
                fnc bad(): void {
                  export X;
                  return;
                }
                """, "EXPORT_LOCAL_SCOPE");

        assertSyntaxError("""
                define class Bad as
                  export X;
                end
                """, "EXPORT_LOCAL_SCOPE");
    }

    @Test
    void resolvesForwardAndAliasBindingsAfterNamespaceCollection() {
        Ast.Program program = Parser.parse("""
                export class Internal as PublicBox;

                define class Internal as
                end
                """);

        ExportResolver.Result result = ExportResolver.resolve(program);
        ExportResolver.ExportBinding binding =
                result.bindings(Parser.ROOT_MODULE).getFirst();

        assertEquals("Internal", binding.localName());
        assertEquals("PublicBox", binding.externalName());
        assertEquals(Ast.ExportKind.CLASS, binding.localKind());
        assertEquals(Ast.ExportScope.PUBLIC, binding.scope());
        assertEquals(ExportResolver.BindingOrigin.EXPLICIT_EXPORT, binding.origin());
        assertTrue(binding.localSymbolId().contains("::class::Internal"));
    }

    @Test
    void inlinePubAndIdenticalExplicitExportAreIdempotent() {
        Ast.Program program = Parser.parse("""
                export class Box;

                pub define class Box as
                end
                """);

        List<ExportResolver.ExportBinding> bindings =
                ExportResolver.resolve(program).bindings(Parser.ROOT_MODULE);
        assertEquals(1, bindings.size());
        assertEquals("Box", bindings.getFirst().externalName());
        assertEquals(ExportResolver.BindingOrigin.IMPLICIT_PUB, bindings.getFirst().origin());
    }

    @Test
    void resolvesPackageScopedBindingWithoutCreatingNativeAbiExposure() {
        Ast.Program program = Parser.parse("""
                export[pkg] class Box;

                define class Box as
                end
                """);

        ExportResolver.ExportBinding binding =
                ExportResolver.resolve(program).bindings(Parser.ROOT_MODULE).getFirst();
        assertEquals(Ast.ExportScope.PACKAGE, binding.scope());
        assertEquals("Box", binding.externalName());
    }

    @Test
    void rejectsExplicitPrivateScopeConflictAndAliasCollision() {
        assertSemanticError("""
                export class Box;
                private define class Box as
                end
                """, "EXPORT_EXPLICIT_PRIVATE");

        assertSemanticError("""
                export[pkg] class Box;
                pub define class Box as
                end
                """, "EXPORT_SCOPE_CONFLICT");

        assertSemanticError("""
                export class A as Public;
                export class B as Public;
                define class A as
                end
                define class B as
                end
                """, "EXPORT_ALIAS_COLLISION");
    }

    @Test
    void rejectsKindMismatchAmbiguityImportedReexportAndVisibilityLeak() {
        assertSemanticError("""
                export actor Box;
                define class Box as
                end
                """, "EXPORT_KIND_MISMATCH");

        assertSemanticError("""
                export X;
                type X = int;
                const int X = 1;
                """, "EXPORT_AMBIGUOUS_TARGET");

        assertSemanticError("""
                import class External from './dep';
                export External;
                fnc keep(): void { return; }
                """, "EXPORT_IMPORTED_NAME");

        assertSemanticError("""
                export fnc api;
                type Hidden = int;
                fnc api(Hidden value): void { return; }
                """, "EXPORT_API_LEAK");
    }

    @Test
    void rejectsMixedVisibilityOverloadFamily() {
        assertSemanticError("""
                export fnc f;
                pub fnc f(): int { return 1; }
                fnc f(int value): int { return value; }
                """, "EXPORT_OVERLOAD_VISIBILITY_CONFLICT");
    }

    @Test
    void moduleExportsResolveWithinTheirOwnNamespace() {
        Ast.Program program = Parser.parse("""
                define module Models as
                  export class Box;
                  define class Box as
                  end
                end
                """);

        List<ExportResolver.ExportBinding> bindings =
                ExportResolver.resolve(program).bindings("Models");
        assertEquals(1, bindings.size());
        assertEquals("Box", bindings.getFirst().externalName());
        assertEquals("Models", bindings.getFirst().ownerNamespaceId());
    }

    private static void assertSyntaxError(String source, String category) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(category), failure.getMessage());
    }

    private static void assertSemanticError(String source, String category) {
        Ast.Program program = Parser.parse(source);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> ExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains(category), failure.getMessage());
    }
}
