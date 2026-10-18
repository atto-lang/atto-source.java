package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModuleLocalImportTest {
    @TempDir
    Path temp;

    @Test
    void parserRecordsTheLexicalModuleOnEachImport() {
        Ast.Program program = Parser.parse("""
                import fnc rootValue from "./root";

                define module Alpha as
                  import fnc value from "./alpha";
                  pub fnc read(): int { return value(); }
                end

                define module Beta as
                  import fnc value from "./beta";
                  pub fnc read(): int { return value(); }
                end

                pub routine main(): void { return; }
                """);

        assertEquals(3, program.imports().size());
        assertNull(program.imports().get(0).lexicalModule());
        assertEquals("Alpha", program.imports().get(1).lexicalModule());
        assertEquals("Beta", program.imports().get(2).lexicalModule());
    }

    @Test
    void twoModulesMayUseTheSameImportedBindingIndependently() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define module Alpha as
                  import fnc value from "./alpha";
                  pub fnc use(): void {
                    value();
                    return;
                  }
                end

                define module Beta as
                  import fnc value from "./beta";
                  pub fnc use(): void {
                    value();
                    return;
                  }
                end
                """)));
    }

    @Test
    void fileRootImportDoesNotLeakIntoNamedModule() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        import fnc value from "./dep";

                        define module Alpha as
                          pub fnc read(): int {
                            return value();
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("file/root-scoped"), failure.getMessage());
        assertTrue(failure.getMessage().contains("move the import into the module prelude"), failure.getMessage());
    }

    @Test
    void importsMustRemainInTheModulePrelude() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module Alpha as
                          pub fnc first(): void { return; }
                          import fnc value from "./dep";
                        end
                        """));

        assertTrue(
                failure.getMessage().contains("module imports must appear before module declarations"),
                failure.getMessage());
    }

    @Test
    void runtimeResolvesIdenticalAliasesByLexicalModule() throws Exception {
        Path alpha = temp.resolve("alpha.ores");
        Path beta = temp.resolve("beta.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(alpha, """
                pub fnc value(): int { return 1; }
                """);
        Files.writeString(beta, """
                pub fnc value(): int { return 2; }
                """);
        Files.writeString(main, """
                define module Alpha as
                  import fnc value from "./alpha.ores";
                  pub fnc read(): int { return value(); }
                end

                define module Beta as
                  import fnc value from "./beta.ores";
                  pub fnc read(): int { return value(); }
                end

                pub routine main(): void {
                  stdio.stdout.write(Alpha.read());
                  stdio.stdout.write("|");
                  stdio.stdout.write(Beta.read());
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals("1|2", output.toString(StandardCharsets.UTF_8));
        assertEquals(3, build.units().size());
    }
}
