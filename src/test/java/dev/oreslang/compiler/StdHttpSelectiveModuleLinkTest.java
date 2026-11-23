package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Namespace selection and named-module selection must not share semantics. */
final class StdHttpSelectiveModuleLinkTest {
    private static final String LIB_ID = "@std/http.atto";
    private static final String APP_ID = "http-import-app.atto";
    private static final Map<String, Map<String, String>> RESOLUTIONS =
            Map.of(APP_ID, Map.of("atto.std/http", LIB_ID));

    @Test
    void actualStdHttpFileRootNamespaceIsNotADeclaredModule() throws Exception {
        Ast.Program library = Parser.parse(CoreLibrarySources.source("atto.std/http"));
        Ast.Program importer = Parser.parse(
                "import module http, http3 from \"atto.std/http\";\n"
                        + "pub fnc main(): void { return; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ImportGraph.validateLinkedImports(
                        Map.of(LIB_ID, library, APP_ID, importer), RESOLUTIONS));
        assertTrue(error.getMessage().contains("does not match an exported declaration"),
                error.getMessage());
    }

    @Test
    void explicitlyDeclaredModulesCanBeImportedByName() {
        Ast.Program library = Parser.parse("""
                define pub module http as
                  pub fnc status(): int { return 200; }
                end
                define pub module http3 as
                  pub fnc version(): int { return 3; }
                end
                """);
        Ast.Program importer = Parser.parse(
                "import module http, http3 from \"atto.std/http\";\n"
                        + "pub fnc main(): void { return; }");
        assertDoesNotThrow(() -> ImportGraph.validateLinkedImports(
                Map.of(LIB_ID, library, APP_ID, importer), RESOLUTIONS));
    }

    @Test
    void privateModuleCannotBeImportedAcrossUnits() {
        Ast.Program library = Parser.parse("""
                define private module http as
                  pub fnc status(): int { return 200; }
                end
                define pub module http3 as
                  pub fnc version(): int { return 3; }
                end
                """);
        assertEquals(Ast.Visibility.PRIVATE, library.modules().get(0).visibility());
        assertEquals(Ast.Visibility.PUBLIC, library.modules().get(1).visibility());
        Ast.Program importer = Parser.parse(
                "import module http as web from \"atto.std/http\";\n"
                        + "pub fnc main(): void { return; }");
        var error = assertThrows(IllegalArgumentException.class,
                () -> ImportGraph.validateLinkedImports(
                        Map.of(LIB_ID, library, APP_ID, importer), RESOLUTIONS));
        assertTrue(error.getMessage().contains("does not match an exported declaration"),
                error.getMessage());
    }

    @Test
    void namespaceWildcardSelectsUnitRatherThanNamedModules() throws Exception {
        Ast.Program library = Parser.parse(CoreLibrarySources.source("atto.std/http"));
        Ast.Program importer = Parser.parse(
                "import * as web from \"atto.std/http\";\n"
                        + "pub fnc main(): void { return; }");
        assertDoesNotThrow(() -> ImportGraph.validateLinkedImports(
                Map.of(LIB_ID, library, APP_ID, importer), RESOLUTIONS));
    }
}
