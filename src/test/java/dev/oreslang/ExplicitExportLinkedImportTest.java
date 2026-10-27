package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportLinkedImportTest {
    @TempDir Path temp;

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
    void explicitAliasExecutesThroughTheSameSourceExportBinding() throws Exception {
        Path lib = temp.resolve("lib.ores");
        Path main = temp.resolve("main.ores");
        Files.writeString(lib, """
                export class Internal as PublicBox;
                define class Internal as
                  pub get(): int { return 7; }
                end
                """);
        Files.writeString(main, """
                import class PublicBox from "./lib.ores";
                pub fnc main(): void {
                  val box = new PublicBox();
                  stdio.stdout.write(box.get());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("7", out.toString(StandardCharsets.UTF_8));
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
