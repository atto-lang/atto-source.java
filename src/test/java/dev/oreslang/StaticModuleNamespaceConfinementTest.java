package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StaticModuleNamespaceConfinementTest {
    private static final String MODULE = """
            define module Catalog as
                pub const version: int = 4;
                pub fnc get(): int { return 4; }
            end
            """;

    @Test
    void moduleBindingsAcceptBothNameFirstAndTypeFirstDeclarations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module Catalog as
                    pub const version: int = 4;
                    pub const int legacyVersion = 5;
                    pub let counter: int = 0;
                end
                define module app as
                    pub fnc main(): void {
                        const x = Catalog.version;
                        const y = Catalog.legacyVersion;
                        Catalog.counter = x + y;
                        return;
                    }
                end
                """)));
    }

    @Test
    void directStaticModuleMembersRemainAvailable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(MODULE + """
                routine main(): void {
                    const version = Catalog.version;
                    const result = Catalog.get();
                    return;
                }
                """)));
    }

    @Test
    void localShadowingOfModuleNameStillProducesAnOrdinaryValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(MODULE + """
                routine main(): void {
                    const Catalog = 19;
                    const copied = Catalog;
                    return;
                }
                """)));
    }

    @Test
    void moduleStaticWritesRespectDeclaredBindingMutability() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    routine main(): void {
                        Catalog.version = 5;
                        return;
                    }
                    """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module WritableCatalog as
                    pub let counter: int = 0;
                end
                routine main(): void {
                    WritableCatalog.counter = 5;
                    return;
                }
                """)));
    }

    @Test
    void staticFunctionCanBeReifiedWithoutReifyingItsModule() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(MODULE + """
                routine main(): void {
                    const f = Catalog.get;
                    const result = f();
                    return;
                }
                """)));
    }

    @Test
    void noModuleNamespaceCanBePassedToAnyTypedParameter() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    fnc consume(Any value): void { return; }
                    routine main(): void {
                        consume(Catalog);
                        return;
                    }
                    """)));
    }

    @Test
    void bareNamespaceCannotEscapeViaTupleOrOptionPayload() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    routine main(): void {
                        const tuple = (Catalog, 1);
                        return;
                    }
                    """)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    routine main(): void {
                        const result = Some(Catalog);
                        return;
                    }
                    """)));
    }

    @Test
    void namespaceMarkerCannotBeErasedIntoUnknownOrAny() {
        var namespace = new dev.oreslang.types.Types.ModuleNamespace("Catalog");
        assertFalse(dev.oreslang.types.Types.isAssignable(
                namespace, dev.oreslang.types.Types.Unknown.INSTANCE));
        assertFalse(dev.oreslang.types.Types.isAssignable(
                dev.oreslang.types.Types.Unknown.INSTANCE, namespace));
        assertFalse(dev.oreslang.types.Types.isAssignable(
                namespace, new dev.oreslang.types.Types.Generic("T")));
    }

    @Test
    void importedModuleAliasIsNotAnOrdinaryValue() {
        java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("catalog.ores", MODULE);
        sources.put("client.ores", """
                import module Catalog as ImportedCatalog from "./catalog.ores";
                routine main(): void {
                    const escaped = ImportedCatalog;
                    return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () ->
                new dev.oreslang.compiler.IncrementalCompiler().compile(sources));
    }

    @Test
    void importedWildcardNamespaceIsNotAnOrdinaryValue() {
        java.util.Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("catalog.ores", MODULE);
        sources.put("client.ores", """
                import module * as Namespace from "./catalog.ores";
                routine main(): void {
                    const escaped = Namespace;
                    return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () ->
                new dev.oreslang.compiler.IncrementalCompiler().compile(sources));
    }

    @Test
    void bareModuleNamespaceMustNeverBecomeRuntimeInstance() {
        // Reject before guest execution; internal dispatch facades are not values.
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    routine main(): void {
                        const moduleInstance = Catalog;
                        return;
                    }
                    """)));
    }

    @Test
    void moduleNamespaceMustNotEscapeInsideCollections() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    routine main(): void {
                        const modules = [Catalog];
                        return;
                    }
                    """)));
    }

    @Test
    void moduleNamespaceCannotBeReturnedAsAnOrdinaryValue() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(MODULE + """
                    pub fnc leak(): Any {
                        return Catalog;
                    }
                    """)));
    }
}
