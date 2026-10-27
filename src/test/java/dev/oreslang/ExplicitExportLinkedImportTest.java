package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportLinkedImportTest {
    @Test
    void explicitPublicAliasIsTheCrossFileClassInterfaceName() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(
                Map.of(
                        "lib.ores", """
                                export class Internal as PublicBox;
                                define class Internal as
                                end
                                """,
                        "main.ores", """
                                import class PublicBox from "./lib.ores";
                                pub fnc main(): void { return; }
                                """),
                Map.of()));
    }

    @Test
    void explicitPublicSameNameMakesDefaultPrivateClassImportable() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(
                Map.of(
                        "lib.ores", """
                                export class Box;
                                define class Box as
                                end
                                """,
                        "main.ores", """
                                import class Box from "./lib.ores";
                                pub fnc main(): void { return; }
                                """),
                Map.of()));
    }

    @Test
    void fileAndPackageExportsDoNotWidenIntoCrossFilePublicImports() {
        for (String scope : new String[] {"file", "pkg"}) {
            IncrementalCompiler compiler = new IncrementalCompiler();
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> compiler.compile(
                            Map.of(
                                    "lib.ores", "export[" + scope + "] class Local;\n"
                                            + "define class Local as\nend\n",
                                    "main.ores", """
                                            import class Local from "./lib.ores";
                                            pub fnc main(): void { return; }
                                            """),
                            Map.of()));
            assertTrue(
                    failure.getMessage().contains("does not match an exported declaration"),
                    failure.getMessage());
        }
    }

    @Test
    void aliasDoesNotAlsoExposeDefaultPrivateLocalSpelling() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        Map.of(
                                "lib.ores", """
                                        export class Internal as PublicBox;
                                        define class Internal as
                                        end
                                        """,
                                "main.ores", """
                                        import class Internal from "./lib.ores";
                                        pub fnc main(): void { return; }
                                        """),
                        Map.of()));
        assertTrue(
                failure.getMessage().contains("does not match an exported declaration"),
                failure.getMessage());
    }
}
