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
    void explicitlyExportedDefaultPrivateSignatureInvalidatesImporter() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          pub get(): int { return 1; }
                        end
                        """,
                "main.ores", """
                        import class PublicBox from "./lib.ores";
                        pub fnc main(): void { return; }
                        """);

        compiler.compile(initial);

        Map<String, String> changed = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          pub get(): String { return "one"; }
                        end
                        """,
                "main.ores", initial.get("main.ores"));

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("lib.ores"));
        assertTrue(result.rebuilt("main.ores"),
                "signature changes behind an explicit export must invalidate importers");
    }

    @Test
    void synthesizedConstructorFieldTypeIsPartOfExplicitExportAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          val int value;
                        end
                        """,
                "main.ores", """
                        import class PublicBox from "./lib.ores";
                        pub fnc main(): void { return; }
                        """);

        compiler.compile(initial);

        Map<String, String> changed = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          val String value;
                        end
                        """,
                "main.ores", initial.get("main.ores"));

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("lib.ores"));
        assertTrue(result.rebuilt("main.ores"),
                "synthesized constructor parameter types are part of the exported class ABI");
    }

    @Test
    void explicitExportAliasRenameInvalidatesWildcardImporter() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                        end
                        """,
                "main.ores", """
                        import * as lib from "./lib.ores";
                        pub fnc main(): void { return; }
                        """);

        compiler.compile(initial);

        Map<String, String> changed = Map.of(
                "lib.ores", """
                        export class Internal as PublicCrate;
                        define class Internal as
                        end
                        """,
                "main.ores", initial.get("main.ores"));

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("lib.ores"));
        assertTrue(result.rebuilt("main.ores"),
                "external export names are source ABI and must invalidate wildcard importers");
    }

    @Test
    void explicitExportImplementationOnlyEditKeepsImporterReusable() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> initial = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          pub get(): int { return 1; }
                        end
                        """,
                "main.ores", """
                        import class PublicBox from "./lib.ores";
                        pub fnc main(): void { return; }
                        """);

        compiler.compile(initial);

        Map<String, String> changed = Map.of(
                "lib.ores", """
                        export class Internal as PublicBox;
                        define class Internal as
                          pub get(): int { return 2; }
                        end
                        """,
                "main.ores", initial.get("main.ores"));

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("lib.ores"));
        assertTrue(result.reused("main.ores"),
                "method body-only edits must not perturb the resolved source-interface digest");
    }

    @Test
    void sourceExportAliasAndImportAliasComposeWithoutLeakingLocalName() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(
                Map.of(
                        "lib.ores", """
                                export class Internal as PublicBox;
                                define class Internal as
                                  pub self_ref(Internal other): Internal { return other; }
                                end
                                """,
                        "main.ores", """
                                import class PublicBox as Box from "./lib.ores";
                                pub fnc use(Box value): Box {
                                  return value.self_ref(value);
                                }
                                """),
                Map.of()));
    }

    @Test
    void exportedDefaultPrivateClassMayExposePublicConstructor() throws Exception {
        Path lib = temp.resolve("ctor-lib.ores");
        Path main = temp.resolve("ctor-main.ores");
        Files.writeString(lib, """
                export class Internal as PublicBox;
                define class Internal as
                  val int value;
                  pub constructor(int value) {
                    self.value = value;
                  }
                  pub get(): int { return self.value; }
                end
                """);
        Files.writeString(main, """
                import class PublicBox from "./ctor-lib.ores";
                pub fnc main(): void {
                  val box = new PublicBox(9);
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

        assertEquals("9", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void privateConstructorRemainsUnavailableThroughPublicExportAlias() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        Map.of(
                                "lib.ores", """
                                        export class Internal as PublicBox;
                                        define class Internal as
                                          private constructor() { return; }
                                        end
                                        """,
                                "main.ores", """
                                        import class PublicBox from "./lib.ores";
                                        pub fnc make(): void {
                                          val box = new PublicBox();
                                          return;
                                        }
                                        """),
                        Map.of()));
        assertTrue(failure.getMessage().contains("constructor for imported class"),
                failure.getMessage());
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
