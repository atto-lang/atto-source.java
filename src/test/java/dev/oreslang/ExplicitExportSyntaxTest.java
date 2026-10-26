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
        assertTrue(failure.getMessage().contains("[EXPORT_IMPORT_PRELUDE]"), failure.getMessage());
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
    void matchesAllCommonSourceSyntaxVectors() {
        assertParses("export X;");
        assertParses("export[pkg] actor Counter;");
        assertParses("export(package) class {X, Y as Z,};");
        assertParses("export {X, Y as Z, #ready,};");
        assertParses("export symbol {#ready, #failed,};");
        assertParses("export class {X};");
        assertParses("export symbol #ready as #ready;");

        assertParseError("EXPORT_SEMICOLON_REQUIRED", "export X");
        assertParseError("EXPORT_EMPTY_GROUP", "export {};");
        assertParseError("EXPORT_EMPTY_GROUP", "export class {};");
        assertParseError("EXPORT_MALFORMED_LIST", "export actor {,X};");
        assertParseError("EXPORT_MALFORMED_LIST", "export actor {X,,Y};");
        assertParseError("EXPORT_MALFORMED_LIST", "export actor {X Y};");
        assertParseError("EXPORT_NESTED_GROUP", "export actor {{X}};");
        assertParseError("EXPORT_WILDCARD_UNSUPPORTED", "export *;");
        assertParseError("EXPORT_REEXPORT_UNSUPPORTED", "export * from \"./dep\";");
        assertParseError("EXPORT_REEXPORT_UNSUPPORTED", "export from \"./dep\" {X};");
        assertParseError("EXPORT_QUALIFIED_TARGET_UNSUPPORTED", "export dep.X;");
        assertParseError("EXPORT_QUALIFIED_TARGET_UNSUPPORTED", "export dep::X;");
        assertParseError("EXPORT_GENERIC_TARGET_UNSUPPORTED", "export Foo<int>;");
        assertParseError("EXPORT_CALLABLE_SIGNATURE_UNSUPPORTED", "export foo(int);");
        assertParseError("EXPORT_KIND_PREFIX_IN_GROUP", "export {class X, actor Y};");
        assertParseError("EXPORT_KIND_PREFIX_IN_GROUP", "export actor {actor X};");
        assertParseError("EXPORT_SYMBOL_BINDING_ALIAS_UNSUPPORTED", "export symbol #ready as Ready;");
        assertParseError("EXPORT_SYMBOL_IDENTITY_MISMATCH", "export symbol #ready as #ok;");
        assertParseError("EXPORT_MULTIPLE_SCOPES", "export[pkg][file] X;");
        assertParseError("EXPORT_UNKNOWN_SCOPE", "export[project] X;");
        assertParseError("EXPORT_SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE", "export symbol ready as #ready;");
        assertParseError("EXPORT_UNKNOWN_DECLARATION_KIND", "export string ready as 'ready';");
        assertParseError("EXPORT_LITERAL_ALIAS_UNSUPPORTED", "export const ready as 'ready';");
        assertParseError("EXPORT_LITERAL_ALIAS_UNSUPPORTED", "export class Foo as \"foo\";");
    }

    @Test
    void rejectsPinnedAdversarialSyntaxWithStableCategories() {
        assertParseError("EXPORT_SEMICOLON_REQUIRED", "export class X\nfnc main(): void { return; }");
        assertParseError("EXPORT_EMPTY_GROUP", "export actor {}; fnc main(): void { return; }");
        assertParseError("EXPORT_MALFORMED_LIST", "export actor {A,,B}; fnc main(): void { return; }");
        assertParseError("EXPORT_WILDCARD_UNSUPPORTED", "export *; fnc main(): void { return; }");
        assertParseError("EXPORT_REEXPORT_UNSUPPORTED", "export from \"./dep\" {X}; fnc main(): void { return; }");
        assertParseError("EXPORT_QUALIFIED_TARGET_UNSUPPORTED", "export dep.X; fnc main(): void { return; }");
        assertParseError("EXPORT_QUALIFIED_TARGET_UNSUPPORTED", "export dep::X; fnc main(): void { return; }");
        assertParseError("EXPORT_GENERIC_TARGET_UNSUPPORTED", "export Foo<int>; fnc main(): void { return; }");
        assertParseError("EXPORT_CALLABLE_SIGNATURE_UNSUPPORTED", "export foo(int); fnc main(): void { return; }");
        assertParseError("EXPORT_KIND_PREFIX_IN_GROUP", "export {class X, actor Y}; fnc main(): void { return; }");
        assertParseError("EXPORT_SYMBOL_IDENTITY_MISMATCH", "export symbol #ready as #other; fnc main(): void { return; }");
        assertParseError("EXPORT_SYMBOL_IDENTITY_TARGET_REQUIRES_SYMBOL_SOURCE", "export symbol ready as #ready; fnc main(): void { return; }");
        assertParseError("EXPORT_UNKNOWN_DECLARATION_KIND", "export string ready as 'ready'; fnc main(): void { return; }");
        assertParseError("EXPORT_LITERAL_ALIAS_UNSUPPORTED", "export const ready as 'ready'; fnc main(): void { return; }");
    }

    @Test
    void rejectsExportsFromExecutableClassActorAndInterfaceScopes() {
        assertParseError("EXPORT_LOCAL_SCOPE", """
                fnc bad(): void {
                  export X;
                  return;
                }
                """);
        assertParseError("EXPORT_LOCAL_SCOPE", """
                define class Box as
                  export X;
                end
                """);
        assertParseError("EXPORT_LOCAL_SCOPE", """
                actor Worker
                  export X;
                end
                """);
        assertParseError("EXPORT_LOCAL_SCOPE", """
                interface Api
                  export X;
                end
                """);
    }

    @Test
    void contextualSymbolCanRemainAnUntypedLocalExportName() {
        Ast.Program program = Parser.parse("""
                export symbol as PublicSymbol;
                fnc symbol(): int { return 1; }
                """);

        Ast.ExportDecl declaration = root(program).exports().getFirst();
        assertEquals(null, declaration.kind());
        assertEquals("symbol", declaration.items().getFirst().localName());
        assertEquals("PublicSymbol", declaration.items().getFirst().externalName());

        Ast.ExportBinding binding =
                ExportResolver.resolve(program).bindingsFor(owner(program)).getFirst();
        assertEquals(Ast.ExportKind.FNC, binding.localKind());
        assertEquals("PublicSymbol", binding.externalName());
    }

    @Test
    void contextualSymbolInsideGroupCanRemainAnOrdinaryLocalName() {
        Ast.Program program = Parser.parse("""
                export {symbol as PublicSymbol};
                fnc symbol(): int { return 1; }
                """);
        Ast.ExportDecl declaration = root(program).exports().getFirst();
        assertEquals(null, declaration.kind());
        assertEquals("symbol", declaration.items().getFirst().localName());
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
        assertExportError("EXPORT_IMPORTED_NAME", imported);
    }

    @Test
    void untypedSameSpellingAcrossTypeAndValueNamespacesIsAmbiguous() {
        Ast.Program program = Parser.parse("""
                export X;
                define type X = int;
                const int X = 1;
                """);
        assertExportError("EXPORT_AMBIGUOUS_TARGET", program);
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
        assertExportError("EXPORT_API_LEAK", leaking);

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

    private static void assertParses(String exportSource) {
        assertDoesNotThrow(() -> Parser.parse(exportSource + "\nfnc main(): void { return; }"));
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
