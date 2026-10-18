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
    void bareModuleNamespaceMustNeverBecomeRuntimeInstance() {
        // Known red regression: current TypeChecker exposes moduleShape as a
        // Record for NameExpr, while the evaluator creates a ModuleFacade.
        // The full-AOT static-module contract requires this to be rejected.
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
                    pub fnc leak(): any {
                        return Catalog;
                    }
                    """)));
    }
}
