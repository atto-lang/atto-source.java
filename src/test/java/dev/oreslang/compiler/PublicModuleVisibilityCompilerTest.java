package dev.oreslang.compiler;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Public module exposure changes are observable import-graph ABI changes. */
final class PublicModuleVisibilityCompilerTest {
    private static final String PRIVATE = """
            define module api as
              pub fnc version(): int { return 1; }
            end
            """;
    private static final String PUBLIC = """
            define pub module api as
              pub fnc version(): int { return 1; }
            end
            """;
    private static final String IMPORTER = """
            import module api from "./lib.atto";
            pub fnc main(): int { return 7; }
            """;

    @Test
    void moduleDeclarationUsesCanonicalDefinePubVisibility() {
        var canonical = dev.oreslang.parser.Parser.parse(PUBLIC).modules().getFirst();
        var legacy = dev.oreslang.parser.Parser.parse(PUBLIC.replace("define pub module", "pub define module"))
                .modules().getFirst();
        var privateModule = dev.oreslang.parser.Parser.parse(PRIVATE).modules().getFirst();
        assertEquals(dev.oreslang.ast.Ast.Visibility.PUBLIC, canonical.visibility());
        assertEquals(canonical, legacy, "legacy pub define must lower identically");
        assertEquals(dev.oreslang.ast.Ast.Visibility.PRIVATE, privateModule.visibility());
    }

    @Test
    void moduleVisibilityChangesTheIncrementalExportAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var first = compiler.compile(Map.of("lib.atto", PRIVATE));
        var second = compiler.compile(Map.of("lib.atto", PUBLIC));
        assertNotEquals(first.units().get("lib.atto").abiDigest(),
                second.units().get("lib.atto").abiDigest(),
                "making a module public must invalidate dependent importers");
        assertTrue(second.rebuilt("lib.atto"));
    }

    @Test
    void privateModuleCannotBeSelectedByPublicModuleImporter() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(
                        Map.of("lib.atto", PRIVATE, "app.atto", IMPORTER)));
        assertTrue(failure.getMessage().contains("does not match an exported declaration"),
                failure.getMessage());
    }

    @Test
    void publicFunctionInsidePrivateModuleIsNotAnExport() {
        String library = """
                define module internal as
                  pub fnc leak(): int { return 7; }
                end
                """;
        String importer = """
                import fnc leak from "./lib.atto";
                pub fnc main(): int { return 42; }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(
                        Map.of("lib.atto", library, "app.atto", importer)));
        assertTrue(error.getMessage().contains("leak"), error.getMessage());
        assertTrue(error.getMessage().contains("export"), error.getMessage());
    }

    @Test
    void publicModuleCanBeImportedByTheRealSourceGraph() {
        var result = assertDoesNotThrow(() -> new IncrementalCompiler().compile(
                Map.of("lib.atto", PUBLIC, "app.atto", IMPORTER)));
        assertTrue(result.units().containsKey("lib.atto"));
        assertTrue(result.units().containsKey("app.atto"));
    }
}
