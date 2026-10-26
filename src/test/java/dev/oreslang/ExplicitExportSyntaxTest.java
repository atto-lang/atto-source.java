package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportSyntaxTest {
    @Test
    void exportMetadataDoesNotCloseImportPrelude() {
        Ast.Program before = Parser.parse("""
                export class Worker;
                import type Message from "+core/actor";
                define class Worker as
                end
                """);
        Ast.Program after = Parser.parse("""
                import type Message from "+core/actor";
                export class Worker;
                define class Worker as
                end
                """);

        Ast.ExportDecl left = root(before).exports().getFirst();
        Ast.ExportDecl right = root(after).exports().getFirst();
        assertEquals(left.scope(), right.scope());
        assertEquals(left.kind(), right.kind());
        assertEquals(left.items(), right.items());
        assertEquals(before.imports(), after.imports());
    }

    @Test
    void importAfterRealDeclarationStillFails() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Worker as
                end
                import type Message from "+core/actor";
                """));
        assertTrue(failure.getMessage().contains("[IMPORT_AFTER_DECLARATION]"), failure.getMessage());
    }

    @Test
    void parsesScopesGroupsAliasesTrailingCommaAndSymbolIdentity() {
        Ast.Program program = Parser.parse("""
                export[pkg] actor {Reader, Writer as Sink,};
                export(file) type {Count};
                export {Config, #ready,};
                export symbol #ready as #ready;
                fnc main(): void { return; }
                """);

        List<Ast.ExportDecl> exports = root(program).exports();
        assertEquals(4, exports.size());
        assertEquals(Ast.ExportScope.PACKAGE, exports.get(0).scope());
        assertEquals(Ast.ExportKind.ACTOR, exports.get(0).kind());
        assertEquals("Sink", exports.get(0).items().get(1).externalName());
        assertEquals(Ast.ExportScope.FILE, exports.get(1).scope());
        assertEquals("#ready", exports.get(2).items().get(1).localName());
        assertTrue(exports.get(3).items().getFirst().selfIdentity());
    }

    @Test
    void rejectsPinnedAdversarialSyntaxWithStableCategories() {
        assertParseError("EXPORT_SEMICOLON_REQUIRED", "export class X\nfnc main(): void { return; }");
        assertParseError("EMPTY_EXPORT_GROUP", "export actor {}; fnc main(): void { return; }");
        assertParseError("MALFORMED_EXPORT_LIST", "export actor {A,,B}; fnc main(): void { return; }");
        assertParseError("WILDCARD_EXPORT_UNSUPPORTED", "export *; fnc main(): void { return; }");
        assertParseError("REEXPORT_UNSUPPORTED", "export from \"./dep\" {X}; fnc main(): void { return; }");
        assertParseError("QUALIFIED_EXPORT_UNSUPPORTED", "export dep.X; fnc main(): void { return; }");
        assertParseError("QUALIFIED_EXPORT_UNSUPPORTED", "export dep::X; fnc main(): void { return; }");
        assertParseError("GENERIC_EXPORT_TARGET_UNSUPPORTED", "export Foo<int>; fnc main(): void { return; }");
        assertParseError("CALLABLE_SIGNATURE_EXPORT_UNSUPPORTED", "export foo(int); fnc main(): void { return; }");
        assertParseError("KIND_PREFIX_IN_GROUP", "export {class X, actor Y}; fnc main(): void { return; }");
        assertParseError("SYMBOL_IDENTITY_MISMATCH", "export symbol #ready as #other; fnc main(): void { return; }");
        assertParseError("SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE", "export symbol ready as #ready; fnc main(): void { return; }");
        assertParseError("UNKNOWN_EXPORT_DECLARATION_KIND", "export string ready as 'ready'; fnc main(): void { return; }");
        assertParseError("LITERAL_EXPORT_ALIAS_UNSUPPORTED", "export const ready as 'ready'; fnc main(): void { return; }");
    }

    @Test
    void resolvesForwardExportAndAliasAfterNamespaceCollection() {
        Ast.Program program = Parser.parse("""
                export class Internal as Public;
                define class Internal as
                end
                """);

        ExportResolver.Resolution resolution = ExportResolver.resolve(program);
        Ast.ExportBinding binding = resolution.bindingsFor(owner(program)).getFirst();
        assertEquals("Internal", binding.localName());
        assertEquals("Public", binding.externalName());
        assertEquals(Ast.ExportKind.CLASS, binding.localKind());
        assertEquals(Ast.ExportOrigin.EXPLICIT_EXPORT, binding.origin());
    }

    @Test
    void inlinePubAndIdenticalExplicitExportAreIdempotent() {
        Ast.Program program = Parser.parse("""
                export class X;
                pub define class X as
                end
                """);

        List<Ast.ExportBinding> bindings = ExportResolver.resolve(program).bindingsFor(owner(program));
        assertEquals(1, bindings.size());
        assertEquals("X", bindings.getFirst().externalName());
        assertEquals(Ast.ExportScope.PUBLIC, bindings.getFirst().scope());
    }

    @Test
    void aliasCollisionAndScopeConflictFailClosed() {
        Ast.Program alias = Parser.parse("""
                export class A as Public;
                export class B as Public;
                define class A as
                end
                define class B as
                end
                """);
        assertExportError("EXPORT_ALIAS_COLLISION", alias);

        Ast.Program scope = Parser.parse("""
                export[file] class X;
                export[pkg] class X;
                define class X as
                end
                """);
        assertExportError("EXPORT_SCOPE_CONFLICT", scope);
    }

    @Test
    void typedKindMismatchAndImportedLocalExportFailClosed() {
        Ast.Program mismatch = Parser.parse("""
                export actor Box;
                define class Box as
                end
                """);
        assertExportError("EXPORT_KIND_MISMATCH", mismatch);

        Ast.Program imported = Parser.parse("""
                export X;
                import class X from "./dep";
                fnc main(): void { return; }
                """);
        assertExportError("IMPORTED_EXPORT_REQUIRES_REEXPORT", imported);
    }

    @Test
    void untypedSameSpellingAcrossTypeAndValueNamespacesIsAmbiguous() {
        Ast.Program program = Parser.parse("""
                export X;
                define type X = int;
                const int X = 1;
                """);
        assertExportError("AMBIGUOUS_EXPORT_TARGET", program);
    }

    @Test
    void functionOverloadsResolveAsOneLogicalFamilyButMixedVisibilityFails() {
        Ast.Program good = Parser.parse("""
                export fnc work;
                fnc work(): int { return 1; }
                fnc work(int value): int { return value; }
                """);
        List<Ast.ExportBinding> goodBindings = ExportResolver.resolve(good).bindingsFor(owner(good));
        assertEquals(1, goodBindings.size());
        assertEquals(Ast.ExportKind.FNC, goodBindings.getFirst().localKind());

        Ast.Program bad = Parser.parse("""
                export fnc work;
                pub fnc work(): int { return 1; }
                fnc work(int value): int { return value; }
                """);
        assertExportError("OVERLOAD_VISIBILITY_CONFLICT", bad);
    }

    @Test
    void exportedSignatureCannotLeakNarrowerLocalType() {
        Ast.Program leaking = Parser.parse("""
                define type InternalId = int;
                export class PublicApi;
                define class PublicApi as
                  pub id(InternalId value): InternalId {
                    return value;
                  }
                end
                """);
        assertExportError("EXPORT_API_VISIBILITY_LEAK", leaking);

        Ast.Program packageSafe = Parser.parse("""
                export[pkg] type InternalId;
                define type InternalId = int;
                export[pkg] class PublicApi;
                define class PublicApi as
                  pub id(InternalId value): InternalId {
                    return value;
                  }
                end
                """);
        assertDoesNotThrow(() -> ExportResolver.resolve(packageSafe));
    }

    @Test
    void typeCheckerRunsExportResolutionAsAdmissionGate() {
        Ast.Program program = Parser.parse("""
                export actor Box;
                define class Box as
                end
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("EXPORT_KIND_MISMATCH"), failure.getMessage());
    }

    @Test
    void moduleBodyCarriesItsOwnExportNamespace() {
        Ast.Program program = Parser.parse("""
                define module Service as
                  export fnc value;
                  fnc value(): int { return 1; }
                end
                """);
        Ast.ModuleDecl service = program.modules().stream()
                .filter(module -> module.name().equals("Service"))
                .findFirst().orElseThrow();
        assertEquals(1, service.exports().size());
        List<Ast.ExportBinding> bindings =
                ExportResolver.resolve(program).bindingsFor(owner(program, service));
        assertEquals(1, bindings.size());
        assertEquals("value", bindings.getFirst().externalName());
    }

    private static Ast.ModuleDecl root(Ast.Program program) {
        return program.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst().orElseThrow();
    }

    private static String owner(Ast.Program program) {
        return owner(program, root(program));
    }

    private static String owner(Ast.Program program, Ast.ModuleDecl module) {
        String prefix = program.namespace() == null || program.namespace().isBlank()
                ? "<file>" : program.namespace();
        return prefix + "::" + module.name();
    }

    private static void assertParseError(String code, String source) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("[" + code + "]"), failure.getMessage());
    }

    private static void assertExportError(String code, Ast.Program program) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> ExportResolver.resolve(program));
        assertTrue(failure.getMessage().contains("[" + code + "]"), failure.getMessage());
    }
}
